import asyncio
import audioop
import base64
import json
import unittest
from dataclasses import replace
from types import SimpleNamespace
from anyvoice.murf_stream import MurfStream
from anyvoice.providers import ProviderError
from anyvoice.session import Pipeline,Session
from test_pipeline import configured,caption,FakeCompletion

class Socket:
 def __init__(self,events):self.events=list(events);self.sent=[];self.closed=False
 async def send(self,message):self.sent.append(json.loads(message))
 async def recv(self):return json.dumps(self.events.pop(0))
 async def close(self):self.closed=True

class MurfStreamTests(unittest.IsolatedAsyncioTestCase):
 async def test_auth_pcm_resampling_and_order_across_two_contexts(self):
  raw=(b'\x10\x27\xf0\xd8')*1200
  def audio(data):return {'audio':base64.b64encode(data).decode()}
  ws=Socket([audio(raw[:601]),audio(raw[601:]),{'final':True},audio(raw),{'final':True}]);connections=[]
  async def connect(url,**kwargs):connections.append((url,kwargs));return ws
  voice=MurfStream(configured(),connect=connect);received=[];speech=[]
  await voice.open()
  try:
   await voice.synthesize('one',received.append,lambda:speech.append(True))
   first=b''.join(received);expected,_=audioop.ratecv(raw,2,1,24000,16000,None)
   self.assertEqual(first,expected)
   received.clear();await voice.synthesize('two',received.append,lambda:speech.append(True))
   self.assertEqual(b''.join(received),expected)
   self.assertEqual(len(connections),1);self.assertNotIn('api-key',connections[0][0])
   self.assertEqual(connections[0][1]['additional_headers']['api-key'],configured().murf_key)
   self.assertEqual([e['text'] for e in ws.sent if 'text' in e],['one','two'])
   ids=[e['context_id'] for e in ws.sent if 'text' in e];self.assertNotEqual(ids[0],ids[1])
   self.assertEqual(len(speech),2)
  finally:await voice.close()
  self.assertTrue(ws.closed)
 async def test_partial_audio_failure_never_replays_phrase(self):
  ws=Socket([{'audio':base64.b64encode(b'\x10\x27'*2400).decode()},{'error':'failure','error_code':'ERROR'}])
  voice=MurfStream(configured());voice.ws=ws;received=[]
  try:
   with self.assertRaises(ProviderError):await voice.synthesize('one',received.append,lambda:None)
   self.assertTrue(received);self.assertEqual(len([e for e in ws.sent if 'text' in e]),1)
  finally:await voice.close()
 async def test_empty_and_wrong_context_audio_fail(self):
  for events in [[{'final':True}],[{'context_id':'wrong','final':True}]]:
   voice=MurfStream(configured());voice.ws=Socket(events)
   try:
    with self.assertRaises(ProviderError):await voice.synthesize('one',lambda pcm:None,lambda:None)
   finally:await voice.close()
 async def test_pipeline_direct_keeps_agora_stt_and_prepares_ahead(self):
  settings=replace(configured(),voice_provider='murf-direct')
  class Transport:
   async def open(self,*callbacks):pass
   async def close(self):self.closed=True
  class Services:
   async def open_stt(self):self.stt=True
   async def open(self):raise AssertionError('Direct mode must not start the slow voice agent')
   async def close(self):self.closed=True;return []
  class Direct:
   def __init__(self):self.spoken=[]
   async def open(self):pass
   async def synthesize(self,text,on_pcm,on_speech):
    self.spoken.append(text);on_speech();on_pcm(b'\x10\x27'*8000)
   async def close(self):self.closed=True
  services=Services();direct=Direct();direct_b=Direct();rtc=Transport();session=Session()
  pipeline=Pipeline(settings,session,transport=rtc,services=services,completion=FakeCompletion(),direct=direct,direct_b=direct_b)
  task=asyncio.create_task(pipeline.run())
  try:
   await asyncio.wait_for(session.ready.wait(),1)
   self.assertTrue(services.stt);self.assertEqual(session.voice_provider,'murf-direct')
   pipeline.receive_caption(json.dumps(caption('one',identity=1)).encode())
   pipeline.receive_caption(json.dumps(caption('one',identity=1)).encode())
   await asyncio.sleep(.01)
   pipeline.receive_caption(json.dumps(caption('two',identity=2)).encode())
   await asyncio.sleep(.01)
   self.assertEqual(sorted(direct.spoken+direct_b.spoken),['one','two'])
   self.assertEqual(sum(r['duration_ms'] for r in session.audio.clips.values()),1000)
   self.assertEqual(session.audio.acknowledged,0) # Both generated before any playback acknowledgement.
  finally:session.stopped.set();await asyncio.wait_for(task,1)
  self.assertTrue(direct.closed and direct_b.closed and rtc.closed and services.closed)

if __name__=='__main__':unittest.main()
