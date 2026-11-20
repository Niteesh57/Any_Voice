import asyncio
from dataclasses import replace
import io
import json
import time
import unittest
import wave
from types import SimpleNamespace
from unittest.mock import AsyncMock,patch
import httpx
from anyvoice.voices import VoiceCatalog,agora_locale
from anyvoice.api import create_app
from anyvoice.session import Session,Pipeline
from anyvoice.providers import AgoraServices
from anyvoice.murf_stream import MurfStream
from test_murf_stream import Socket
from test_pipeline import configured

class VoiceTests(unittest.IsolatedAsyncioTestCase):
 async def test_catalog_uses_declared_native_and_supported_locales_without_assuming_multilingual(self):
  raw=[{"voiceId":"dynamic-a","displayName":"New voice (F)","locale":"te-IN","gender":"Female",
        "supportedLocales":{"ja-JP":{"detail":"Japanese"},"en-UK":{"detail":"English UK"},"unknown-X":{"detail":"Unknown"}}},
       {"voiceId":"dynamic-b","displayName":"Another","locale":"hi-IN","supportedLocales":{"te-IN":{"detail":"Telugu"}}},
       {"voiceId":"no-declaration","displayName":"Hidden","locale":"made-up","supportedLocales":{}}]
  requests=[]
  async def respond(request):requests.append(request);return httpx.Response(200,json=raw)
  client=httpx.AsyncClient(transport=httpx.MockTransport(respond))
  catalog=VoiceCatalog(configured())
  with patch("anyvoice.voices.httpx.AsyncClient",return_value=client):
   rows=await catalog.get();again=await catalog.get()
  self.assertEqual(len(requests),1);self.assertIs(rows,again)
  self.assertEqual(rows[0]["locales"],["ja-JP","en-UK","te-IN"])
  self.assertNotIn("hi-IN",rows[0]["locales"])
  self.assertEqual([r["id"] for r in rows],["dynamic-a","dynamic-b"])
  self.assertIn("model=falcon-2.1",str(requests[0].url))
  self.assertEqual({r["id"] for r in catalog.languages},{"te-IN","ja-JP","en-UK","hi-IN"})

 async def test_assignment_refreshes_catalog_and_rejects_wrong_voice_language(self):
  catalog=VoiceCatalog(configured());catalog.get=AsyncMock(return_value=[{"id":"only-japanese","locales":["ja-JP"]}])
  with self.assertRaises(ValueError):await catalog.validate("only-japanese","te-IN")
  with self.assertRaises(ValueError):await catalog.validate("deleted-voice","ja-JP")
  self.assertEqual((await catalog.validate("only-japanese","ja-JP"))["id"],"only-japanese")
  catalog.get.assert_awaited_with(force=True)

 async def test_preview_uses_chosen_voice_locale_and_cleans_up(self):
  settings=configured();catalog=VoiceCatalog(settings);catalog.validate=AsyncMock();observed=[]
  class Stream:
   def __init__(self,settings):observed.append((settings.voice,settings.target_locale));self.closed=False
   async def synthesize(self,text,pcm,speech):pcm(b"01"*1600)
   async def close(self):observed.append("closed")
  with patch("anyvoice.voices.MurfStream",Stream):
   data=await catalog.preview("selected-id","ja-JP")
   self.assertEqual(data,await catalog.preview("selected-id","ja-JP"))
  self.assertEqual(observed,[("selected-id","ja-JP"),"closed"])
  with wave.open(io.BytesIO(data),"rb") as wav:self.assertEqual((wav.getframerate(),wav.getnchannels(),wav.getnframes()),(16000,1,1600))

 async def test_selected_locale_reaches_murf_and_agora_and_same_language_passes_through(self):
  s=Session(source_language="auto",target_language="ja-JP",voice_locale="ja-JP",voice_id="selected-id",source_languages=("en-IN","ja-JP"))
  p=Pipeline(configured(),s,transport=SimpleNamespace());self.addCleanup(s.audio.close)
  self.assertEqual(p.settings.voice,"selected-id");self.assertEqual(p.settings.target_locale,"ja-JP")
  p.receive_caption(json.dumps({"transcript":{"uid":1001,"language":"ja-JP","sentenceId":1,"offset":1,"text":"test","isFinal":True}}).encode())
  self.assertEqual(s.route_mode,"original")
  p.receive_caption(json.dumps({"transcript":{"uid":1001,"language":"en-IN","sentenceId":2,"offset":2,"text":"test","isFinal":True}}).encode())
  p.receive_caption(json.dumps({"translation":{"uid":1001,"sentenceId":2,"results":[{"language":"ja-JP","texts":["translated"],"isFinal":True}]}}).encode())
  p.route_votes=(p.route_votes[0],p.route_votes[1],p.route_votes[2]-1)
  p.receive_caption(json.dumps({"transcript":{"uid":1001,"language":"en-IN","sentenceId":3,"offset":3,"text":"more speech","isFinal":True}}).encode())
  self.assertEqual(p.phrases.get_nowait()[0],"translated")
  calls=[]
  async def respond(request):calls.append(json.loads(request.content));return httpx.Response(200,json={"agent_id":"fixture"})
  services=AgoraServices(p.settings,s,lambda uid:"token",httpx.AsyncClient(transport=httpx.MockTransport(respond)))
  await services.open_stt();await services.open_voice();await services.close()
  self.assertEqual(calls[0]["translateConfig"]["languages"],[{"source":"en-IN","target":["ja-JP"]}])
  self.assertEqual(calls[1]["properties"]["tts"]["params"]["voiceId"],"selected-id")
  self.assertEqual(calls[1]["properties"]["tts"]["params"]["locale"],"ja-JP")

 async def test_api_rejects_unselected_and_incompatible_voices_before_startup(self):
  app=create_app(configured(),native_check=lambda:True)
  headers={"Authorization":"Bearer "+configured().access_token}
  async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url="http://test") as client:
   self.assertEqual((await client.get("/v1/voices")).status_code,401)
   self.assertEqual((await client.post("/v1/sessions",headers=headers,json={})).status_code,422)
   with patch("anyvoice.voices.VoiceCatalog.validate",new=AsyncMock(side_effect=ValueError("unsupported pair"))):
    response=await client.post("/v1/sessions",headers=headers,json={"voice_id":"bad-pair","target_language":"ja-JP"})
    self.assertEqual(response.status_code,422)
   self.assertEqual((await client.get("/v1/voice-preview",headers=headers,params={"voice_id":"x","language":"unknown"})).status_code,422)

 def test_locale_compatibility_aliases_and_unsupported_mixed_language(self):
  self.assertEqual(agora_locale("en-UK"),"en-GB");self.assertEqual(agora_locale("ar-XA"),"ar-SA")
  self.assertIsNone(agora_locale("hi-LATN"));self.assertIsNone(agora_locale("made-up"))

class RegionalLanguageTests(unittest.IsolatedAsyncioTestCase):
 async def test_same_language_across_regional_codes_keeps_original_voice(self):
  session=Session(source_language="auto",target_language="en-GB",voice_locale="en-UK",source_languages=("en-IN","en-GB"))
  pipeline=Pipeline(configured(),session,transport=SimpleNamespace())
  self.addCleanup(session.audio.close)
  pipeline.receive_caption(json.dumps({"transcript":{"uid":1001,"language":"en-IN","sentenceId":1,"offset":1,"text":"test","isFinal":True}}).encode())
  self.assertEqual(session.route_mode,"original")
