import asyncio
import json
import logging
import struct
import unittest
from unittest.mock import patch
from types import SimpleNamespace
from anyvoice.session import Pipeline, Session
from anyvoice.parallel_voice import ParallelVoice
from anyvoice.audio_queue import AudioQueue
from test_pipeline import configured, caption

class LanguageRoutingTests(unittest.IsolatedAsyncioTestCase):
    def pipeline(self):
        session = Session(source_language="auto",source_languages=("en-IN","hi-IN","ta-IN","te-IN"))
        pipeline = Pipeline(configured(),session,transport=SimpleNamespace())
        self.addCleanup(session.audio.close)
        return pipeline,session

    def source(self,p,lang,identity,offset,final=True,text="speech"):
        p.receive_caption(json.dumps({"transcript":{"uid":1001,"language":lang,
            "sentenceId":identity,"offset":offset,"isFinal":final,"text":text}}).encode())

    def confirm(self,p,lang,identity,offset):
        with patch('anyvoice.session.time.monotonic',return_value=1000):self.source(p,lang,identity,offset)
        with patch('anyvoice.session.time.monotonic',return_value=1001):self.source(p,lang,identity+1,offset+1)
        with patch('anyvoice.session.time.monotonic',return_value=1002):self.source(p,lang,identity+2,offset+2)

    async def test_telugu_original_and_return_to_translation_without_stale_sentences(self):
        p,s=self.pipeline()
        self.assertEqual(s.route_mode,"detecting")
        self.confirm(p,"te-IN",1,100)
        p.receive_caption(json.dumps(caption(identity=1)).encode())
        self.assertEqual(s.route_mode,"original");self.assertTrue(p.phrases.empty())
        self.confirm(p,"en-IN",2,200)
        p.receive_caption(json.dumps(caption(identity=2)).encode())
        phrase=p.phrases.get_nowait()
        self.assertEqual(s.route_mode,"translated");self.assertEqual(phrase[2],2)
        self.confirm(p,"te-IN",4,300)
        self.assertTrue(p.current_epoch(phrase[2])) # Accepted speech can finish after a confirmed route change.
        self.source(p,"en-IN",2,200) # late old source must not switch back
        p.receive_caption(json.dumps(caption(identity=2,text="additional old translation")).encode())
        self.assertEqual(s.route_mode,"original");self.assertTrue(p.phrases.empty())
        self.confirm(p,"hi-IN",6,400)
        p.receive_caption(json.dumps(caption(identity=6)).encode())
        self.assertEqual(s.detected_language,"hi-IN");self.assertEqual(p.phrases.get_nowait()[2],4)

    async def test_translation_before_source_is_held_and_telugu_is_suppressed(self):
        p,s=self.pipeline()
        p.receive_caption(json.dumps(caption(identity=1)).encode())
        self.assertTrue(p.phrases.empty())
        self.source(p,"en-IN",1,100,False)
        self.assertEqual(s.route_mode,"detecting")
        self.source(p,"en-IN",1,100)
        self.assertFalse(p.phrases.empty());p.phrases.get_nowait()
        p.receive_caption(json.dumps(caption(identity=2)).encode())
        self.confirm(p,"te-IN",2,200)
        self.assertTrue(p.phrases.empty());self.assertEqual(s.route_mode,"original")

    async def test_non_target_language_flapping_keeps_accepted_generation_and_epoch(self):
        p,s=self.pipeline();self.source(p,'hi-IN',1,100)
        epoch=s.route_epoch
        for index,language in enumerate(('ta-IN','en-IN','ta-IN','hi-IN'),2):
            self.source(p,language,index,index*100)
            p.receive_caption(json.dumps(caption(identity=index)).encode())
        self.assertEqual(s.route_epoch,epoch)
        self.assertEqual(p.phrases.qsize(),4)
        self.assertTrue(all(p.phrases.get_nowait()[2]==epoch for _ in range(4)))
        # One target-language misclassification cannot switch playback or invalidate generation.
        self.source(p,'te-IN',20,2000)
        self.assertEqual(s.route_mode,'translated');self.assertTrue(p.current_epoch(epoch))

    async def test_epoch_wire_header_keeps_transport_sequence_across_language_switch(self):
        queue=AudioQueue();self.addCleanup(queue.close)
        queue.append(b"01",1,1,1);queue.append(b"23",5,1,3)
        stream=queue.packets(0,lambda:True,routed=True)
        self.assertEqual(struct.unpack("!IIIII",(await anext(stream))[:20]),(1,1,1,1,2))
        self.assertEqual(struct.unpack("!IIIII",(await anext(stream))[:20]),(2,5,1,3,2))
        await stream.aclose()

    async def test_initial_target_misclassification_cannot_unmute_source(self):
        p,s=self.pipeline()
        self.source(p,'te-IN',1,100)
        self.assertEqual(s.route_mode,'detecting')
        self.source(p,'hi-IN',2,200)
        self.assertEqual(s.route_mode,'translated')
        p.receive_caption(json.dumps(caption(identity=2)).encode())
        self.assertEqual(p.phrases.qsize(),1)

    async def test_intervening_hindi_resets_target_confirmation(self):
        p,s=self.pipeline();self.source(p,'hi-IN',1,100)
        with patch('anyvoice.session.time.monotonic',return_value=1000):self.source(p,'te-IN',2,200)
        with patch('anyvoice.session.time.monotonic',return_value=1001):self.source(p,'hi-IN',3,300)
        with patch('anyvoice.session.time.monotonic',return_value=1002):self.source(p,'te-IN',4,400)
        with patch('anyvoice.session.time.monotonic',return_value=1003):self.source(p,'te-IN',5,500)
        self.assertEqual(s.route_mode,'translated')
        with patch('anyvoice.session.time.monotonic',return_value=1004):self.source(p,'te-IN',6,600)
        self.assertEqual(s.route_mode,'original')
        self.source(p,'hi-IN',7,700)
        self.assertEqual(s.route_mode,'translated')

    async def test_partial_and_duplicate_segments_cannot_confirm_original(self):
        p,s=self.pipeline()
        with patch('anyvoice.session.time.monotonic',return_value=1000):self.source(p,'te-IN',1,100)
        for i in range(1,5):
            with patch('anyvoice.session.time.monotonic',return_value=1000+i):
                self.source(p,'te-IN',1,100,text='changed '+str(i))
        self.assertEqual(s.route_mode,'detecting')
        self.source(p,'te-IN',2,200,False);self.source(p,'te-IN',3,300,False)
        self.assertEqual(s.route_mode,'detecting')

    async def test_fixed_hindi_source_ignores_wrong_detector_labels(self):
        s=Session(source_language='hi-IN',source_languages=('hi-IN',))
        p=Pipeline(configured(),s,transport=SimpleNamespace());self.addCleanup(s.audio.close)
        self.source(p,'te-IN',1,100)
        p.receive_caption(json.dumps(caption(identity=1)).encode())
        self.assertEqual(s.route_mode,'translated');self.assertEqual(s.detected_language,'hi-IN')
        self.assertEqual(p.phrases.qsize(),1)

    async def test_old_active_generation_cannot_publish_after_language_switch(self):
        epoch=1;started=asyncio.Event();finish=asyncio.Event();errors=[]
        class Client:
            async def synthesize(self,text,pcm,first):
                if text=="old":
                    started.set();await finish.wait()
                pcm(b"01"*800)
        queue=AudioQueue();self.addCleanup(queue.close);phrases=asyncio.Queue()
        parallel=ParallelVoice([Client(),Client()],queue,errors.append,lambda:None,
            logging.getLogger("test"),is_current=lambda e:e==epoch)
        task=asyncio.create_task(parallel.run(phrases))
        try:
            phrases.put_nowait(("old",0,1));await asyncio.wait_for(started.wait(),1)
            epoch=3;parallel.release_ready();phrases.put_nowait(("new",0,3))
            await asyncio.sleep(.03);finish.set();await asyncio.sleep(.03)
            self.assertFalse(errors)
            self.assertEqual([r["epoch"] for r in queue.clips.values()],[3])
            self.assertEqual([r["chunk_sequence"] for r in queue.clips.values()],[2])
        finally:
            task.cancel();await asyncio.gather(task,return_exceptions=True)
