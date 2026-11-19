"""Linux RTC observer receives Agora captions and monitors agent playback. No provider processing here."""
import array
import asyncio
import platform


def native_available():
    if platform.system() != "Linux":
        return False
    try:
        from agora.rtc.agora_service import AgoraService  # noqa: F401
        return True
    except (ImportError, OSError, SystemExit):
        return False


def check(code, operation):
    if code != 0:
        raise RuntimeError(f"Agora {operation} failed ({code})")


class AgoraTransport:
    def __init__(self, settings, session, token):
        self.settings, self.session, self.token = settings, session, token
        self.service = self.connection = None
        self.observer = self.audio_observer = self.local_observer = None
        self.connected = asyncio.Event()

    async def open(self, on_caption, on_speech, on_error):
        from agora.rtc.agora_service import AgoraService
        from agora.rtc.agora_base import AgoraServiceConfig, RTCConnConfig, AudioSubscriptionOptions, RtcConnectionPublishConfig
        from agora.rtc.audio_frame_observer import IAudioFrameObserver
        from agora.rtc.rtc_connection_observer import IRTCConnectionObserver
        from agora.rtc.local_user_observer import IRTCLocalUserObserver
        loop = asyncio.get_running_loop()
        ready = self.connected
        session = self.session

        class ConnectionObserver(IRTCConnectionObserver):
            def on_connected(self, connection, info, reason):
                loop.call_soon_threadsafe(ready.set)
            def on_connection_failure(self, connection, info, reason):
                loop.call_soon_threadsafe(on_error, "Agora control connection failed")
            def on_connection_lost(self, connection, info):
                loop.call_soon_threadsafe(on_error, "Agora control connection lost")

        class CaptionObserver(IRTCLocalUserObserver):
            def on_stream_message(self, local, user_id, stream_id, data, length):
                if str(user_id) == str(session.stt_uid):
                    loop.call_soon_threadsafe(on_caption, bytes(data[:length]))

        class AudioObserver(IAudioFrameObserver):
            def on_playback_audio_frame_before_mixing(self, local, channel, uid, frame, vad_state, vad_audio):
                if str(uid) == str(session.worker_uid):
                    pcm = bytes(frame.buffer)
                    samples = array.array("h")
                    samples.frombytes(pcm[:len(pcm) // 2 * 2])
                    if samples and sum(v*v for v in samples) / len(samples) > 2500:
                        loop.call_soon_threadsafe(on_speech)
                return 1

        config = AgoraServiceConfig()
        config.appid = self.settings.agora_app_id
        config.enable_audio_device = 0; config.enable_video = 0
        self.service = AgoraService()
        check(self.service.initialize(config), "initialize")
        conn_config = RTCConnConfig(auto_subscribe_audio=0, auto_subscribe_video=0,
            audio_subs_options=AudioSubscriptionOptions(packet_only=0, pcm_data_only=1,
                bytes_per_sample=2, number_of_channels=1, sample_rate_hz=16000))
        self.connection = self.service.create_rtc_connection(conn_config,
            RtcConnectionPublishConfig(is_publish_audio=False, is_publish_video=False))
        if self.connection is None:
            raise RuntimeError("Could not create Agora control connection")
        self.observer, self.local_observer, self.audio_observer = ConnectionObserver(), CaptionObserver(), AudioObserver()
        check(self.connection.register_observer(self.observer), "register connection observer")
        check(self.connection.register_local_user_observer(self.local_observer), "register caption observer")
        check(self.connection.get_local_user().set_playback_audio_frame_before_mixing_parameters(1, 16000), "set receive format")
        check(self.connection.register_audio_frame_observer(self.audio_observer, 0, None), "register playback observer")
        check(self.connection.connect(self.token, session.channel, str(session.control_uid)), "connect")
        await asyncio.wait_for(ready.wait(), timeout=20)
        check(self.connection.get_local_user().subscribe_audio(str(session.worker_uid)), "subscribe to voice agent")

    async def close(self):
        if self.connection:
            try:
                self.connection.disconnect()
            finally:
                self.connection.release()
                self.connection = None
        if self.service:
            self.service.release()
            self.service = None

