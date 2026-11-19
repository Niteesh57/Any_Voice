import asyncio
import json
import logging
import struct
import unittest
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

    async def test_telugu_original_and_return_to_translation_without_stale_sentences(self):
        p,s=self.pipeline()
        self.assertEqual(s.route_mode,"detecting")
        self.source(p,"te-IN",1,100)
        p.receive_caption(json.dumps(caption(identity=1)).encode())
        self.assertEqual(s.route_mode,"original");self.assertTrue(p.phrases.empty())
        self.source(p,"en-IN",2,200)
        p.receive_caption(json.dumps(caption(identity=2)).encode())
        phrase=p.phrases.get_nowait()
        self.assertEqual(s.route_mode,"translated");self.assertEqual(phrase[2],2)
        self.source(p,"te-IN",3,300)
        self.assertFalse(p.current_epoch(phrase[2]))
        self.source(p,"en-IN",2,200) # late old source must not switch back
        p.receive_caption(json.dumps(caption(identity=2,text="additional old translation")).encode())
        self.assertEqual(s.route_mode,"original");self.assertTrue(p.phrases.empty())
        self.source(p,"hi-IN",4,400)
        p.receive_caption(json.dumps(caption(identity=4)).encode())
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
        self.source(p,"te-IN",2,200)
        self.assertTrue(p.phrases.empty());self.assertEqual(s.route_mode,"original")

    async def test_epoch_wire_header_keeps_transport_sequence_across_language_switch(self):
        queue=AudioQueue();self.addCleanup(queue.close)
        queue.append(b"01",1,1,1);queue.append(b"23",5,1,3)
        stream=queue.packets(0,lambda:True,routed=True)
        self.assertEqual(struct.unpack("!IIIII",(await anext(stream))[:20]),(1,1,1,1,2))
        self.assertEqual(struct.unpack("!IIIII",(await anext(stream))[:20]),(2,5,1,3,2))
        await stream.aclose()

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
