"""Persistent Falcon connection. Generate ahead of playback, preserve phrase order."""
import asyncio
import audioop
import base64
import json
import uuid
import time
import logging
from .providers import ProviderError

class MurfCapacityError(ProviderError):
    pass

class MurfStream:
    def __init__(self, settings, connect=None):
        self.settings = settings
        self.connect = connect
        self.ws = None
        self.lock = asyncio.Lock()
        self.finished_at = 0

    async def open(self):
        if self.connect is None:
            from websockets.asyncio.client import connect
        else:
            connect = self.connect
        self.ws = await connect(
            'wss://global.api.murf.ai/v1/speech/stream-input?model=falcon-2.1&sample_rate=24000&channel_type=MONO&format=PCM',
            additional_headers={'api-key': self.settings.murf_key},
            open_timeout=15, close_timeout=5, ping_interval=20, ping_timeout=20, max_size=8*1024*1024)

    async def synthesize(self, text, on_pcm, on_speech,voice_id=None,locale=None):
        async with self.lock:
            for attempt in range(4):
                try:
                    return await self._generate(text,on_pcm,on_speech,voice_id,locale)
                except MurfCapacityError:
                    if attempt == 3: raise
                    await self.close()
                    logging.getLogger("uvicorn.error").info("murf_retry reason=active_context_limit attempt=%d",attempt+1)
                    await asyncio.sleep(.15 * (2 ** attempt))

    async def _generate(self,text,on_pcm,on_speech,voice_id,locale):
        # Murf releases a context shortly AFTER final. Preserve numbered audio while
        # allowing that release to settle before opening another context on this socket.
        await asyncio.sleep(max(0,.08-(time.monotonic()-self.finished_at)))
        # A failed idle connection can be reopened before a phrase starts. Never replay
        # a partially generated phrase: that would duplicate words already heard.
        from websockets.protocol import State
        if self.ws is None or getattr(self.ws, 'state', None) == State.CLOSED:
            await self.open()
        context = uuid.uuid4().hex
        state = None
        remainder = b''
        samples = 0
        first = True
        try:
            await self.ws.send(json.dumps({'context_id': context, 'voice_config': {
                'voice_id': voice_id or self.settings.voice, 'locale': locale or self.settings.target_locale,
                'rate': self.settings.voice_rate, 'pitch': 0}}))
            await self.ws.send(json.dumps({'text': text, 'context_id': context, 'end': True}))
            deadline = asyncio.get_running_loop().time() + 90
            while True:
                remaining = deadline - asyncio.get_running_loop().time()
                if remaining <= 0: raise ProviderError('Murf streaming timed out.')
                event = json.loads(await asyncio.wait_for(self.ws.recv(), min(30, max(.01, remaining))))
                if event.get('context_id') not in (None, context):
                    raise ProviderError('Murf returned audio for an unexpected phrase.')
                if event.get('error') or event.get('error_code') or event.get('warning_code') == 'NO_VOICE_CONFIG':
                    code=str(event.get('error_code') or event.get('warning_code') or 'provider_error')
                    code=''.join(c for c in code if c.isalnum() or c=='_')[:48]
                    if code=='ACTIVE_CONTEXT_LIMIT_EXCEEDED' and samples==0:
                        raise MurfCapacityError('Murf capacity is busy before audio generation')
                    raise ProviderError('Murf streaming could not generate this phrase ('+code+').')
                if event.get('audio'):
                    raw = remainder + base64.b64decode(event['audio'], validate=True)
                    remainder = raw[len(raw)//2*2:]
                    raw = raw[:len(raw)//2*2]
                    if raw:
                        pcm, state = audioop.ratecv(raw, 2, 1, 24000, 16000, state)
                        samples += len(pcm)//2
                        if samples > 16000*90: raise ProviderError('Murf phrase exceeded the audio limit.')
                        if pcm:
                            if first and audioop.rms(pcm, 2) > 50:
                                on_speech(); first = False
                            on_pcm(pcm)
                if event.get('final'):
                    if remainder or samples == 0: raise ProviderError('Murf returned incomplete audio.')
                    self.finished_at=time.monotonic()
                    return
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            if isinstance(exc, ProviderError): raise
            close_code=getattr(getattr(exc,'rcvd',None),'code',None)
            if close_code==4029 and samples==0:raise MurfCapacityError('Murf capacity closed the request before audio') from exc
            detail=type(exc).__name__+(' '+str(close_code) if close_code else '')
            raise ProviderError('Direct Murf streaming connection failed ('+detail+').') from exc

    async def close(self):
        if self.ws is not None:
            try: await self.ws.close()
            finally: self.ws = None
