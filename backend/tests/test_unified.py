import asyncio
import json
import time
import unittest
from types import SimpleNamespace
from anyvoice.session import Session
from anyvoice.unified import UnifiedPipeline
from test_pipeline import configured,FakeCompletion

class UnifiedTests(unittest.IsolatedAsyncioTestCase):
 async def test_chunk_end_survives_early_playback_ack(self):
  from anyvoice.audio_queue import AudioQueue
  q=AudioQueue();self.addCleanup(q.close)
  q.append(b'01'*800,1,1,1);q.acknowledge(1);q.complete_chunk(1)
  self.assertEqual(q.chunk_ends[1],1)
  q.append(b'01'*800,2,1,1);q.complete_chunk(2);q.acknowledge(2)
  self.assertEqual(q.chunk_ends,{1:1,2:2})
 def pipeline(self):
  s=Session(mode='unified',source_language='auto',source_languages=('en-IN','hi-IN','ta-IN','te-IN'))
  async def close():pass
  p=UnifiedPipeline(configured(),s,transport=SimpleNamespace(),services=SimpleNamespace(),completion=FakeCompletion(),qwen=SimpleNamespace(close=close))
  p.parallel=SimpleNamespace(chunks={},jobs=asyncio.Queue(),next_number=2,next_release=2)
  self.addCleanup(s.audio.close);return p,s

 async def test_input_handoff_waits_for_generation_and_hardware_ack_reuses_identity(self):
  p,s=self.pipeline();identity=(s.id,s.channel,id(p.rtc));s.state='ready'
  s.audio.append(b'01'*800,1,1,1);s.audio.complete_chunk(1)
  task=asyncio.create_task(p.monitor())
  try:
   await asyncio.sleep(.01);p.request_input('bubble');p.switch_requested-=3
   p.parallel.chunks[1]=object()
   await asyncio.sleep(.12);self.assertEqual(s.input_mode,'media')
   p.parallel.chunks.clear();await asyncio.sleep(.12);self.assertEqual(s.input_mode,'media')
   s.audio.acknowledge(1);await asyncio.sleep(.12)
   self.assertEqual(s.input_mode,'bubble');self.assertTrue(s.public()['input_allowed'])
   s.bubble_state='thinking';p.request_input('media');await asyncio.sleep(.12)
   self.assertEqual(s.input_mode,'bubble')
   s.bubble_state='listening';await asyncio.sleep(.12)
   self.assertEqual(s.input_mode,'media');self.assertFalse(s.public()['input_allowed'])
   self.assertEqual((s.id,s.channel,id(p.rtc)),identity)
  finally:task.cancel();await asyncio.gather(task,return_exceptions=True)

 async def test_pending_final_caption_extends_drain_and_microphone_ignored_in_media(self):
  p,s=self.pipeline();task=asyncio.create_task(p.monitor())
  try:
   await asyncio.sleep(.01);p.request_input('bubble');p.switch_requested-=3
   p.last_caption=time.monotonic()
   p.receive_input_pcm(b'01'*320);self.assertEqual(s.input_frames,0)
   await asyncio.sleep(.12);self.assertEqual(s.input_mode,'media')
   p.last_caption-=3;await asyncio.sleep(.12);self.assertEqual(s.input_mode,'bubble')
   s.bubble_state='listening';p.receive_input_pcm(bytes(640));self.assertEqual(s.input_frames,1)
   # Media captions cannot pollute the microphone conversation after handoff.
   p.receive_caption(json.dumps({'transcript':{'uid':1001,'sentenceId':1,'text':'stale video','isFinal':True}}).encode())
   self.assertNotEqual(s.source,'stale video')
  finally:task.cancel();await asyncio.gather(task,return_exceptions=True)
