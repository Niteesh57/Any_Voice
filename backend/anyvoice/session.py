import asyncio
from dataclasses import dataclass, field
import time
import uuid
import logging

from .captions import FinalizedCaptions, decode_message, speech_chunks
from .providers import AgoraServices, ProviderError
from .rtc import AgoraTransport


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

    def public(self):
        return {"id": self.id, "state": self.state, "message": self.message,
                "source_text": self.source, "translated_text": self.translated,
                "phrases": self.phrases, "audio_frames": self.audio_frames,
                "source_language": "en-IN", "target_language": "te-IN"}


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
    if uid == session.worker_uid:
        rtm = ServiceRtm(str(uid))
        rtm.add_privilege(ServiceRtm.kPrivilegeLogin, lifetime)
        token.add_service(rtm)
    return token.build()


class Pipeline:
    def __init__(self, settings, session, transport=None, services=None):
        self.settings, self.session = settings, session
        token = lambda uid: token_for(settings, session, uid)
        self.rtc = transport or AgoraTransport(settings, session, token(session.control_uid))
        self.services = services or AgoraServices(settings, session, token)
        self.phrases = asyncio.Queue(maxsize=8)
        self.finals = FinalizedCaptions()
        self.error = asyncio.get_running_loop().create_future()
        self.work = []
        self.speech_count = 0
        self.last_speech = 0
        self.awaiting_audio_since = None
        self.log = logging.getLogger("uvicorn.error")

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

    def receive_caption(self, data):
        if self.session.stopped.is_set():
            return
        try:
            for item in decode_message(data, self.session.source_uid):
                if item["kind"] == "source":
                    self.session.source = item["text"]
                else:
                    delta = self.finals.accept(item["id"], item["text"])
                    if delta.strip():
                        self.session.translated = item["text"]
                        self.phrases.put_nowait((delta, time.monotonic()))
        except asyncio.QueueFull:
            self.fail("Translation is falling behind. Pause the video and restart to resynchronize.")
        except Exception as exc:
            logging.getLogger("anyvoice").error("Caption decode failed: %s; bytes=%s; prefix=%s", type(exc).__name__, len(data), data[:12].hex())
            self.fail("Could not process Agora captions. Stop and restart the session.")

    async def speak_phrases(self):
        while True:
            text, queued = await self.phrases.get()
            if time.monotonic() - queued > 15:
                raise ProviderError("Translation is over 15 seconds behind. Restart to resynchronize.")
            for chunk in speech_chunks(text):
                self.session.message = "Speaking Telugu"
                before = self.speech_count
                submitted = time.monotonic()
                self.awaiting_audio_since = submitted
                self.log.info("latency session=%s stage=caption_to_submit ms=%.0f bytes=%d", self.session.id[:8],
                              (submitted - queued) * 1000, len(chunk.encode("utf-8")))
                await self.services.speak(chunk)
                self.log.info("latency session=%s stage=speak_request ms=%.0f", self.session.id[:8],
                              (time.monotonic() - submitted) * 1000)
                # One in-flight broadcast: confirm voice arrives, then wait for its tail.
                # SDK audio frames are measured only for the agent UID, never source audio.
                deadline = time.monotonic() + 25
                while self.speech_count == before:
                    if time.monotonic() > deadline:
                        raise ProviderError("No Telugu voice returned. Check Murf voice access and Agora agent status.")
                    await asyncio.sleep(0.02)
                deadline = time.monotonic() + 60
                # APPEND preserves ordering if this short quiet interval is an internal pause.
                # Keep a single submitted chunk at a time; do not flood the provider queue.
                while time.monotonic() - self.last_speech < 0.15:
                    if time.monotonic() > deadline:
                        raise ProviderError("Voice playback did not finish. Restart the session.")
                    await asyncio.sleep(0.02)
            self.session.phrases += 1
            self.session.message = "Listening to English"

    async def monitor(self):
        while True:
            await asyncio.sleep(1)
            now = time.monotonic()
            if now - self.session.created > self.settings.max_seconds:
                raise ProviderError("Session time limit reached. Start a new session to continue.")
            if now - self.session.touched > 25:
                raise ProviderError("Phone disconnected. Session ended.")

    async def run(self):
        try:
            await self.rtc.open(self.receive_caption, self.receive_speech, self.fail)
            await self.services.open()
            self.session.touched = time.monotonic()
            self.session.state, self.session.message = "ready", "Listening to English"
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
                    await self.rtc.close()
                except Exception:
                    self.session.state = "error"
                    self.session.message = "Control connection cleanup failed. Restart the backend before another session."
                if self.session.state != "error":
                    self.session.state, self.session.message = "stopped", "Session stopped"
                self.session.ready.set()
