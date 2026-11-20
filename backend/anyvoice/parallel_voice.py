"""Two generation slots, one ordered release stream. Never publish across a missing chunk."""
import asyncio
from dataclasses import dataclass
from pathlib import Path
import tempfile
import shutil
import time
from .captions import speech_chunks
from .providers import ProviderError

@dataclass
class Chunk:
    number: int
    text: str
    queued: float
    path: Path
    epoch: int = 0
    written: int = 0
    released: int = 0
    part: int = 0
    done: bool = False
    error: str | None = None
    voice: dict | None = None
    conversation: bool = False

class ParallelVoice:
    def __init__(self, clients, audio, on_error, on_generated, log, window=6, is_current=None,before_generate=None):
        self.before_generate=before_generate
        self.is_current = is_current or (lambda epoch: True)
        self.clients, self.audio = clients, audio
        self.on_error, self.on_generated, self.log = on_error, on_generated, log
        self.directory = Path(tempfile.mkdtemp(prefix='anyvoice-parallel-'))
        self.window = asyncio.Semaphore(window)
        self.jobs = asyncio.Queue()
        self.chunks = {}
        self.next_number = 1
        self.next_release = 1
        self.failed = False
        self.tasks = []
        self.closed = False

    async def run(self, phrases):
        self.tasks = [asyncio.create_task(self.dispatch(phrases))]
        self.tasks += [asyncio.create_task(self.worker(index, client)) for index,client in enumerate(self.clients)]
        try:
            await asyncio.gather(*self.tasks)
        finally:
            for task in self.tasks: task.cancel()
            await asyncio.gather(*self.tasks, return_exceptions=True)
            self.closed = True
            self.chunks.clear()
            shutil.rmtree(self.directory, ignore_errors=True)

    async def dispatch(self, phrases):
        while True:
            item = await phrases.get()
            text, queued = item[:2]
            epoch = item[2] if len(item)>2 else 0
            for text_part in speech_chunks(text, limit=512):
                await self.window.acquire()
                if not self.is_current(epoch):
                    self.window.release()
                    break
                number = self.next_number
                self.next_number += 1
                chunk = Chunk(number, text_part, queued, self.directory / ('%08d.pcm' % number),epoch=epoch,voice=item[3] if len(item)>3 else None,conversation=bool(item[4]) if len(item)>4 else False)
                chunk.path.touch()
                self.chunks[number] = chunk
                self.log.info('chunk_assigned chunk=%03d bytes=%d',number,len(text_part.encode('utf-8')))
                self.jobs.put_nowait(chunk)

    async def worker(self, slot, client):
        while True:
            chunk = await self.jobs.get()
            if not self.is_current(chunk.epoch):
                chunk.done = True
                self.release_ready()
                continue
            started = time.monotonic()
            first_audio = False
            self.log.info('chunk_start slot=%d chunk=%03d queue_ms=%.0f',slot+1,chunk.number,(started-chunk.queued)*1000)
            def pcm(data):
                if self.closed or self.failed or not self.is_current(chunk.epoch): return
                if not data or len(data)%2: raise ProviderError('Invalid PCM from voice generator.')
                if chunk.written+len(data)>16000*2*90: raise ProviderError('Voice chunk exceeded the audio limit.')
                with open(chunk.path,'ab') as out: out.write(data)
                chunk.written += len(data)
                self.release_ready()
            def first():
                nonlocal first_audio
                if not first_audio:
                    first_audio = True
                    self.log.info('chunk_first_audio slot=%d chunk=%03d ms=%.0f',slot+1,chunk.number,(time.monotonic()-started)*1000)
            try:
                if self.before_generate:
                    await self.before_generate(chunk)
                    if chunk.conversation:self.log.info('conversation_turn_wait chunk=%03d ms=%.0f',chunk.number,(time.monotonic()-started)*1000)
                    started=time.monotonic()
                await client.synthesize(chunk.text, pcm, first,**(chunk.voice or {}))
                if not self.is_current(chunk.epoch):
                    chunk.done = True
                    self.release_ready()
                    continue
                if not chunk.written: raise ProviderError('Voice chunk was empty.')
                chunk.done = True
                self.log.info('chunk_generated slot=%d chunk=%03d audio_ms=%d generation_ms=%.0f',
                    slot+1,chunk.number,chunk.written*1000//32000,(time.monotonic()-started)*1000)
                self.on_generated()
                self.release_ready()
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                self.log.error('chunk_failed chunk=%03d type=%s detail=%s',chunk.number,type(exc).__name__,str(exc) if isinstance(exc,ProviderError) else 'Voice generation failed')
                # A later failure must not interrupt an earlier voice still being generated.
                chunk.error = 'Could not generate voice chunk %03d. Earlier audio will finish.' % chunk.number
                chunk.done = True
                self.release_ready()

    def release_ready(self):
        if self.closed or self.failed: return
        while True:
            chunk = self.chunks.get(self.next_release)
            if chunk is None: return
            if not self.is_current(chunk.epoch):
                del self.chunks[chunk.number]
                chunk.path.unlink(missing_ok=True)
                self.next_release += 1
                self.window.release()
                continue
            available = chunk.written - chunk.released
            while available >= 1600 or (chunk.done and available > 0):
                size = min(1600,available)
                with open(chunk.path,'rb') as source:
                    source.seek(chunk.released); data = source.read(size)
                if len(data)!=size: raise ProviderError('Numbered audio buffer is incomplete.')
                chunk.part += 1
                self.audio.append(data, chunk.number, chunk.part, chunk.epoch)
                self.log.debug('chunk_packet chunk=%03d part=%03d sequence=%d',chunk.number,chunk.part,self.audio.next_sequence-1)
                chunk.released += size; available -= size
            if not chunk.done: return
            if chunk.error:
                self.failed = True
                self.on_error(chunk.error)
                return
            self.log.info('chunk_released chunk=%03d packets=%d',chunk.number,chunk.part)
            self.audio.complete_chunk(chunk.number)
            del self.chunks[chunk.number]
            chunk.path.unlink(missing_ok=True)
            self.next_release += 1
            self.window.release()
