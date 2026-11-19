import asyncio
import logging
import struct
import time
import unittest
from anyvoice.audio_queue import AudioQueue
from anyvoice.parallel_voice import ParallelVoice
from anyvoice.providers import ProviderError

class ParallelVoiceTests(unittest.IsolatedAsyncioTestCase):
 async def exercise(self,fail_second=False):
  gate=asyncio.Event();two_finished=asyncio.Event();started=[];finished=[];errors=[]
  class Client:
   async def synthesize(self,text,on_pcm,on_speech):
    started.append(int(text));number=int(text)
    if number==1:await gate.wait()
    if number==2 and fail_second:
     two_finished.set();raise ProviderError('test failure')
    on_speech();on_pcm(bytes([number,0])*2400)
    finished.append(number)
    if number==2:two_finished.set()
  audio=AudioQueue();phrases=asyncio.Queue();count=[]
  parallel=ParallelVoice([Client(),Client()],audio,errors.append,lambda:count.append(True),logging.getLogger('test'))
  for number in [1,2,3]:phrases.put_nowait((str(number),time.monotonic()))
  task=asyncio.create_task(parallel.run(phrases))
  try:
   await asyncio.wait_for(two_finished.wait(),1);await asyncio.sleep(.02)
   self.assertEqual(parallel.next_number,4) # Numbers assigned before generation finishes.
   self.assertFalse(audio.clips);self.assertFalse(errors) # 002 cannot jump ahead of 001.
   gate.set()
   for _ in range(100):
    if errors or parallel.next_release==4:break
    await asyncio.sleep(.01)
   rows=list(audio.clips.values())
   if fail_second:
    self.assertEqual([r['chunk_sequence'] for r in rows],[1,1,1])
    self.assertEqual(len(errors),1);self.assertIn('002',errors[0])
   else:
    self.assertEqual(finished[0],2)
    self.assertEqual([r['chunk_sequence'] for r in rows],[1]*3+[2]*3+[3]*3)
    self.assertEqual([r['part_sequence'] for r in rows],[1,2,3]*3)
    self.assertEqual(b''.join(r['path'].read_bytes() for r in rows),b'\x01\x00'*2400+b'\x02\x00'*2400+b'\x03\x00'*2400)
    self.assertEqual([r['sequence'] for r in rows],list(range(1,10)))
  finally:
   task.cancel();await asyncio.gather(task,return_exceptions=True);audio.close()
  self.assertFalse(parallel.directory.exists())
 async def test_out_of_order_completion_releases_exact_original_order(self):await self.exercise()
 async def test_later_failure_does_not_interrupt_earlier_chunk_or_skip_hole(self):await self.exercise(True)
 async def test_window_bounds_generation_without_discarding_chunks(self):
  gates=[asyncio.Event() for _ in range(3)];started=[]
  class Client:
   async def synthesize(self,text,on_pcm,on_speech):
    n=int(text);started.append(n);await gates[n-1].wait();on_pcm(b'\x01\x00'*800)
  audio=AudioQueue();phrases=asyncio.Queue()
  parallel=ParallelVoice([Client(),Client()],audio,lambda e:None,lambda:None,logging.getLogger('test'),window=2)
  for n in [1,2,3]:phrases.put_nowait((str(n),0))
  task=asyncio.create_task(parallel.run(phrases))
  try:
   await asyncio.sleep(.03);self.assertEqual(started,[1,2]);self.assertEqual(parallel.next_number,3)
   gates[0].set();await asyncio.sleep(.03);self.assertEqual(started,[1,2,3])
  finally:task.cancel();await asyncio.gather(task,return_exceptions=True);audio.close()
 async def test_numbered_wire_protocol_preserves_chunk_and_part(self):
  audio=AudioQueue()
  try:
   audio.append(b'\x01\x00'*800,1,1);audio.append(b'\x02\x00'*800,2,1)
   stream=audio.packets(0,lambda:True,numbered=True)
   first=await anext(stream);second=await anext(stream)
   self.assertEqual(struct.unpack('!IIII',first[:16]),(1,1,1,1600))
   self.assertEqual(struct.unpack('!IIII',second[:16]),(2,2,1,1600))
   await stream.aclose()
  finally:audio.close()

if __name__=='__main__':unittest.main()
