import asyncio
from dataclasses import replace
import gzip
import json
import unittest

import httpx

from anyvoice.api import create_app
from anyvoice.captions import FinalizedCaptions, decode_message, speech_chunks
from anyvoice.config import Settings
from anyvoice.providers import AgoraServices
from anyvoice.session import Pipeline, Session, token_for


def configured():
    return Settings(access_token="local-test-access-token-12345", agora_app_id="a" * 32,
        agora_certificate="b" * 32, agora_customer_id="customer", agora_customer_secret="secret",
        murf_key="murf-test", public_url="https://voice.example")


def caption(text="నమస్కారం", final=True, identity=123, uid=1001):
    return {"translation": {"uid": uid, "sentenceId": identity, "textTs": 999,
        "results": [{"language": "te-IN", "texts": [text], "isFinal": final}]}}


class CaptionTests(unittest.TestCase):
    def test_agent_token_has_rtc_and_rtm_privileges(self):
        from anyvoice.vendor.agora_tokens.AccessToken2 import AccessToken, ServiceRtc, ServiceRtm
        settings, session = configured(), Session()
        agent = AccessToken()
        self.assertTrue(agent.from_string(token_for(settings, session, session.worker_uid)))
        self.assertTrue(agent.verify_signature(settings.agora_certificate))
        self.assertEqual(len(agent.get_services(ServiceRtc.kServiceType)), 1)
        self.assertEqual(len(agent.get_services(ServiceRtm.kServiceType)), 1)
        phone = AccessToken()
        self.assertTrue(phone.from_string(token_for(settings, session, session.source_uid)))
        self.assertEqual(len(phone.get_services(ServiceRtm.kServiceType)), 0)

    def test_final_only_and_source_uid(self):
        self.assertEqual(list(decode_message(json.dumps(caption(final=False)).encode(), 1001)), [])
        self.assertEqual(list(decode_message(json.dumps(caption(uid=2001)).encode(), 1001)), [])
        data = gzip.compress(json.dumps(caption()).encode())
        self.assertEqual(list(decode_message(data, 1001))[0]["text"], "నమస్కారం")

    def test_mixed_final_and_partial(self):
        data = caption()
        data["translation"]["results"].append({"language": "te-IN", "texts": ["మారవచ్చు"], "isFinal": False})
        self.assertEqual(list(decode_message(json.dumps(data).encode(), 1001))[0]["text"], "నమస్కారం")

    def test_empty_and_wrong_language(self):
        data = caption()
        data["translation"]["results"] = []
        self.assertEqual(list(decode_message(json.dumps(data).encode(), 1001)), [])
        data = caption(); data["translation"]["results"][0]["language"] = "hi-IN"
        self.assertEqual(list(decode_message(json.dumps(data).encode(), 1001)), [])

    def test_dedup_preserves_repeated_speech_and_prefix(self):
        finals = FinalizedCaptions()
        self.assertEqual(finals.accept("1", "hello"), "hello")
        self.assertEqual(finals.accept("1", "hello"), "")
        self.assertEqual(finals.accept("1", "hello world"), " world")
        self.assertEqual(finals.accept("2", "hello"), "hello")
        with self.assertRaises(ValueError): finals.accept("1", "changed")

    def test_utf8_chunks_preserve_text(self):
        for text in ["తెలుగులో మాట్లాడండి. " * 100, "అ" * 1000, " " + "a" * 510 + "😀", "hello", ""]:
            chunks = list(speech_chunks(text))
            self.assertEqual("".join(chunks), text)
            self.assertTrue(all(0 < len(chunk.encode()) <= 512 for chunk in chunks))

    def test_compressed_size_limit(self):
        with self.assertRaises(ValueError): list(decode_message(gzip.compress(b" " * 70000), 1001))


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    async def test_services_use_only_agora_with_murf_and_te_target(self):
        calls = []
        async def handle(request):
            body = json.loads(request.content) if request.content else {}
            calls.append((str(request.url), body))
            if request.url.path.endswith("/join"):
                return httpx.Response(200, json={"agent_id": "stt" if "speech-to-text" in request.url.path else "voice"})
            return httpx.Response(200, content=b"")
        client = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        services = AgoraServices(configured(), Session(), lambda uid: "token-" + str(uid), client)
        await services.open()
        await services.speak("నమస్కారం")
        await services.close()
        self.assertTrue(all(url.startswith("https://api.agora.io/") for url, _ in calls))
        stt = calls[0][1]
        self.assertEqual(stt["translateConfig"]["languages"], [{"source": "en-IN", "target": ["te-IN"]}])
        self.assertEqual(stt["rtcConfig"]["subscribeAudioUids"], ["1001"])
        voice = calls[1][1]["properties"]
        self.assertEqual(voice["tts"]["vendor"], "murf")
        self.assertEqual(voice["tts"]["params"]["locale"], "te-IN")
        self.assertEqual(voice["remote_rtc_uids"], ["3001"])
        self.assertEqual(voice["turn_detection"]["config"]["start_of_speech"]["mode"], "manual")
        self.assertEqual(calls[2][1], {"text": "నమస్కారం", "priority": "APPEND", "interruptable": False})
        self.assertTrue(any("/stt/leave" in url for url, _ in calls))
        self.assertTrue(any("/voice/leave" in url for url, _ in calls))

    async def test_partial_startup_is_cleaned_up(self):
        calls = []
        async def handle(request):
            calls.append(str(request.url))
            if "conversational-ai-agent" in str(request.url): return httpx.Response(403, json={"detail": "not enabled"})
            return httpx.Response(200, json={"agent_id": "started-stt"})
        client = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        services = AgoraServices(configured(), Session(), lambda uid: "token", client)
        with self.assertRaises(RuntimeError): await services.open()
        await services.close()
        self.assertTrue(any("/started-stt/leave" in url for url in calls))


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_auth_missing_config_and_no_third_provider(self):
        settings = Settings(access_token="local-test-access-token-12345")
        app = create_app(settings, native_check=lambda: True)
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            self.assertEqual((await client.get("/v1/capabilities")).status_code, 401)
            headers = {"Authorization": "Bearer " + settings.access_token}
            caps = (await client.get("/v1/capabilities", headers=headers)).json()
            self.assertFalse(caps["configured"])
            self.assertFalse(any(key.startswith("TRANSLATION_") for key in caps["missing"]))
            self.assertEqual((await client.post("/v1/sessions", json={}, headers=headers)).status_code, 503)
            idle = await client.post("/v1/agent/idle/chat/completions", headers=headers)
            self.assertIn("[DONE]", idle.text)
            self.assertNotIn("translation", idle.text)

    async def test_session_lifecycle_and_single_session(self):
        class FakePipeline:
            def __init__(self, settings, session): self.session = session
            async def run(self):
                try:
                    self.session.state = "ready"; self.session.ready.set()
                    await self.session.stopped.wait()
                finally: self.session.state = "stopped"; self.session.stopped.set()
        settings = configured()
        app = create_app(settings, pipeline_factory=FakePipeline, native_check=lambda: True)
        headers = {"Authorization": "Bearer " + settings.access_token}
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            unsupported = await client.post("/v1/sessions", json={"target_language": "hi-IN"}, headers=headers)
            self.assertEqual(unsupported.status_code, 422)
            result = await client.post("/v1/sessions", json={}, headers=headers)
            self.assertEqual(result.status_code, 200)
            session = result.json()
            self.assertNotIn(settings.agora_certificate, result.text)
            self.assertNotIn(settings.murf_key, result.text)
            self.assertEqual((await client.post("/v1/sessions", json={}, headers=headers)).status_code, 409)
            stopped = await client.post("/v1/sessions/" + session["id"] + "/stop", headers=headers)
            self.assertEqual(stopped.json()["state"], "stopped")


class PipelineTests(unittest.IsolatedAsyncioTestCase):
    async def test_captions_speak_and_stop_close_both_services(self):
        class FakeTransport:
            async def open(self, on_caption, on_speech, on_error): self.on_speech = on_speech
            async def close(self): self.closed = True
        rtc = FakeTransport()
        class FakeServices:
            def __init__(self): self.spoken = []; self.closed = False
            async def open(self): pass
            async def speak(self, text): self.spoken.append(text); rtc.on_speech()
            async def close(self): self.closed = True; return []
        services, session = FakeServices(), Session()
        pipeline = Pipeline(configured(), session, transport=rtc, services=services)
        task = asyncio.create_task(pipeline.run())
        await asyncio.wait_for(session.ready.wait(), 1)
        data = gzip.compress(json.dumps(caption()).encode())
        pipeline.receive_caption(data); pipeline.receive_caption(data)
        await asyncio.sleep(0.95)
        self.assertEqual(services.spoken, ["నమస్కారం"])
        self.assertEqual(session.phrases, 1)
        session.stopped.set(); await asyncio.wait_for(task, 1)
        self.assertTrue(services.closed and rtc.closed)

    async def test_settings_reject_invalid_public_callback(self):
        settings = replace(configured(), public_url="http://public.example")
        with self.assertRaises(ValueError): settings.validate()


if __name__ == "__main__": unittest.main()
