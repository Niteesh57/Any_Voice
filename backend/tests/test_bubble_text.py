import asyncio
import json
import random
import struct
import time
import unittest
from dataclasses import replace
from types import SimpleNamespace
import httpx
from anyvoice.bubble import BubblePipeline,BubbleServices
from anyvoice.memory import MemoryStore
from anyvoice.qwen_audio import QwenAudio
from anyvoice.tool_receipts import receipt
from anyvoice.session import Session
from test_pipeline import configured,FakeCompletion

class TextBubbleTests(unittest.IsolatedAsyncioTestCase):
 def test_receipt_never_claims_success_for_failed_or_unconfirmed_actions(self):
  for status in ('expired','failed','unavailable','error','needs_clarification'):
   self.assertIsNone(receipt([{'tool':'search_youtube','result':{'status':status}}],'te-IN'))
  self.assertIsNone(receipt([{'tool':'task_complete','result':{'status':'updated','task':{'status':'open'}}}],'te-IN'))
  self.assertIsNone(receipt([{'tool':'task_create','result':{'status':'saved'}}],'fr-FR'))
 async def test_mixed_script_reply_is_repaired_once_without_repeating_tools(self):
  requests=[]
  async def handle(request):
   body=json.loads(request.content);requests.append(body);self.assertNotIn('tools',body)
   delta={'content':'యూట్యూబ్ శోధన కోరాను.'}
   return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':delta}]})+'\n\ndata: [DONE]\n\n')
  q=QwenAudio(replace(configured(),bubble_llm_provider='nvidia',nvidia_key='fixture',bubble_llm_model='nemotron',bubble_llm_url='https://fixture.invalid'), 'te-IN',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  store=MemoryStore(':memory:');q.use_memory(store,'owner','Asia/Kolkata',{})
  try:
   self.assertFalse(q.agent.matches_script('లঞ্চ్ రిక్వెస్ట్'))
   self.assertFalse(q.agent.matches_script('The search was requested'))
   self.assertTrue(q.agent.matches_script('YouTube శోధన కోరాను.'))
   result=await q.agent.spoken('లঞ্চ్ రిక్వెస్ట్','Telugu');self.assertEqual(result,'యూట్యూబ్ శోధన కోరాను.')
   self.assertEqual(len(requests),1);self.assertFalse(store.context('owner')['memories'])
  finally:await q.close();store.close()
 def pipeline(self):
  class Model:
   def __init__(self):self.requests=[]
   async def answer_text(self,text):self.requests.append(text);yield 'తెలుగు సమాధానం'
  model=Model();s=Session(mode='unified',input_mode='bubble',desired_input='bubble')
  p=BubblePipeline(replace(configured(),bubble_llm_provider='nvidia'),s,transport=SimpleNamespace(),services=SimpleNamespace(),completion=FakeCompletion(),qwen=model)
  self.addCleanup(s.audio.close)
  return p,s,model
 def caption(self,p,text,identity='one',uid=1002,final=True,offset=None):
  p.receive_caption(json.dumps({'transcript':{'uid':uid,'sentenceId':identity,'text':text,'isFinal':final,'offset':offset}}).encode())
 def voice(self,p):
  p.vad.detector=SimpleNamespace(is_speech=lambda *_:True)
  for _ in range(15):p.receive_input_pcm(struct.pack('<h',1000)*320)
 def end(self,p):
  for _ in range(35):p.receive_input_pcm(bytes(640))
 async def test_final_text_is_grouped_once_after_speech_and_duplicates_do_not_replay(self):
  p,s,m=self.pipeline();self.voice(p)
  self.caption(p,'partial',final=False);self.caption(p,'wrong source',uid=1001)
  self.caption(p,'మొదటి భాగం');self.caption(p,'మొదటి భాగం')
  self.caption(p,'రెండవ భాగం','two');self.end(p)
  await asyncio.wait_for(p.answer_task,2)
  self.assertEqual(m.requests,['మొదటి భాగం రెండవ భాగం'])
  self.assertEqual(s.bubble_state,'thinking')
  self.caption(p,'మొదటి భాగం');self.assertFalse(p.command_captions)
  s.bubble_state='listening';self.voice(p);self.caption(p,'మొదటి భాగం')
  self.assertFalse(p.command_captions)
  self.caption(p,'మొదటి భాగం','new-id');self.end(p)
  await asyncio.wait_for(p.answer_task,2)
  self.assertEqual(len(m.requests),2)
 async def test_silence_noise_labels_echo_stale_and_inactive_modes_never_call_model(self):
  p,s,m=self.pipeline()
  for _ in range(100):p.receive_input_pcm(bytes(640))
  self.caption(p,'hallucinated speech');self.assertFalse(p.command_captions)
  rng=random.Random(12)
  for _ in range(100):p.receive_input_pcm(struct.pack('<320h',*[rng.randint(-30,30) for _ in range(320)]))
  self.voice(p);self.caption(p,'[Music]');self.caption(p,'[inaudible]')
  self.caption(p,'old turn','old',offset=p.caption_floor_ms-1000)
  self.assertFalse(p.command_captions)
  s.bubble_state='speaking';self.caption(p,'echo','echo');self.end(p)
  self.assertIsNone(p.answer_task);self.assertFalse(m.requests)
  for mode in ('media','bidir'):
   s.bubble_state='listening';s.input_mode=mode;s.desired_input=mode
   self.caption(p,'wrong mode',mode)
  self.assertFalse(p.command_captions);self.assertFalse(m.requests)
 async def test_click_without_minimum_speech_discards_captions(self):
  p,s,m=self.pipeline();p.vad.detector=SimpleNamespace(is_speech=lambda *_:True)
  p.receive_input_pcm(struct.pack('<h',1000)*320);self.caption(p,'click hallucination');self.end(p)
  self.assertFalse(p.command_captions);self.assertFalse(m.requests);self.assertIsNone(p.answer_task)
 async def test_late_final_is_accepted_but_switch_cancels_pending_command(self):
  p,s,m=self.pipeline();self.voice(p);self.end(p)
  await asyncio.sleep(.05);self.caption(p,'చివరి సమాధానం')
  await asyncio.wait_for(p.answer_task,2);self.assertEqual(len(m.requests),1)
  s.bubble_state='listening';self.voice(p);self.end(p);s.desired_input='media'
  await asyncio.wait_for(p.answer_task,1);self.assertEqual(len(m.requests),1)
 async def test_separate_persistent_stt_subscribes_only_to_bubble_microphone(self):
  requests=[]
  async def handle(request):
   requests.append((request.method,str(request.url),json.loads(request.content)))
   return httpx.Response(200,json={'agent_id':'bubble-agent'})
  settings=replace(configured(),bubble_llm_provider='nvidia')
  service=BubbleServices(settings,Session(mode='unified',target_language='te-IN'),lambda _: 'fixture',httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  await service.open_stt();await service.open_stt();await service.close()
  self.assertEqual(len(requests),2)
  body=requests[0][2];self.assertEqual(body['rtcConfig']['subscribeAudioUids'],['1002'])
  self.assertEqual(body['rtcConfig']['pubBotUid'],'6001');self.assertEqual(body['languages'],['te-IN','en-IN'])
  self.assertNotIn('translateConfig',body);self.assertIn('/agents/bubble-agent/leave',requests[1][1])
 async def test_native_text_tool_receipt_uses_nvidia_key_and_device_scoped_memory(self):
  requests=[]
  async def handle(request):
   body=json.loads(request.content);requests.append(body)
   self.assertEqual(request.headers['authorization'],'Bearer nvidia-fixture')
   self.assertIsInstance(body['messages'][1]['content'],str)
   if len(requests)==1:
    delta={'tool_calls':[{'index':0,'id':'memory-call','type':'function','function':{'name':'memory_save','arguments':json.dumps({'key':'style','text':'Short answers'})}}]}
   else:
    self.assertNotIn('tools',body);delta={'content':'గుర్తుంచుకున్నాను'}
   return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':delta}]})+'\n\ndata: [DONE]\n\n')
  settings=replace(configured(),bubble_llm_provider='nvidia',nvidia_key='nvidia-fixture',dashscope_key='wrong',bubble_llm_model='nvidia/nemotron-3-nano-omni-30b-a3b-reasoning',bubble_llm_url='https://integrate.api.nvidia.com/v1/chat/completions')
  store=MemoryStore(':memory:');q=QwenAudio(settings,'te-IN',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  try:
   q.use_memory(store,'owner-one','Asia/Kolkata',{})
   reply=''.join([x async for x in q.answer_text('చిన్న సమాధానాలు ఇష్టమని గుర్తుంచుకో')])
   self.assertEqual(reply,'గుర్తుంచుకున్నాను.');self.assertEqual(len(requests),1)
   self.assertTrue(store.context('owner-one')['memories']);self.assertFalse(store.context('owner-two')['memories'])
   self.assertEqual(requests[0]['reasoning_budget'],512)
  finally:await q.close();store.close()
