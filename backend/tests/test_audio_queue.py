import asyncio
import json
import unittest
from types import SimpleNamespace
import httpx
from anyvoice.audio_queue import AudioQueue
from anyvoice.session import Pipeline,Session
from anyvoice.api import create_app
from test_pipeline import configured,FakeCompletion

class AudioStorageTests(unittest.TestCase):
 def test_order_immutability_ack_retry_and_cleanup(self):
  queue=AudioQueue()
  try:
   one=b'\x01\x00'*1600;two=b'\x02\x00'*2000
   self.assertEqual(queue.append(one),1);self.assertEqual(queue.append(two),2)
   self.assertEqual(queue.clips[1]['path'].read_bytes(),one)
   self.assertEqual([c['sequence'] for c in queue.manifest(0)['clips']],[1,2])
   self.assertEqual([c['sequence'] for c in queue.manifest(1)['clips']],[2])
   self.assertNotIn('path',queue.manifest(0)['clips'][0])
   queue.acknowledge(2);queue.acknowledge(1)
   self.assertEqual(queue.total_bytes,0);self.assertFalse(queue.clips)
  finally:queue.close()
  self.assertFalse(queue.directory.exists())
 def test_incomplete_pcm_is_rejected(self):
  queue=AudioQueue()
  try:
   for pcm in [b'',b'x']:
    with self.assertRaises(ValueError):queue.append(pcm)
  finally:queue.close()

class QueuedPreparationTests(unittest.IsolatedAsyncioTestCase):
 async def test_pcm_streams_before_completion_without_interrupting_current_generation(self):
  completion=FakeCompletion();first=asyncio.Event();second=asyncio.Event();calls=[]
  class Services:
   async def speak(self,text):
    calls.append(text);pipeline.receive_pcm(b'\x01\x00'*1600)
    (first if len(calls)==1 else second).set()
  session=Session();pipeline=Pipeline(configured(),session,transport=SimpleNamespace(),services=Services(),completion=completion)
  pipeline.phrases.put_nowait(('one',0)) # Deliberately older than the former 15 second cutoff.
  task=asyncio.create_task(pipeline.speak_phrases())
  try:
   await asyncio.wait_for(first.wait(),1)
   pipeline.phrases.put_nowait(('two',0))
   await asyncio.sleep(.3)
   self.assertEqual(calls,['one']);self.assertEqual(list(session.audio.clips),[1,2])
   completion.done.set()
   await asyncio.wait_for(second.wait(),2)
   self.assertEqual(calls,['one','two']);self.assertEqual(list(session.audio.clips),[1,2,3,4])
   self.assertFalse(session.stopped.is_set())
   completion.done.set();await asyncio.sleep(.4)
   self.assertEqual(list(session.audio.clips),[1,2,3,4])
  finally:
   task.cancel();await asyncio.gather(task,return_exceptions=True);session.audio.close()
 async def test_stream_framing_resume_and_live_wakeup(self):
  import struct
  queue=AudioQueue();finished=False
  try:
   stream=queue.packets(0,lambda:finished)
   waiting=asyncio.create_task(anext(stream))
   await asyncio.sleep(.01)
   self.assertFalse(waiting.done())
   payload=b'\x01\x00'*800
   queue.append(payload)
   frame=await asyncio.wait_for(waiting,1)
   self.assertEqual(struct.unpack('!II',frame[:8]),(1,1600))
   self.assertEqual(frame[8:],payload)
   await stream.aclose()
   queue.append(payload)
   resumed=queue.packets(1,lambda:True)
   frame=await anext(resumed)
   self.assertEqual(struct.unpack('!II',frame[:8]),(2,1600))
   await resumed.aclose()
  finally:queue.close()
 async def test_audio_api_is_authenticated_and_preserves_bytes(self):
  settings=configured();app=create_app(settings,native_check=lambda:True)
  session=Session(state='ready');session.audio=AudioQueue();app.state.sessions[session.id]=session
  pcm=b'\x00\x01'*800;session.audio.append(pcm)
  path='/v1/sessions/'+session.id+'/audio'
  headers={'Authorization':'Bearer '+settings.access_token}
  try:
   async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as client:
    self.assertEqual((await client.get(path+'/1')).status_code,401)
    self.assertEqual((await client.get(path+'/1',headers=headers)).content,pcm)
    data=(await client.get(path,headers=headers)).json()
    self.assertEqual(data['clips'][0]['bytes'],len(pcm))
    self.assertEqual((await client.post(path+'/1/played',headers=headers)).status_code,200)
    self.assertEqual((await client.post(path+'/1/played',headers=headers)).status_code,200)
    self.assertEqual((await client.get(path+'/1',headers=headers)).status_code,404)
  finally:session.audio.close()

if __name__=='__main__':unittest.main()
