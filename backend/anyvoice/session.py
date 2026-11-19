import asyncio
from dataclasses import dataclass, field
import time
import uuid
import logging

from .captions import FinalizedCaptions, decode_message, speech_chunks
from .providers import AgoraServices, ProviderError
from .rtc import AgoraTransport
from .completion import SpeechCompletion
from .audio_queue import AudioQueue
from .murf_stream import MurfStream
from .parallel_voice import ParallelVoice


@dataclass
class Session:
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    channel: str = field(default_factory=lambda: "av_" + uuid.uuid4().hex)
    source_uid: int = 1001
    worker_uid: int = 2001  # Conversational AI voice agent; Android subscribes to this UID.
    control_uid: int = 3001
    stt_uid: int = 4001
    state: str = "connecting"
    message: str = "Connecting Agora translation and Telugu voice"
    source: str = ""
    translated: str = ""
    phrases: int = 0
    audio_frames: int = 0
    created: float = field(default_factory=time.monotonic)
    touched: float = field(default_factory=time.monotonic)
    stopped: asyncio.Event = field(default_factory=asyncio.Event)
    ready: asyncio.Event = field(default_factory=asyncio.Event)
    task: asyncio.Task | None = None
    audio: object = None
    voice_provider: str | None = None
    source_language: str = "en-IN"
    source_languages: tuple = ("en-IN",)
    detected_language: str = ""
    route_mode: str = "detecting"
    route_epoch: int = 0

    def public(self):
        return {"id": self.id, "state": self.state, "message": self.message,
                "source_text": self.source, "translated_text": self.translated,
                "phrases": self.phrases, "audio_frames": self.audio_frames,
                "playback_mode": "queued-pcm", "queued_audio_ms": sum(c['duration_ms'] for c in self.audio.clips.values()) if self.audio else 0,
                "last_audio_sequence": self.audio.next_sequence - 1 if self.audio else 0,
                "voice_provider": self.voice_provider,
                "audio_protocol": 3,
                "route_mode": self.route_mode, "route_epoch": self.route_epoch,
                "detected_language": self.detected_language, "source_languages": self.source_languages,
                "source_language": self.source_language, "target_language": "te-IN"}


def token_for(settings, session, uid):
    from .vendor.agora_tokens.AccessToken2 import AccessToken, ServiceRtc, ServiceRtm
    # AccessToken2 privilege expirations are lifetimes in seconds, not Unix timestamps.
    lifetime = settings.max_seconds + 120
    token = AccessToken(settings.agora_app_id, settings.agora_certificate, expire=lifetime)
    rtc = ServiceRtc(session.channel, uid)
    rtc.add_privilege(ServiceRtc.kPrivilegeJoinChannel, lifetime)
    rtc.add_privilege(ServiceRtc.kPrivilegePublishAudioStream, lifetime)
    rtc.add_privilege(ServiceRtc.kPrivilegePublishDataStream, lifetime)
    token.add_service(rtc)
    if uid in (session.worker_uid, session.control_uid):
        rtm = ServiceRtm(str(uid))
        rtm.add_privilege(ServiceRtm.kPrivilegeLogin, lifetime)
        token.add_service(rtm)
    return token.build()


class Pipeline:
    def __init__(self, settings, session, transport=None, services=None, completion=None, direct=None, direct_b=None):
        self.settings, self.session = settings, session
        session.voice_provider = session.voice_provider or settings.voice_provider
        token = lambda uid: token_for(settings, session, uid)
        self.rtc = transport or AgoraTransport(settings, session, token(session.control_uid))
        self.services = services or AgoraServices(settings, session, token)
        self.completion = completion or SpeechCompletion(settings, session, token(session.control_uid))
        self.direct = direct or MurfStream(settings)
        self.direct_b = direct_b or MurfStream(settings)
        self.phrases = asyncio.Queue()
        self.session.audio = AudioQueue()
        self.capture = None
        self.streaming_started = False
        self.packet_buffer = bytearray()
        self.rtc.on_pcm = self.receive_pcm
        self.finals = FinalizedCaptions()
        self.error = asyncio.get_running_loop().create_future()
        self.work = []
        self.speech_count = 0
        self.last_speech = 0
        self.awaiting_audio_since = None
        self.log = logging.getLogger("uvicorn.error")
        self.parallel = None
        self.engine_chunk = 0
        self.engine_part = 0
        self.engine_epoch = 0
        self.language_segments = {}
        self.pending_translations = {}
        self.latest_source_offset = -1
        self.candidate = None
        if session.source_language != "auto":
            session.route_mode = "translated"
            session.detected_language = session.source_language

    def fail(self, message):
        if not self.error.done():
            self.error.set_result(message)

    def receive_speech(self):
        if self.awaiting_audio_since is not None:
            self.log.info("latency session=%s stage=speak_to_first_audio ms=%.0f", self.session.id[:8],
                          (time.monotonic() - self.awaiting_audio_since) * 1000)
            self.awaiting_audio_since = None
        self.speech_count += 1
        self.session.audio_frames += 1
        self.last_speech = time.monotonic()

    def current_epoch(self, epoch):
        return self.session.route_mode == "translated" and epoch == self.session.route_epoch

    def route_source(self, item):
        language, identity = item.get("language"), item.get("id")
        if language not in self.session.source_languages or not identity:
            return
        offset = item.get("offset")
        if isinstance(offset, (int,float)) and offset < self.latest_source_offset:
            return  # Delayed source captions cannot undo a newer language decision.
        now = time.monotonic()
        prior = self.candidate
        stable = item.get("final") or (prior and prior[0] == language and prior[1] == identity
            and prior[2] != item["text"] and now-prior[3] >= .3)
        if prior is None or prior[:2] != (language,identity):
            self.candidate = (language,identity,item["text"],now)
        if not stable:
            return
        if isinstance(offset, (int,float)): self.latest_source_offset = offset
        session = self.session
        if session.detected_language != language:
            session.route_epoch += 1
            session.detected_language = language
            session.route_mode = "original" if language == "te-IN" else "translated"
            session.translated = ""
            self.packet_buffer.clear()
            if self.parallel: self.parallel.release_ready()
            self.log.info("language_route session=%s language=%s mode=%s epoch=%d",
                session.id[:8],language,session.route_mode,session.route_epoch)
        session.message = "Telugu detected - original audio" if language == "te-IN" else language + " detected - translating to Telugu"
        self.language_segments[identity] = (language,session.route_epoch)
        if len(self.language_segments)>2000: self.language_segments.pop(next(iter(self.language_segments)))
        pending = self.pending_translations.pop(identity,None)
        if pending: self.accept_translation(pending)

    def accept_translation(self, item):
        if self.session.source_language == "auto":
            segment = self.language_segments.get(item["id"])
            if segment is None:
                self.pending_translations[item["id"]] = item
                if len(self.pending_translations)>128: self.pending_translations.pop(next(iter(self.pending_translations)))
                return
            language, epoch = segment
            if language == "te-IN" or not self.current_epoch(epoch): return
        else:
            epoch = self.session.route_epoch
        delta = self.finals.accept(item["id"], item["text"])
        if delta.strip():
            end_ms = item.get("speech_end_ms")
            if end_ms is not None and 0 <= time.time()*1000-end_ms < 120000:
                self.log.info("latency session=%s stage=speech_end_to_translation ms=%.0f",
                    self.session.id[:8],time.time()*1000-end_ms)
            self.session.translated = item["text"]
            self.phrases.put_nowait((delta,time.monotonic(),epoch))

    def receive_caption(self, data):
        if self.session.stopped.is_set(): return
        try:
            for item in decode_message(data,self.session.source_uid):
                if item["kind"] == "source":
                    self.session.source = item["text"]
                    if self.session.source_language == "auto": self.route_source(item)
                else: self.accept_translation(item)
        except Exception as exc:
            self.log.error("Caption decode failed: %s; bytes=%s",type(exc).__name__,len(data))
            self.fail("Could not process Agora captions.")

    def receive_pcm(self, pcm):
        if not self.streaming_started or self.session.stopped.is_set() or not self.current_epoch(self.engine_epoch): return
        self.packet_buffer.extend(pcm)
        while len(self.packet_buffer) >= 1600:  # 50 ms, mono 16 kHz PCM16
            self.engine_part += 1
            self.session.audio.append(bytes(self.packet_buffer[:1600]),max(1,self.engine_chunk),self.engine_part,self.engine_epoch)
            del self.packet_buffer[:1600]

    async def speak_phrases(self):
        if self.session.voice_provider == 'murf-direct':
            def generated(): self.session.phrases += 1
            self.parallel = ParallelVoice([self.direct,self.direct_b],self.session.audio,self.fail,generated,self.log,is_current=self.current_epoch)
            await self.parallel.run(self.phrases)
            return
        while True:
            phrase = await self.phrases.get()
            text, queued = phrase[:2]
            epoch = phrase[2] if len(phrase)>2 else self.session.route_epoch
            if not self.current_epoch(epoch): continue
            # Submit stable text immediately. Audio itself streams in 50 ms packets.
            while not self.phrases.empty():
                phrase = self.phrases.get_nowait()
                following = phrase[0]
                following_epoch = phrase[2] if len(phrase)>2 else self.session.route_epoch
                if following_epoch != epoch: continue
                text += ' ' + following
                if len(text.encode('utf-8')) >= 512: break
            for chunk in speech_chunks(text, limit=512):
                if not self.current_epoch(epoch): break
                self.engine_epoch = epoch
                self.engine_chunk += 1
                self.engine_part = 0
                self.session.message = 'Listening - preparing Telugu audio'
                self.streaming_started = True
                if self.session.voice_provider == 'agora-engine': self.completion.begin()
                submitted = time.monotonic()
                self.log.info('latency session=%s stage=caption_queue_wait ms=%.0f bytes=%d',
                    self.session.id[:8], (submitted-queued)*1000, len(chunk.encode('utf-8')))
                self.awaiting_audio_since = submitted
                await self.services.speak(chunk)
                # Engine route is retained for hackathon demo and A/B comparisons.
                await self.completion.wait()
                if self.packet_buffer and self.current_epoch(epoch):
                    self.engine_part += 1
                    self.session.audio.append(bytes(self.packet_buffer),self.engine_chunk,self.engine_part,self.engine_epoch)
                    self.packet_buffer.clear()
                self.session.phrases += 1
                self.log.info('speech_streamed session=%s last_packet=%d generation_ms=%.0f',
                    self.session.id[:8], self.session.audio.next_sequence-1,
                    (time.monotonic()-submitted)*1000)
            self.session.message = 'Listening - Telugu audio plays in order'

    async def monitor(self):
        while True:
            await asyncio.sleep(1)
            now = time.monotonic()
            if now - self.session.created > self.settings.max_seconds:
                raise ProviderError("Session time limit reached.")
            if now - self.session.touched > 25:
                raise ProviderError("Phone disconnected. Session ended.")

    async def run(self):
        try:
            await self.rtc.open(self.receive_caption, self.receive_speech, self.fail)
            if self.session.voice_provider == 'murf-direct':
                await asyncio.gather(self.direct.open(), self.direct_b.open())
                await self.services.open_stt()
            else:
                await self.completion.open()
                await self.services.open()
            self.session.touched = time.monotonic()
            self.session.state, self.session.message = "ready", "Detecting language - original audio" if self.session.source_language == "auto" else "Listening to English"
            self.session.ready.set()
            self.work = [asyncio.create_task(coro) for coro in (
                self.speak_phrases(), self.monitor(), self.session.stopped.wait(),
            )]
            done, _ = await asyncio.wait([*self.work, self.error], return_when=asyncio.FIRST_COMPLETED)
            if self.error in done:
                raise ProviderError(self.error.result())
            for task in done:
                task.result()
        except asyncio.CancelledError:
            pass
        except Exception as exc:
            self.session.state = "error"
            self.session.message = str(exc) if isinstance(exc, ProviderError) else "Agora connection failed. Check backend configuration and enabled services."
        finally:
            self.session.stopped.set()
            for task in self.work:
                task.cancel()
            await asyncio.gather(*self.work, return_exceptions=True)
            # Stop both billable services before removing the silent control participant.
            try:
                failures = await self.services.close()
                if failures:
                    self.session.state = "error"
                    self.session.message = "Could not confirm cloud cleanup. Check active agents in Agora Console."
            except Exception:
                self.session.state = "error"
                self.session.message = "Cloud cleanup was not confirmed. Check active agents in Agora Console."
            finally:
                try:
                    try:
                        await self.completion.close()
                    finally:
                        try: await self.direct.close()
                        finally:
                            try: await self.direct_b.close()
                            finally: await self.rtc.close()
                except Exception:
                    self.session.state = "error"
                    self.session.message = "Control connection cleanup failed."
                if self.session.state != "error":
                    self.session.state, self.session.message = "stopped", "Session stopped"
                # Completed clips survive a provider error so the phone can finish its queue.
                if self.session.state != "error" or self.session.audio.next_sequence == 1:
                    self.session.audio.close()
                self.session.ready.set()
