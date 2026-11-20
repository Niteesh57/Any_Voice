import asyncio
import json
import os
import tempfile
import time
import unittest
from dataclasses import replace
from datetime import datetime
from zoneinfo import ZoneInfo
import httpx
from anyvoice.memory import MemoryStore
from anyvoice.tools import ToolExecutor,validate
from anyvoice.qwen_audio import QwenAudio
from anyvoice.api import create_app
from test_pipeline import configured

OWNER='1'*32
OTHER='2'*32

class StoreTests(unittest.TestCase):
 def test_persistent_tasks_completion_retry_isolation_and_forget(self):
  with tempfile.TemporaryDirectory() as folder:
   path=os.path.join(folder,'memory.db');s=MemoryStore(path)
   s.identity(OWNER,'a'*64);s.identity(OWNER,'a'*64)
   with self.assertRaises(ValueError):s.identity(OWNER,'wrong'*10)
   first=s.execute(OWNER,'create','task_create',{'title':'Presentation','due_date':'2026-11-21'})
   again=s.execute(OWNER,'create','task_create',{'title':'Presentation'})
   self.assertEqual(first,again);task=first['task']['id'];s.close()
   s=MemoryStore(path);self.assertEqual(len(s.context(OWNER)['tasks']),1);self.assertFalse(s.context(OTHER)['tasks'])
   with self.assertRaises(ValueError):s.execute(OTHER,'complete','task_complete',{'task_id':task})
   s.execute(OWNER,'complete','task_complete',{'task_id':task});s.execute(OWNER,'complete2','task_complete',{'task_id':task})
   self.assertEqual(s.task(OWNER,task)['status'],'done')
   day=datetime.now(ZoneInfo('Asia/Kolkata')).date().isoformat()
   events=s.execute(OWNER,'today','daily_review',{'date':day,'timezone':'Asia/Kolkata'})['recorded_events']
   self.assertEqual(sum(e['kind']=='task_completed' for e in events),1)
   fact=s.execute(OWNER,'remember','memory_save',{'key':'style','text':'Short explanations'})['memory']
   s.turn(OWNER,'turn','Remember short explanations','I remember')
   s.execute(OWNER,'forget','memory_forget',{'memory_id':fact['id']})
   self.assertFalse(s.context(OWNER)['memories']);self.assertFalse(s.context(OWNER)['recent_conversation']);s.close()
 def test_daily_review_uses_local_day_and_editing_done_task_is_not_completion(self):
  s=MemoryStore(':memory:');self.addCleanup(s.close)
  s.db.execute('INSERT INTO events VALUES(?,?,?,?,?)',('event',OWNER,'task_completed',json.dumps({'title':'Late work'}),'2026-11-19T20:00:00+00:00'));s.db.commit()
  result=s.execute(OWNER,'day-check','daily_review',{'date':'2026-11-20','timezone':'Asia/Kolkata'})
  self.assertEqual(len(result['recorded_events']),1)
  task=s.execute(OWNER,'new','task_create',{'title':'Task'})['task']
  s.execute(OWNER,'done','task_complete',{'task_id':task['id']})
  completed=s.task(OWNER,task['id'])['completed']
  s.execute(OWNER,'edit','task_update',{'task_id':task['id'],'title':'New title'})
  self.assertEqual(s.task(OWNER,task['id'])['completed'],completed)
  self.assertEqual(len(s.rows("SELECT kind FROM events WHERE owner=? AND kind='task_updated'",(OWNER,))),1)
 def test_invalid_arguments_do_not_reach_storage(self):
  for name,args in [('task_create',{'title':' '}),('task_create',{'title':'X','due_date':'tomorrow'}),('task_complete',{'task_id':'made-up'}),('open_app',{'app':'x','command':'tap'})]:
   with self.assertRaises(ValueError):validate(name,args)
 def test_action_results_are_scoped_expire_and_do_not_overwrite(self):
  s=MemoryStore(':memory:');self.addCleanup(s.close)
  s.queue_action(OWNER,'a','open_app',{'app':'youtube'},time.time()+20)
  with self.assertRaises(ValueError):s.action_result(OTHER,'a',{'status':'launch_requested'})
  first={'status':'launch_requested'};self.assertEqual(s.action_result(OWNER,'a',first),first)
  self.assertEqual(s.action_result(OWNER,'a',{'status':'failed'}),first)
  s.queue_action(OWNER,'old','open_app',{},time.time()-1);self.assertEqual(s.actions(OWNER),[])
  self.assertEqual(s.action_result(OWNER,'old')['status'],'expired')

class AgentTests(unittest.IsolatedAsyncioTestCase):
 async def test_split_tool_json_creates_once_and_followup_survives_new_client(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close);requests=[]
  plan={'user_text':'Remember to finish the presentation tomorrow','reply':'','actions':[{'name':'task_create','arguments':{'title':'Presentation','due_date':'2026-11-21'}}]}
  async def handle(request):
   body=json.loads(request.content);requests.append(body)
   if len(requests)==1:
    args=json.dumps(plan);mid=len(args)//2
    deltas=[{'tool_calls':[{'index':0,'id':'call1','function':{'name':'plan_turn','arguments':args[:mid]}}]}, {'tool_calls':[{'index':0,'function':{'arguments':args[mid:]}}]}]
   elif len(requests)==2:deltas=[{'content':'Saved for tomorrow.'}]
   else:
    self.assertIn('Presentation',body['messages'][0]['content']);self.assertIn('Saved for tomorrow.',body['messages'][0]['content'])
    complete={'user_text':'I finished that','reply':'','actions':[{'name':'task_complete','arguments':{'task_id':store.context(OWNER)['tasks'][0]['id']}}]}
    deltas=[{'tool_calls':[{'index':0,'id':'call2','function':{'name':'plan_turn','arguments':json.dumps(complete)}}]}] if len(requests)==3 else [{'content':'Marked it done.'}]
   return httpx.Response(200,text=''.join('data: '+json.dumps({'choices':[{'delta':d}]})+'\n\n' for d in deltas)+'data: [DONE]\n\n')
  settings=replace(configured(),dashscope_key='fixture',bubble_llm_model='fixture',bubble_llm_url='https://qwen.example/chat/completions')
  def client():
   q=QwenAudio(settings,'en-US',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)));q.use_memory(store,OWNER,'Asia/Kolkata',{});return q
  q=client();self.assertEqual(''.join([x async for x in q.answer(b'01'*3200)]),'Saved for tomorrow.');await q.close()
  q=client();self.assertEqual(''.join([x async for x in q.answer(b'01'*3200)]),'Marked it done.');await q.close()
  self.assertEqual(store.context(OWNER)['tasks'][0]['status'],'done')
 async def test_incomplete_model_stream_never_executes_task(self):
  s=MemoryStore(':memory:');self.addCleanup(s.close)
  async def handle(request):return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':{'tool_calls':[{'index':0,'function':{'name':'plan_turn','arguments':'{"user_text":"save task"'}}]}}]})+'\n\n')
  settings=replace(configured(),dashscope_key='fixture',bubble_llm_model='fixture',bubble_llm_url='https://qwen.example/chat/completions')
  q=QwenAudio(settings,'en-US',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)));q.use_memory(s,OWNER,'Asia/Kolkata',{})
  try:
   from anyvoice.providers import ProviderError
   with self.assertRaises(ProviderError):[x async for x in q.answer(b'01'*3200)]
   self.assertFalse(s.context(OWNER)['tasks'])
  finally:await q.close()
 async def test_tool_executor_validates_apps_and_deduplicates_same_turn(self):
  s=MemoryStore(':memory:');self.addCleanup(s.close);tools=ToolExecutor(s,OWNER,'Asia/Kolkata',{'youtube':'YouTube'})
  first=await tools.execute('a'*32+':0','task_create',{'title':'Demo'})
  second=await tools.execute('a'*32+':1','task_create',{'title':'Demo'})
  self.assertEqual(first,second)
  result=await tools.execute('b'*32+':0','open_app',{'app':'arbitrary-package'})
  self.assertEqual(result['status'],'unavailable')
  async def phone():
   while not s.actions(OWNER):await asyncio.sleep(.01)
   action=s.actions(OWNER)[0];s.action_result(OWNER,action['id'],{'status':'launch_requested'})
  phone_task=asyncio.create_task(phone())
  result=await tools.execute('c'*32+':0','search_youtube',{'query':'Telugu music'});await phone_task
  self.assertEqual(result['status'],'launch_requested')

class MemoryApiTests(unittest.IsolatedAsyncioTestCase):
 async def test_identity_auth_tools_and_cross_device_isolation(self):
  app=create_app(configured(),native_check=lambda:True);headers={'Authorization':'Bearer '+configured().access_token,'X-AnyVoice-Device':OWNER,'X-AnyVoice-Device-Key':'a'*64}
  async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://test') as c:
   self.assertEqual((await c.get('/v1/me/overview')).status_code,401)
   self.assertEqual((await c.get('/v1/me/overview',headers={'Authorization':headers['Authorization']})).status_code,401)
   r=await c.post('/v1/me/tools',headers=headers,json={'request_id':'create-task-1','name':'task_create','arguments':{'title':'Test'}})
   self.assertEqual(r.status_code,200)
   self.assertEqual(len((await c.get('/v1/me/overview',headers=headers)).json()['tasks']),1)
   other={**headers,'X-AnyVoice-Device':OTHER}
   self.assertFalse((await c.get('/v1/me/overview',headers=other)).json()['tasks'])
   from anyvoice.session import Session
   app.state.sessions['scoped-session']=Session(owner=OWNER)
   self.assertEqual((await c.get('/v1/sessions/scoped-session',headers=other)).status_code,404)
   self.assertEqual((await c.get('/v1/sessions/scoped-session',headers=headers)).status_code,200)
   self.assertEqual((await c.get('/v1/me/overview',headers={**headers,'X-AnyVoice-Device-Key':'b'*64})).status_code,401)
   self.assertEqual((await c.post('/v1/me/tools',headers=headers,json={'request_id':'bad-tool-1','name':'shell','arguments':{}})).status_code,422)
