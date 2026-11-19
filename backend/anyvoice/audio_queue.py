"""Completed PCM phrases, retained until the phone acknowledges ordered delivery."""
from pathlib import Path
import tempfile
import shutil
import asyncio
import struct

class AudioQueue:
    sample_rate = 16000
    def __init__(self):
        self.directory = Path(tempfile.mkdtemp(prefix='anyvoice-queue-'))
        self.clips = {}
        self.next_sequence = 1
        self.acknowledged = 0
        self.total_bytes = 0
        self.changed = asyncio.Event()
        self.closed = False

    def append(self, pcm):
        if not pcm or len(pcm) % 2: raise ValueError('Incomplete PCM audio')
        # 15-minute sessions normally produce <30 MB; protect disk without dropping queued speech.
        if self.total_bytes + len(pcm) > 128 * 1024 * 1024: raise ValueError('Session audio storage limit reached')
        seq = self.next_sequence
        path = self.directory / ('%08d.pcm' % seq)
        temporary = path.with_suffix('.part')
        temporary.write_bytes(pcm); temporary.replace(path)
        self.clips[seq] = {'sequence': seq, 'bytes': len(pcm), 'duration_ms': len(pcm) * 1000 // 32000, 'path': path}
        self.next_sequence += 1; self.total_bytes += len(pcm)
        self.changed.set()
        return seq

    async def packets(self, after, finished):
        while not self.closed:
            self.changed.clear()
            row = self.clips.get(after + 1)
            if row:
                payload = row['path'].read_bytes()
                after += 1
                yield struct.pack('!II', after, len(payload)) + payload
                continue
            if finished(): return
            try:
                await asyncio.wait_for(self.changed.wait(), 3)
            except asyncio.TimeoutError:
                yield struct.pack('!II', 0, 0)  # keepalive, never audio

    def manifest(self, after):
        return {'sample_rate': self.sample_rate, 'clips': [
            {k:v for k,v in row.items() if k != 'path'} for seq,row in self.clips.items() if seq > after][:6]}

    def acknowledge(self, seq):
        if seq <= self.acknowledged: return
        if seq not in self.clips: raise ValueError('Audio sequence is not available')
        # The phone has one ordered player; acknowledging N also confirms all earlier clips.
        for earlier in list(self.clips):
            if earlier > seq: break
            row = self.clips.pop(earlier); row['path'].unlink(missing_ok=True)
            self.total_bytes -= row['bytes']
        self.acknowledged = seq

    def close(self):
        self.closed = True; self.changed.set()
        self.clips.clear(); shutil.rmtree(self.directory, ignore_errors=True)
