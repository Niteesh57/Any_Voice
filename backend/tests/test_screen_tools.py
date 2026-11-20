import asyncio
import base64
import json
import time
import unittest
from dataclasses import replace
from types import SimpleNamespace

import httpx
from anyvoice.api import DeviceResult
from anyvoice.memory import MemoryStore
from anyvoice.qwen_audio import QwenAudio
from anyvoice.tools import ToolExecutor
from test_pipeline import configured

OWNER = 'e' * 32
JPEG = base64.b64encode(b'\xff\xd8fixture').decode()


class ScreenToolTests(unittest.IsolatedAsyncioTestCase):
 async def test_bounded_scroll_observations_ephemeral_and_writes_blocked(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  session=SimpleNamespace(cancel_research=False,stopped=asyncio.Event(),screen_research=False)
  executor=ToolExecutor(store,OWNER,'Asia/Kolkata',{'fixture':'Fixture'},True,session)
  seen=[]
  async def phone():
   while True:
    for action in store.actions(OWNER):
     seen.append(action);page=len(seen)
     store.action_result(OWNER,action['id'],{'status':'observed' if page==1 else 'scrolled','package_name':'fixture','text':'Candidate '+str(page),'can_scroll':True,'content_hash':str(page),'image_data':JPEG})
    await asyncio.sleep(.01)
  worker=asyncio.create_task(phone())
  try:result=await executor.execute('a'*32+':0','explore_screen',{'goal':'Compare candidates','max_pages':4})
  finally:worker.cancel();await asyncio.gather(worker,return_exceptions=True)
  self.assertEqual(len(result['viewports']),4);self.assertEqual(len(seen),4)
  self.assertEqual(seen[1]['arguments']['expected_package'],'fixture')
  self.assertTrue(session.screen_research)
  for row in store.rows('SELECT result FROM actions'):
   self.assertNotIn(JPEG,row['result']);self.assertNotIn('Candidate',row['result'])
  blocked=await executor.execute('a'*32+':1','task_create',{'title':'Injected screen instruction'})
  self.assertEqual(blocked['status'],'unavailable');self.assertFalse(store.context(OWNER)['tasks'])

 async def test_unchanged_screen_stops_and_cancel_stops_before_action(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  session=SimpleNamespace(cancel_research=False,stopped=asyncio.Event(),screen_research=False)
  executor=ToolExecutor(store,OWNER,'Asia/Kolkata',{},True,session);count=0
  async def phone():
   nonlocal count
   while True:
    for action in store.actions(OWNER):
     count+=1;store.action_result(OWNER,action['id'],{'status':'observed','package_name':'fixture','text':'Same page','content_hash':'same','can_scroll':True})
    await asyncio.sleep(.01)
  worker=asyncio.create_task(phone())
  try:result=await executor.execute('b'*32+':0','explore_screen',{'goal':'Read','max_pages':4})
  finally:worker.cancel();await asyncio.gather(worker,return_exceptions=True)
  self.assertEqual(count,2);self.assertEqual(len(result['viewports']),1)
  self.assertEqual(result['stop_reason'],'unchanged')
  session.cancel_research=True
  self.assertEqual((await executor.execute('c'*32+':0','read_screen',{}))['status'],'cancelled')

 async def test_pending_screen_actions_cancelled_by_scope_and_disabled_capability(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  store.queue_action(OWNER,'old','read_screen',{'_scope':'old'},time.time()+20)
  store.queue_action(OWNER,'new','read_screen',{'_scope':'new'},time.time()+20)
  store.queue_action('f'*32,'other','read_screen',{'_scope':'old'},time.time()+20)
  store.cancel_actions(OWNER,'old')
  self.assertEqual([a['id'] for a in store.actions(OWNER)],['new'])
  self.assertEqual(len(store.actions('f'*32)),1)
  executor=ToolExecutor(store,OWNER,'Asia/Kolkata',{})
  self.assertEqual((await executor.execute('a'*32+':0','read_screen',{}))['status'],'unavailable')
 async def test_question_scoped_durable_and_ordinal_choice_validated(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  question=store.execute(OWNER,'ask','ask_user',{'question':'Which language?','goal':'Choose a course','choices':[{'id':'te','label':'Telugu'},{'id':'en','label':'English'}]})['question']
  self.assertEqual(store.context(OWNER)['question']['state'],'pending')
  self.assertIsNone(store.context('f'*32)['question'])
  with self.assertRaises(ValueError):
   store.execute(OWNER,'bad','answer_question',{'question_id':question['id'],'answer':'first','choice_id':'wrong'})
  store.execute(OWNER,'answer','answer_question',{'question_id':question['id'],'answer':'first','choice_id':'te'})
  self.assertEqual(store.context(OWNER)['question']['answer']['choice_id'],'te')
  store.execute(OWNER,'cancel','cancel_question',{});self.assertIsNone(store.question(OWNER))
  free=store.execute(OWNER,'free','ask_user',{'question':'What budget?','goal':'Compare prices'})['question']
  store.execute(OWNER,'free-answer','answer_question',{'question_id':free['id'],'answer':'Under 500'})
  self.assertEqual(store.question(OWNER)['answer']['answer'],'Under 500')


class VisionAgentTests(unittest.IsolatedAsyncioTestCase):
 async def test_images_reach_model_and_question_ends_turn(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close);requests=[]
  plan={'user_text':'Compare the screen','reply':'','actions':[{'name':'read_screen','arguments':{}}]}
  async def handle(request):
   body=json.loads(request.content);requests.append(body)
   if len(requests)==1:
    delta={'tool_calls':[{'index':0,'id':'plan','function':{'name':'plan_turn','arguments':json.dumps(plan)}}]}
   else:
    images=[v for m in body['messages'] if isinstance(m.get('content'),list) for v in m['content'] if v.get('type')=='image_url']
    self.assertEqual(len(images),1)
    self.assertTrue(images[0]['image_url']['url'].startswith('data:image/jpeg;base64,'))
    delta={'tool_calls':[{'index':0,'id':'ask','function':{'name':'ask_user','arguments':json.dumps({'question':'Beginner or advanced?','goal':'Compare courses','choices':[{'id':'beginner','label':'Beginner'},{'id':'advanced','label':'Advanced'}]})}}]}
   return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':delta}]})+'\n\ndata: [DONE]\n\n')
  settings=replace(configured(),dashscope_key='fixture',bubble_llm_model='fixture',bubble_llm_url='https://qwen.example/chat/completions')
  q=QwenAudio(settings,'en-US',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  from anyvoice.session import Session
  session=Session(owner=OWNER,screen_tools=False)
  q.use_memory(store,OWNER,'Asia/Kolkata',{},False,session)
  session.screen_tools=True
  async def phone():
   while True:
    for action in store.actions(OWNER):
     store.action_result(OWNER,action['id'],{'status':'observed','package_name':'fixture','text':'Course A','image_data':JPEG,'can_scroll':False})
    await asyncio.sleep(.01)
  worker=asyncio.create_task(phone())
  try:answer=''.join([x async for x in q.answer(b'01'*3200)])
  finally:worker.cancel();await asyncio.gather(worker,return_exceptions=True);await q.close()
  self.assertEqual(answer,'Beginner or advanced? 1. Beginner 2. Advanced');self.assertEqual(len(requests),2)
  self.assertEqual(store.question(OWNER)['state'],'pending')
  self.assertFalse(store.screen_results)
  self.assertNotIn('image_data',json.dumps(store.context(OWNER)))

 def test_transport_rejects_invalid_base64_and_non_jpeg(self):
  for data in ['not base64',base64.b64encode(b'not a jpeg').decode()]:
   with self.assertRaises(ValueError):DeviceResult(status='observed',image_data=data)
