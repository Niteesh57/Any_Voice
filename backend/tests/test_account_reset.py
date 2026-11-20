import asyncio
import os
import tempfile
import unittest
from dataclasses import replace
import httpx
from anyvoice.api import create_app
from anyvoice.memory import MemoryStore,now
from anyvoice.session import Session
from anyvoice.audio_queue import AudioQueue
from test_pipeline import configured

OWNER='a'*32
OTHER='b'*32
TABLES=('questions','tasks','memories','turns','events','receipts','actions','media_segments','caption_provenance')

class ResetTests(unittest.IsolatedAsyncioTestCase):
 async def test_reset_cancels_owner_sessions_erases_all_content_and_retries_safely(self):
  with tempfile.TemporaryDirectory() as folder:
   path=os.path.join(folder,'reset.db')
   app=create_app(replace(configured(),memory_path=path),native_check=lambda:True)
   store=app.state.memory
   try:
    for owner in (OWNER,OTHER):
     store.identity(owner,'k'*64)
     store.execute(owner,'task-create','task_create',{'title':'Private task'})
     store.execute(owner,'fact-create','memory_save',{'key':'preference','text':'Private fact'})
     store.turn(owner,'turn-'+owner,'Private question','Private reply')
     store.media_segment(owner,'video','segment','source','hi-IN','Private video')
     store.media_segment(owner,'talk','segment','translation','te-IN','Private conversation',source_kind='conversation')
     store.db.execute('INSERT INTO questions VALUES(?,?,?,?,?,?)',(owner,'q','{}','pending','',now()))
     store.db.execute('INSERT INTO actions VALUES(?,?,?,?,?,?,?)',(owner,owner,'open_app','{}','pending','{}',1e15))
     store.screen_results[(owner,'scope')]=(0,{'text':'Private screen'})
    store.db.commit()
    own=Session(owner=OWNER);own.audio=AudioQueue();own.audio.append(b'01'*800,1,1,1)
    other=Session(owner=OTHER);app.state.sessions[own.id]=own;app.state.sessions[other.id]=other
    started=asyncio.Event()
    async def running():
     started.set()
     try:await asyncio.Event().wait()
     finally:store.turn(OWNER,'cleanup','Late cleanup','Must be removed too')
    own.task=asyncio.create_task(running());await started.wait()
    headers={'Authorization':'Bearer '+configured().access_token,'X-AnyVoice-Device':OWNER,'X-AnyVoice-Device-Key':'k'*64}
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://reset') as client:
     self.assertEqual((await client.post('/v1/me/reset')).status_code,401)
     self.assertEqual((await client.post('/v1/me/reset',headers={'Authorization':headers['Authorization']})).status_code,401)
     self.assertEqual((await client.post('/v1/me/reset',headers={**headers,'X-AnyVoice-Device-Key':'wrong'*12})).status_code,401)
     self.assertEqual((await client.post('/v1/me/reset',headers=headers)).json(),{'status':'reset'})
     self.assertTrue(own.task.done());self.assertTrue(own.stopped.is_set())
     self.assertNotIn(own.id,app.state.sessions);self.assertIn(other.id,app.state.sessions)
     for table in TABLES:
      self.assertEqual(store.db.execute('SELECT count(*) FROM '+table+' WHERE owner=?',(OWNER,)).fetchone()[0],0,table)
      self.assertGreater(store.db.execute('SELECT count(*) FROM '+table+' WHERE owner=?',(OTHER,)).fetchone()[0],0,table)
     self.assertNotIn((OWNER,'scope'),store.screen_results);self.assertIn((OTHER,'scope'),store.screen_results)
     self.assertEqual((await client.post('/v1/me/reset',headers=headers)).status_code,200)
     self.assertEqual((await client.get('/v1/me/overview',headers=headers)).json()['tasks'],[])
   finally:store.close()
   reopened=MemoryStore(path)
   try:
    self.assertFalse(reopened.context(OWNER)['recent_conversation'])
    self.assertTrue(reopened.context(OTHER)['tasks'])
   finally:reopened.close()
