import asyncio
import base64
import io
import json
import struct
import unittest
import wave
from dataclasses import replace
from types import SimpleNamespace
import httpx
from anyvoice.bubble import BubblePipeline,CommandVad,BubbleServices
from anyvoice.qwen_audio import QwenAudio
from anyvoice.session import Session
from test_pipeline import configured,FakeCompletion

class WakeTests(unittest.TestCase):
 def test_vad_preserves_bounded_command_and_rejects_clicks_and_silence(self):
  vad=CommandVad();speech=b"\x10\x27"*320;quiet=bytes(640)
  for _ in range(50):self.assertIsNone(vad.feed(quiet))
  for _ in range(20):self.assertIsNone(vad.feed(speech))
  result=None
  for _ in range(34):result=vad.feed(quiet) or result
  self.assertTrue(result);self.assertIn(speech,result);self.assertLessEqual(len(result),32000*2)
  vad.reset();vad.feed(speech)
  self.assertFalse(any(vad.feed(quiet) for _ in range(40)))

 def test_quiet_phone_speech_is_detected_above_room_noise(self):
  vad=CommandVad();noise=struct.pack("<h",17)*320;voice=struct.pack("<h",100)*320
  for _ in range(100):vad.observe_background(noise)
  for _ in range(20):self.assertIsNone(vad.feed(voice))
  command=None
  for _ in range(34):command=vad.feed(noise) or command
  self.assertTrue(command)

class BubbleTests(unittest.IsolatedAsyncioTestCase):
 def pipeline(self):
  s=Session(mode="bubble",source_language="auto",source_languages=("en-IN","te-IN"),target_language="te-IN",voice_id="fixture")
  services=SimpleNamespace();qwen=SimpleNamespace(close=AsyncClose(),configured=lambda:True)
  p=BubblePipeline(configured(),s,transport=SimpleNamespace(),services=services,completion=FakeCompletion(),qwen=qwen)
  self.addCleanup(s.audio.close);return p,s
 def caption(self,p,text,identity=1):
  p.receive_caption(json.dumps({"transcript":{"uid":1001,"sentenceId":identity,"language":"en-IN","text":text,"isFinal":True}}).encode())
 async def test_manual_session_listens_immediately_and_ignores_wake_captions(self):
  p,s=self.pipeline();self.assertEqual(s.bubble_state,"listening")
  self.caption(p,"Wake up Bubble");self.assertTrue(p.response_chunks.empty())
  self.assertEqual(s.wake_sequence,0)
 async def test_rearm_waits_for_phone_playback_ack_and_retains_active_epoch(self):
  p,s=self.pipeline();s.state="ready";s.bubble_state="speaking"
  s.audio.append(b"01"*800,1,1,1);p.response_end=1;p.generation_done=True
  task=asyncio.create_task(p.monitor())
  try:
   await asyncio.sleep(.15);self.assertEqual(s.bubble_state,"speaking")
   s.audio.acknowledge(1);await asyncio.sleep(.15)
   self.assertEqual(s.bubble_state,"listening");self.assertTrue(s.public()["input_allowed"]);self.assertEqual(s.route_epoch,1)
  finally:task.cancel();await asyncio.gather(task,return_exceptions=True)
 async def test_manual_audio_does_not_start_stt(self):
  calls=[]
  async def handle(request):calls.append(request);return httpx.Response(200,json={})
  client=httpx.AsyncClient(transport=httpx.MockTransport(handle))
  service=BubbleServices(configured(),Session(),lambda uid:"fixture",client)
  await service.open_stt();await service.close()
  self.assertEqual(calls,[])
 async def test_qwen_receives_actual_wav_text_only_and_selected_language(self):
  requests=[]
  async def handle(request):
   requests.append(json.loads(request.content))
   events=[{"choices":[{"delta":{"reasoning_content":"never speak this"}}]},
    {"choices":[{"delta":{"content":"తెలుగులో సమాధానం"}}]}, {"choices":[],"usage":{}}]
   return httpx.Response(200,text="".join("data: "+json.dumps(item)+"\n\n" for item in events)+"data: [DONE]\n\n")
  settings=replace(configured(),dashscope_key="fixture-key",bubble_llm_model="fixture-model",bubble_llm_url="https://qwen.example/chat/completions")
  client=QwenAudio(settings,"te-IN",client=httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  try:
   output="".join([part async for part in client.answer(b"01"*3200)])
   self.assertEqual(output,"తెలుగులో సమాధానం");body=requests[0]
   self.assertEqual(body["modalities"],["text"]);self.assertFalse(body["enable_thinking"])
   self.assertIn("Telugu",body["messages"][0]["content"])
   uri=body["messages"][-1]["content"][0]["input_audio"]["data"]
   with wave.open(io.BytesIO(base64.b64decode(uri.split(",")[1])),"rb") as wav:self.assertEqual(wav.getframerate(),16000);self.assertEqual(wav.getnframes(),3200)
   self.assertEqual(len(client.history),2)
  finally:await client.close()

class AsyncClose:
 async def __call__(self):pass

class BubbleApiIsolationTests(unittest.IsolatedAsyncioTestCase):
 async def test_optional_qwen_config_does_not_disable_translation_and_modes_are_exclusive(self):
  from unittest.mock import patch,AsyncMock
  from anyvoice.api import create_app
  settings=configured()
  class FakePipeline:
   def __init__(self,settings,session):self.session=session
   async def run(self):
    self.session.state="ready";self.session.ready.set()
    try:await self.session.stopped.wait()
    finally:self.session.state="stopped";self.session.stopped.set()
  app=create_app(settings,pipeline_factory=FakePipeline,bubble_factory=FakePipeline,native_check=lambda:True)
  headers={"Authorization":"Bearer "+settings.access_token}
  async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test") as client:
   caps=(await client.get("/v1/capabilities",headers=headers)).json()
   self.assertTrue(caps["configured"]);self.assertFalse(caps["bubble_configured"])
   self.assertEqual((await client.post("/v1/sessions",headers=headers,json={"mode":"bubble","voice_id":"fixture"})).status_code,503)
   self.assertEqual((await client.get("/v1/bubble/diagnostics")).status_code,401)
  settings=replace(settings,dashscope_key="fixture",bubble_llm_url="https://qwen.example/chat/completions",bubble_llm_model="fixture")
  app=create_app(settings,pipeline_factory=FakePipeline,bubble_factory=FakePipeline,native_check=lambda:True)
  async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test") as client:
   with patch("anyvoice.voices.VoiceCatalog.validate",new=AsyncMock()):
    bubble=await client.post("/v1/sessions",headers=headers,json={"mode":"bubble","voice_id":"fixture","target_language":"ja-JP"})
    self.assertEqual(bubble.status_code,200);data=bubble.json();self.assertEqual(data["source_languages"],["en-IN"])
    self.assertEqual((await client.post("/v1/sessions",headers=headers,json={"voice_id":"fixture"})).status_code,409)
    self.assertEqual((await client.get("/v1/voice-preview",headers=headers,params={"voice_id":"fixture","language":"ja-JP"})).status_code,409)
    await client.post("/v1/sessions/"+data["id"]+"/stop",headers=headers)
    translated=await client.post("/v1/sessions",headers=headers,json={"voice_id":"fixture"})
    self.assertEqual(translated.status_code,200);self.assertEqual(translated.json()["mode"],"translation")
    await client.post("/v1/sessions/"+translated.json()["id"]+"/stop",headers=headers)
