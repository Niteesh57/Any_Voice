import base64
import io
import json
import unittest
import wave
from dataclasses import replace
import httpx
from anyvoice.config import Settings
from anyvoice.memory import MemoryStore
from anyvoice.qwen_audio import QwenAudio
from test_pipeline import configured

class GeminiTests(unittest.IsolatedAsyncioTestCase):
 def settings(self):
  return replace(configured(),bubble_llm_provider='gemini',gemini_key='gemini-fixture',dashscope_key='wrong-provider-key',bubble_llm_model='gemini-3.1-flash-lite',bubble_llm_url='https://generativelanguage.googleapis.com/v1beta/openai/chat/completions')
 async def test_direct_audio_wav_is_bare_base64_and_uses_gemini_key(self):
  async def handle(request):
   self.assertEqual(request.headers['authorization'],'Bearer gemini-fixture')
   body=json.loads(request.content)
   self.assertNotIn('enable_thinking',body);self.assertNotIn('modalities',body)
   self.assertEqual(body['reasoning_effort'],'minimal')
   self.assertIn('Telugu',body['messages'][0]['content'])
   data=body['messages'][-1]['content'][0]['input_audio']['data']
   self.assertFalse(data.startswith('data:'))
   with wave.open(io.BytesIO(base64.b64decode(data)),'rb') as w:
    self.assertEqual(w.getframerate(),16000);self.assertEqual(w.getnframes(),3200)
   return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':{'content':'తెలుగు సమాధానం'}}]})+'\n\ndata: [DONE]\n\n')
  q=QwenAudio(self.settings(),'te-IN',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  try:self.assertEqual(''.join([x async for x in q.answer(b'01'*3200)]),'తెలుగు సమాధానం')
  finally:await q.close()
 async def test_tool_signature_actual_id_and_audio_survive_followup_and_owner_isolation(self):
  requests=[];signature={'google':{'thought_signature':'opaque-test-signature'}}
  async def handle(request):
   body=json.loads(request.content);requests.append(body)
   if len(requests)==1:
    variants=body['tools'][0]['function']['parameters']['properties']['actions']['items']['anyOf']
    memory=next(v for v in variants if v['properties']['name']['enum']==['memory_save'])
    self.assertEqual(set(memory['properties']['arguments']['required']),{'key','text'})
    self.assertNotIn('$ref',json.dumps(variants))
    self.assertFalse(any(v['properties']['name']['enum']==['open_app'] for v in variants))
    plan={'user_text':'Remember my favorite fruit is mango','reply':'','actions':[{'name':'memory_save','arguments':{'key':'fruit','text':'Favorite fruit is mango'}}]}
    delta={'tool_calls':[{'index':0,'id':'actual-google-id','type':'function','extra_content':signature,'function':{'name':'plan_turn','arguments':json.dumps(plan)}}]}
   else:
    self.assertEqual(body['messages'][2]['tool_calls'][0]['extra_content'],signature)
    self.assertEqual(body['messages'][2]['tool_calls'][0]['id'],'actual-google-id')
    self.assertEqual(body['messages'][3]['tool_call_id'],'actual-google-id')
    self.assertIn('input_audio',body['messages'][1]['content'][0])
    delta={'content':'గుర్తుంచుకున్నాను.'}
   return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':delta}]})+'\n\ndata: [DONE]\n\n')
  store=MemoryStore(':memory:');q=QwenAudio(self.settings(),'te-IN',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  try:
   q.use_memory(store,'device-one','Asia/Kolkata',{})
   self.assertEqual(''.join([x async for x in q.answer(b'01'*3200)]),'గుర్తుంచుకున్నాను.')
   self.assertTrue(store.context('device-one')['memories']);self.assertFalse(store.context('device-two')['memories'])
   self.assertEqual(len(requests),2)
  finally:await q.close();store.close()
 async def test_partial_tool_stream_never_saves_memory(self):
  async def handle(request):
   plan={'user_text':'Remember mango','reply':'','actions':[{'name':'memory_save','arguments':{'key':'fruit','text':'mango'}}]}
   delta={'tool_calls':[{'index':0,'id':'incomplete','type':'function','function':{'name':'plan_turn','arguments':json.dumps(plan)}}]}
   return httpx.Response(200,text='data: '+json.dumps({'choices':[{'delta':delta}]})+'\n\n')
  store=MemoryStore(':memory:');q=QwenAudio(self.settings(),'te-IN',client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  try:
   q.use_memory(store,'device-one','Asia/Kolkata',{})
   with self.assertRaisesRegex(Exception,'before the turn finished'):[x async for x in q.answer(b'01'*3200)]
   self.assertFalse(store.context('device-one')['memories'])
  finally:await q.close();store.close()
