"""RTM completion events: one broadcast in flight, never infer completion from silence."""
import asyncio
import json
from .providers import ProviderError

class SpeechCompletion:
    def __init__(self, settings, session, token):
        self.settings, self.session, self.token = settings, session, token
        self.client = None
        self.done = asyncio.Event()
        self.pending = False
        self.last_turn = -1
        self.failure = None

    def begin(self):
        self.done.clear(); self.pending = True; self.failure = None

    def receive(self, publisher, message):
        if str(publisher) != str(self.session.worker_uid): return
        try:
            item = json.loads(message)
            if isinstance(item.get('message'), str): item = json.loads(item['message'])
            if item.get('event_type') != 'turn.finished': return
            p = item['payload']; turn = int(p['turn_id'])
            if p.get('start', {}).get('type') != 'api_speak' or turn <= self.last_turn: return
            self.last_turn = turn
            if not self.pending: return
            if p.get('end', {}).get('type') != 'ok':
                self.failure = 'Voice preparation did not complete.'
            self.pending = False; self.done.set()
        except (ValueError, TypeError, KeyError): return

    async def wait(self):
        try:
            await asyncio.wait_for(self.done.wait(), 75)
        except asyncio.TimeoutError as exc:
            raise ProviderError('Voice preparation timed out.') from exc
        if self.failure: raise ProviderError(self.failure)

    async def open(self):
        from agora.rtm.rtm_client import RTMClient
        from agora.rtm.rtm_base import RtmConfig, IRtmEventHandler, SubscribeOptions, RtmLogConfig, RtmLogLevel
        loop = asyncio.get_running_loop(); logged = loop.create_future(); subscribed = loop.create_future()
        owner = self
        def result(future, code):
            if not future.done():
                if code == 0: future.set_result(None)
                else: future.set_exception(RuntimeError('Agora playback signaling failed (%s)' % code))
        class Handler(IRtmEventHandler):
            def on_login_result(self, request, code): loop.call_soon_threadsafe(result, logged, code)
            def on_subscribe_result(self, request, channel, code): loop.call_soon_threadsafe(result, subscribed, code)
            def on_message_event(self, event): loop.call_soon_threadsafe(owner.receive, event.publisher, event.message)
        self.handler = Handler()
        self.client = RTMClient(RtmConfig(app_id=self.settings.agora_app_id, user_id=str(self.session.control_uid),
            event_handler=self.handler, log_config=RtmLogConfig(file_path='/tmp/anyvoice-rtm.log', log_level=RtmLogLevel.RTM_LOG_LEVEL_ERROR)))
        code, _ = self.client.login(self.token)
        if code: raise RuntimeError('Could not start playback signaling')
        await asyncio.wait_for(logged, 15)
        code, _ = self.client.subscribe(self.session.channel, SubscribeOptions())
        if code: raise RuntimeError('Could not subscribe to playback completion')
        await asyncio.wait_for(subscribed, 15)

    async def close(self):
        if self.client:
            self.client.logout(); self.client.release(); self.client = None
