"""Only Agora REST calls; Murf Falcon is configured inside Conversational AI Engine."""
import httpx


class ProviderError(RuntimeError):
    pass


class AgoraServices:
    def __init__(self, settings, session, token_builder, client=None):
        self.settings, self.session, self.token = settings, session, token_builder
        self.client = client or httpx.AsyncClient(
            auth=httpx.BasicAuth(settings.agora_customer_id, settings.agora_customer_secret), timeout=20,
        )
        self.stt_id = self.agent_id = None
        self.project = "https://api.agora.io/api/"

    async def request(self, method, url, body=None):
        response = await self.client.request(method, url, json=body)
        if response.status_code not in (200, 201):
            raise ProviderError(f"Agora service returned HTTP {response.status_code}. Check enabled services, credentials and quota.")
        if not response.content:
            return {}
        try:
            return response.json()
        except ValueError as exc:
            raise ProviderError("Agora returned an unreadable response") from exc

    def base(self, service):
        path = "speech-to-text/v1" if service == "stt" else "conversational-ai-agent/v2"
        return self.project + path + "/projects/" + self.settings.agora_app_id

    async def open(self):
        await self.open_stt()
        await self.open_voice()

    async def open_stt(self):
        s = self.session
        result = await self.request("POST", self.base("stt") + "/join", {
            "name": "av_stt_" + s.id, "languages": list(s.source_languages), "maxIdleTime": 30,
            "rtcConfig": {
                "channelName": s.channel, "pubBotUid": str(s.stt_uid),
                "pubBotToken": self.token(s.stt_uid),
                "subscribeAudioUids": [str(s.source_uid)], "enableJsonProtocol": True,
            },
            "translateConfig": {"languages": [{"source": language, "target": ["te-IN"]} for language in s.source_languages if language != "te-IN"]},
        })
        self.stt_id = result.get("agent_id")
        if not self.stt_id:
            raise ProviderError("Agora did not return the translation agent ID")

    async def open_voice(self):
        s = self.session
        result = await self.request("POST", self.base("voice") + "/join", {
            "name": "av_voice_" + s.id,
            "properties": {
                "channel": s.channel, "token": self.token(s.worker_uid),
                "agent_rtc_uid": str(s.worker_uid),
                # A silent backend control participant, not the video's audio publisher.
                "remote_rtc_uids": [str(s.control_uid)], "idle_timeout": 30,
                "advanced_features": {"enable_rtm": True},
                "asr": {"vendor": "ares", "language": "en-IN"},
                "llm": {
                    "vendor": "custom", "url": self.settings.public_url + "/v1/agent/idle/chat/completions",
                    "api_key": self.settings.access_token,
                    "system_messages": [{"role": "system", "content": "This agent only broadcasts supplied Telugu text."}],
                },
                "tts": {"vendor": "murf", "params": {
                    "api_key": self.settings.murf_key, "base_url": "wss://global.api.murf.ai/v1/speech/stream-input",
                    "voiceId": self.settings.voice, "locale": "te-IN", "model": "falcon-2.1",
                    "rate": self.settings.voice_rate, "pitch": 0, "sample_rate": 24000,
                }},
                "turn_detection": {"mode": "default", "config": {
                    "start_of_speech": {"mode": "manual"}, "end_of_speech": {"mode": "manual"},
                }},
                "interruption": {"enable": False, "disabled_config": {"strategy": "ignore"}},
                "parameters": {"data_channel": "rtm", "opt_out": True},
                "filler_words": {"enable": False},
            },
        })
        self.agent_id = result.get("agent_id")
        if not self.agent_id:
            raise ProviderError("Agora did not return the voice agent ID")

    async def speak(self, text):
        await self.request("POST", self.base("voice") + f"/agents/{self.agent_id}/speak", {
            "text": text, "priority": "APPEND", "interruptable": False,
        })

    async def close(self):
        errors = []
        for kind, identity in (("stt", self.stt_id), ("voice", self.agent_id)):
            if identity:
                try:
                    await self.request("POST", self.base(kind) + f"/agents/{identity}/leave", {})
                except Exception:
                    errors.append(kind)
        await self.client.aclose()
        return errors
