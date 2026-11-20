import json
import os
import tempfile
import unittest
from types import SimpleNamespace
from anyvoice.memory import MemoryStore
from anyvoice.session import Session, Pipeline
from anyvoice.tools import ToolExecutor
from test_pipeline import configured, caption

OWNER='a'*32
OTHER='b'*32

class MediaMemoryTests(unittest.IsolatedAsyncioTestCase):
 async def test_caption_pipeline_persists_and_bubble_can_recall_after_restart(self):
  with tempfile.TemporaryDirectory() as folder:
   path=os.path.join(folder,'memory.db');store=MemoryStore(path)
   session=Session(owner=OWNER,memory_store=store,source_language='en-IN')
   pipeline=Pipeline(configured(),session,transport=SimpleNamespace())
   try:
    source={'transcript':{'uid':1001,'language':'en-IN','sentenceId':1,'isFinal':True,'text':'For Maggi, boil water then add noodles and masala.'}}
    pipeline.receive_caption(json.dumps(source).encode())
    for text in ('నీటిని మరిగించి', 'నీటిని మరిగించి నూడుల్స్, మసాలా వేసి రెండు నిమిషాలు ఉడికించండి.'):
     pipeline.receive_caption(json.dumps(caption(text=text,identity=1)).encode())
    pipeline.receive_caption(json.dumps(caption(text=text,identity=1)).encode())
    # Same segment from another session must remain a separate recording.
    store.media_segment(OWNER,'c'*32,'1','source','en-IN','C++ pointers store memory addresses.')
    store.media_segment(OTHER,'d'*32,'1','source','en-IN','Other device private video.')
    store.close();store=MemoryStore(path)
    tools=ToolExecutor(store,OWNER,'Asia/Kolkata',{})
    recalled=await tools.execute('e'*32+':0','media_recall',{'session_id':session.id,'query':''})
    self.assertEqual(len(recalled['segments']),2)
    self.assertIn('Maggi',recalled['segments'][0]['text'])
    self.assertEqual(recalled['segments'][1]['text'],text)
    self.assertNotIn('Other device',json.dumps(store.context(OWNER)))
    self.assertEqual(len(store.media_recall(OTHER)['segments']),1)
    self.assertIn('C++',json.dumps(store.context(OWNER)))
    self.assertIn('Maggi',json.dumps(store.execute(OWNER,'search','memory_search',{'query':'Maggi'})))
   finally:store.close();session.audio.close()

 async def test_only_captured_media_is_saved_no_assistant_or_wrong_uid(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  session=Session(owner=OWNER,memory_store=store,source_language='en-IN')
  pipeline=Pipeline(configured(),session,transport=SimpleNamespace());self.addCleanup(session.audio.close)
  pipeline.receive_caption(json.dumps(caption(text='wrong participant',uid=2001)).encode())
  pipeline.receive_caption(json.dumps(caption(text='partial',final=False)).encode())
  self.assertFalse(store.media_recall(OWNER)['segments'])
  session.mode='bubble'
  pipeline.receive_caption(json.dumps(caption(text='assistant answer')).encode())
  self.assertFalse(store.media_recall(OWNER)['segments'])

 async def test_context_is_bounded_without_deleting_full_transcript(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  for index in range(80):store.media_segment(OWNER,'f'*32,str(index),'source','en-IN',str(index)+' '+('x'*1000))
  recent=store.context(OWNER)['recent_media']
  self.assertTrue(recent['excerpt_limited'])
  self.assertLessEqual(sum(len(r['text']) for r in recent['segments']),12000)
  self.assertEqual(len(store.rows('SELECT * FROM media_segments')),80)
  cursors=set();before=None
  while True:
   page=store.media_recall(OWNER,session_id='f'*32,before=before,limit=10)
   if not page['segments']:break
   for row in page['segments']:
    self.assertNotIn(row['cursor'],cursors);cursors.add(row['cursor'])
   before=page['next_before']
  self.assertEqual(len(cursors),80)
  self.assertEqual(store.media_recall(OWNER,query='0 ')['segments'][0]['segment_id'],'0')
