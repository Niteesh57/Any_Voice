"""Only Agora REST calls; Murf Falcon is configured inside Conversational AI Engine."""
import httpx
import asyncio


class ProviderError(RuntimeError):
    pass


class AgoraServices:
    def __init__(self, settings, session, token_builder, client=None):
        self.settings, self.session, self.token = settings, session, token_builder
        self.client = client or httpx.AsyncClient(
            auth=httpx.BasicAuth(settings.agora_customer_id, settings.agora_customer_secret), timeout=20,
        )
        self.stt_id = self.agent_id = None
        self.bubble_stt_id=None
        self.conversation_stt_id=None;self.conversation_pair=None
        self.conversation_lock=asyncio.Lock();self.closing=False
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
        body = {
            "name": "av_stt_" + s.id, "languages": list(s.source_languages), "maxIdleTime": 900 if s.mode=='unified' else 30,
            "rtcConfig": {
                "channelName": s.channel, "pubBotUid": str(s.stt_uid),
                "pubBotToken": self.token(s.stt_uid),
                "subscribeAudioUids": [str(s.source_uid)], "enableJsonProtocol": True,
            },
            "translateConfig": {"languages": [{"source": language, "target": [s.target_language]} for language in s.source_languages if language != s.target_language]},
        }
        if not body['translateConfig']['languages']:body.pop('translateConfig')
        result = await self.request("POST", self.base("stt") + "/join", body)
        self.stt_id = result.get("agent_id")
        if not self.stt_id:
            raise ProviderError("Agora did not return the translation agent ID")

    async def open_bubble_stt(self):
        if self.settings.bubble_llm_provider!='nvidia' or self.bubble_stt_id:return
        s=self.session
        languages=list(dict.fromkeys((s.target_language,'en-IN')))
        result=await self.request('POST',self.base('stt')+'/join',{
            'name':'av_bubble_'+s.id,'languages':languages,'maxIdleTime':900,
            'rtcConfig':{'channelName':s.channel,'pubBotUid':str(s.bubble_stt_uid),
                'pubBotToken':self.token(s.bubble_stt_uid),
                'subscribeAudioUids':[str(s.microphone_uid if s.mode=='unified' else s.source_uid)],'enableJsonProtocol':True}})
        self.bubble_stt_id=result.get('agent_id')
        if not self.bubble_stt_id:raise ProviderError('Agora did not return the Bubble recognizer ID')

    async def open_voice(self):
        s = self.session
        result = await self.request("POST", self.base("voice") + "/join", {
            "name": "av_voice_" + s.id,
            "properties": {
                "channel": s.channel, "token": self.token(s.worker_uid),
                "agent_rtc_uid": str(s.worker_uid),
                # A silent backend control participant, not the video's audio publisher.
                "remote_rtc_uids": [str(s.control_uid)], "idle_timeout": 900 if s.mode=='unified' else 30,
                "advanced_features": {"enable_rtm": True},
                "asr": {"vendor": "ares", "language": "en-IN"},
                "llm": {
                    "vendor": "custom", "url": self.settings.public_url + "/v1/agent/idle/chat/completions",
                    "api_key": self.settings.access_token,
                    "system_messages": [{"role": "system", "content": "This agent only broadcasts supplied translated text."}],
                },
                "tts": {"vendor": "murf", "params": {
                    "api_key": self.settings.murf_key, "base_url": "wss://global.api.murf.ai/v1/speech/stream-input",
                    "voiceId": self.settings.voice, "locale": self.settings.target_locale, "model": "falcon-2.1",
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

    async def open_conversation(self,other_language):
        async with self.conversation_lock:
            if self.closing:raise ProviderError('Session is stopping')
            await self._open_conversation(other_language)

    async def _open_conversation(self,other_language):
        s=self.session;pair=(s.target_language,other_language)
        if self.conversation_stt_id and self.conversation_pair==pair:return
        if self.conversation_stt_id:
            await self.request('POST',self.base('stt')+f'/agents/{self.conversation_stt_id}/leave',{})
            self.conversation_stt_id=None
        result=await self.request('POST',self.base('stt')+'/join',{
            'name':'av_conversation_'+s.id,'languages':list(pair),'maxIdleTime':900,
            'rtcConfig':{'channelName':s.channel,'pubBotUid':str(s.conversation_stt_uid),'pubBotToken':self.token(s.conversation_stt_uid),'subscribeAudioUids':[str(s.conversation_uid)],'enableJsonProtocol':True},
            'translateConfig':{'languages':[{'source':pair[0],'target':[pair[1]]},{'source':pair[1],'target':[pair[0]]}]}})
        self.conversation_stt_id=result.get('agent_id')
        if not self.conversation_stt_id:raise ProviderError('Agora did not return the conversation translator ID')
        self.conversation_pair=pair

    async def close(self):
        self.closing=True
        async with self.conversation_lock:return await self._close()

    async def _close(self):
        errors = []
        for kind, identity in (("stt", self.stt_id), ("stt",self.bubble_stt_id), ("stt",self.conversation_stt_id), ("voice", self.agent_id)):
            if identity:
                try:
                    await self.request("POST", self.base(kind) + f"/agents/{identity}/leave", {})
                except Exception:
                    errors.append(kind)
        await self.client.aclose()
        return errors
