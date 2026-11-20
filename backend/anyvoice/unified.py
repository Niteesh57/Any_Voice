"""One RTC lifetime, isolated media/microphone UIDs, one ordered voice queue."""
import asyncio
import time
from .bubble import BubblePipeline
from .session import Pipeline,token_for
from .providers import AgoraServices,ProviderError
from .parallel_voice import ParallelVoice

class UnifiedServices(AgoraServices):
 async def open_stt(self):
  await self.open_completion()
  await super().open_stt()
  await self.open_voice()

class UnifiedPipeline(BubblePipeline):
 def __init__(self,settings,session,transport=None,services=None,completion=None,qwen=None,direct=None,direct_b=None):
  services=services or UnifiedServices(settings,session,lambda uid:token_for(settings,session,uid))
  super().__init__(settings,session,transport,services,completion,qwen)
  # Provider settings must retain the selected dynamic voice/locale.
  services.settings=self.settings
  services.open_completion=self.completion.open
  if direct:self.direct=direct
  if direct_b:self.direct_b=direct_b
  session.voice_provider='murf-direct';session.route_mode='detecting';session.route_epoch=0
  session.bubble_state='listening' if session.input_mode=='bubble' else 'standby';self.last_caption=0
  if session.input_mode=='bubble':session.route_mode='translated';session.route_epoch=1
  self.media_started_ms=time.time()*1000

 def request_input(self,mode):
  if self.session.desired_input!=mode:
   self.session.desired_input=mode;self.switch_requested=time.monotonic()
   self.log.info('input_requested session=%s mode=%s',self.session.id[:8],mode)

 def receive_caption(self,data):
  if self.session.input_mode!='media':return
  self.last_caption=time.monotonic()
  Pipeline.receive_caption(self,data)

 def route_source(self,item):
  try:stamp=float(item['id'])
  except (ValueError,TypeError,KeyError):stamp=0
  if stamp>1e12 and stamp<self.media_started_ms:return
  Pipeline.route_source(self,item)

 def accept_translation(self,item):
  if item.get('speech_end_ms') and item['speech_end_ms']<self.media_started_ms:return
  Pipeline.accept_translation(self,item)

 def receive_input_pcm(self,pcm):
  if self.session.input_mode=='bubble' and self.session.desired_input=='bubble':
   BubblePipeline.receive_input_pcm(self,pcm)

 def receive_pcm(self,pcm):
  if self.session.input_mode=='bubble':BubblePipeline.receive_pcm(self,pcm)

 def voice_idle(self):
  return self.phrases.empty() and self.parallel is not None and not self.parallel.chunks and self.parallel.jobs.empty()

 async def speak_phrases(self):
  def generated():self.session.phrases+=1
  self.parallel=ParallelVoice([self.direct,self.direct_b],self.session.audio,self.fail,generated,self.log,is_current=self.current_epoch)
  jobs=[asyncio.create_task(self.parallel.run(self.phrases)),asyncio.create_task(BubblePipeline.speak_phrases(self))]
  try:await asyncio.gather(*jobs)
  finally:
   for job in jobs:job.cancel()
   await asyncio.gather(*jobs,return_exceptions=True)

 async def monitor(self):
  self.switch_requested=time.monotonic()
  if self.session.input_mode=='bubble':self.session.message='Bubble is listening'
  while True:
   await asyncio.sleep(.1);s=self.session;now=time.monotonic()
   if now-s.created>self.settings.max_seconds:raise ProviderError('Session time limit reached')
   if now-s.touched>25:raise ProviderError('Phone disconnected')
   idle=self.voice_idle();played=s.audio.acknowledged>=s.audio.next_sequence-1
   if s.input_mode=='bubble' and s.bubble_state in ('thinking','speaking'):
    if s.audio.next_sequence-1>s.audio.acknowledged:s.bubble_state='speaking'
    if self.generation_done and played and s.audio.acknowledged>=self.response_end:
     self.generation_done=False;self.vad.reset();self.ignore_until=now+.35
     s.bubble_state='listening';s.message='Bubble is listening'
   if s.desired_input==s.input_mode:continue
   if s.input_mode=='media':
    # A pause can precede late final captions. Keep translation/generation alive through the tail.
    if now-max(self.last_caption,self.switch_requested)<2 or not idle or not played:continue
   elif s.bubble_state in ('thinking','speaking') or not idle or not played:continue
   s.input_mode=s.desired_input;s.route_epoch+=1
   self.vad.reset();self.ignore_until=now+.35
   s.source='';s.translated=''
   if s.input_mode=='bubble':
    self.engine_chunk=self.parallel.next_number-1
    s.route_mode='translated';s.bubble_state='listening';s.message='Bubble is listening'
   else:
    self.parallel.next_number=self.engine_chunk+1;self.parallel.next_release=self.engine_chunk+1
    self.media_started_ms=time.time()*1000
    self.language_segments.clear();self.pending_translations.clear();self.route_votes=None;self.candidate=None
    self.latest_source_offset=-1;s.detected_language='';s.route_mode='detecting';s.bubble_state='standby';s.message='Detecting media language'
   self.log.info('input_switched session=%s mode=%s last_sequence=%d acknowledged=%d',s.id[:8],s.input_mode,s.audio.next_sequence-1,s.audio.acknowledged)

 async def run(self):
  try:await Pipeline.run(self)
  finally:
   if self.answer_task:self.answer_task.cancel();await asyncio.gather(self.answer_task,return_exceptions=True)
   await self.qwen.close()
