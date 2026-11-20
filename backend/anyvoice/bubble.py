"""Opt-in microphone assistant, isolated from the translation pipeline."""
import asyncio
import audioop
import logging
import time
from collections import deque
from dataclasses import replace
from .captions import speech_chunks
from .providers import AgoraServices,ProviderError
from .session import Pipeline,token_for
from .qwen_audio import QwenAudio

class CommandVad:
 """Bounded PCM endpoint detector; command audio remains in memory only."""
 def __init__(self):self.noise=15;self.background=deque(maxlen=100);self.reset()
 def reset(self):self.frames=[];self.pre=deque(maxlen=10);self.voiced=0;self.silent=0;self.size=0
 def observe_background(self,pcm):
  if pcm and not len(pcm)%2:
   rms=audioop.rms(pcm,2)
   if 0<rms<600:
    self.background.append(rms);ordered=sorted(self.background);self.noise=ordered[len(ordered)//5]
 def feed(self,pcm):
  if not pcm or len(pcm)%2:return None
  rms=audioop.rms(pcm,2)
  speech=rms>=max(45,self.noise*3)
  if not speech and not self.frames:self.noise=self.noise*.95+rms*.05
  duration=len(pcm)/32000
  if not self.frames:
   self.pre.append(pcm)
   if not speech:return None
   self.frames=list(self.pre);self.size=sum(map(len,self.frames));self.pre.clear()
  else:self.frames.append(pcm);self.size+=len(pcm)
  if speech:self.voiced+=duration;self.silent=0
  else:self.silent+=duration
  if self.size>=32000*12 or self.silent>=.65:
   result=b"".join(self.frames) if self.voiced>=.16 else None
   self.reset();return result
  return None

class BubbleServices(AgoraServices):
 async def open_stt(self):
  # Direct microphone audio goes to Qwen; no wake recognizer or STT bot.
  pass

class BubblePipeline(Pipeline):
 def __init__(self,settings,session,transport=None,services=None,completion=None,qwen=None):
  session.voice_provider="agora-engine";session.route_mode="translated";session.route_epoch=1
  chosen=replace(settings,voice=session.voice_id or settings.voice,target_locale=session.voice_locale or session.target_language)
  services=services or BubbleServices(chosen,session,lambda uid:token_for(chosen,session,uid))
  super().__init__(settings,session,transport=transport,services=services,completion=completion)
  session.route_mode="translated";session.bubble_state="listening"
  self.qwen=qwen or QwenAudio(self.settings,session.target_language,session.context)
  if session.owner and session.memory_store and not qwen:
   self.qwen.use_memory(session.memory_store,session.owner,session.timezone,session.device_apps,session.screen_tools,session)
  self.rtc.on_input_pcm=self.receive_input_pcm
  self.vad=CommandVad();self.answer_task=None;self.listening_deadline=0
  self.generation_done=False;self.response_end=0;self.ignore_until=0;self.response_chunks=asyncio.Queue();self.voice_last_packet=0
 def receive_caption(self,data):
  pass
 def receive_input_pcm(self,pcm):
  self.session.input_frames+=1
  self.session.input_rms=audioop.rms(pcm,2) if pcm and not len(pcm)%2 else 0
  self.session.max_input_rms=max(self.session.max_input_rms,self.session.input_rms)
  if self.session.bubble_state=="standby":self.vad.observe_background(pcm)
  if self.session.stopped.is_set() or self.session.bubble_state!="listening" or time.monotonic()<self.ignore_until:return
  command=self.vad.feed(pcm)
  if command:
   self.session.bubble_state="thinking";self.session.message="Bubble is thinking";self.generation_done=False
   self.listening_deadline=0
   self.answer_task=asyncio.create_task(self.answer(command))
 def receive_pcm(self,pcm):
  if self.streaming_started and pcm:self.voice_last_packet=time.monotonic()
  super().receive_pcm(pcm)
 async def answer(self,pcm):
  pending="";answer="";first=True;started=time.monotonic()
  try:
   self.session.translated=""
   async for delta in self.qwen.answer(pcm):
    if first:
     self.log.info("bubble_latency stage=qwen_first_text ms=%.0f",(time.monotonic()-started)*1000);first=False
    pending+=delta;answer+=delta;self.session.translated=answer
    if len(pending)>=30 and (any(c in delta for c in ".!?।。！？\n") or len(pending.encode())>=400):
     for chunk in speech_chunks(pending):await self.response_chunks.put(chunk)
     pending=""
   if pending.strip():
    for chunk in speech_chunks(pending):await self.response_chunks.put(chunk)
   await self.response_chunks.put(None)
  except asyncio.CancelledError:raise
  except Exception as exc:
   self.fail(str(exc) if isinstance(exc,ProviderError) else "Bubble could not answer.")
 async def speak_phrases(self):
  while True:
   text=await self.response_chunks.get()
   if text is None:
    self.generation_done=True;self.response_end=self.session.audio.next_sequence-1;continue
   if not text.strip():continue
   self.session.bubble_state="speaking";self.session.message="Bubble is speaking"
   self.engine_chunk+=1;self.engine_part=0;self.engine_epoch=self.session.route_epoch
   first_sequence=self.session.audio.next_sequence
   self.voice_last_packet=0;self.streaming_started=True;self.completion.begin()
   await self.services.speak(text);await self.completion.wait()
   deadline=time.monotonic()+1
   while not self.voice_last_packet and time.monotonic()<deadline:await asyncio.sleep(.02)
   deadline=time.monotonic()+1
   while self.voice_last_packet and time.monotonic()-self.voice_last_packet<.12 and time.monotonic()<deadline:await asyncio.sleep(.02)
   if self.packet_buffer:
    self.engine_part+=1;self.session.audio.append(bytes(self.packet_buffer),self.engine_chunk,self.engine_part,self.engine_epoch);self.packet_buffer.clear()
   if self.session.audio.next_sequence==first_sequence:raise ProviderError("Bubble voice returned no audio. Try another supported voice.")
   self.streaming_started=False;self.session.phrases+=1
 async def monitor(self):
  while True:
   await asyncio.sleep(.1);now=time.monotonic()
   if now-self.session.created>self.settings.max_seconds:raise ProviderError("Bubble session limit reached. Tap Ask Bubble again.")
   if now-self.session.touched>25:raise ProviderError("Bubble disconnected.")
   if self.generation_done and self.session.audio.acknowledged>=self.response_end:
    self.generation_done=False;self.vad.reset();self.ignore_until=now+.35
    self.session.bubble_state="listening";self.session.message="Bubble is listening to your command";self.listening_deadline=now+25
 async def run(self):
  try:
   await super().run()
  finally:
   if self.answer_task:self.answer_task.cancel();await asyncio.gather(self.answer_task,return_exceptions=True)
   await self.qwen.close()
