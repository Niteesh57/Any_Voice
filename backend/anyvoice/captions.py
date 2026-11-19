"""Agora STT v7 JSON protocol: gzip, source UID filtering and finalized segments."""
import gzip
import io
import json


def decode_message(data: bytes, source_uid: int):
    if len(data) > 65536:
        raise ValueError("Caption message exceeds limit")
    if data.startswith(b"\x1f\x8b"):
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as stream:
            data = stream.read(65537)
    if len(data) > 65536:
        raise ValueError("Decoded caption message exceeds limit")
    item = json.loads(data.decode("utf-8"))
    transcription = item.get("transcript")
    if transcription and str(transcription.get("uid")) == str(source_uid):
        rows = transcription.get("results", [])
        text = "".join(row.get("text", "") for row in rows) if rows else transcription.get("text", "")
        if text:
            yield {"kind": "source", "text": text,
                   "language": transcription.get("language"),
                   "id": str(transcription.get("sentenceId") or transcription.get("textTs") or ""),
                   "offset": transcription.get("offset"),
                   "final": transcription.get("isFinal") is True or any(r.get("isFinal") is True for r in rows)}
    translation = item.get("translation")
    if not translation or str(translation.get("uid")) != str(source_uid):
        return
    # Distinct occurrences of identical sentences have distinct IDs, and must both play.
    identity = translation.get("sentenceId") or translation.get("textTs")
    if identity is None:
        raise ValueError("Translation caption has no segment identity")
    rows = translation.get("results")
    if rows is None:
        legacy = translation.get("results0")
        rows = [legacy] if legacy else []
    final_parts = []
    for row in rows:
        if row.get("language") == "te-IN" and row.get("isFinal") is True:
            texts = row.get("texts", [])
            if not isinstance(texts, list) or any(not isinstance(text, str) for text in texts):
                raise ValueError("Invalid translated caption texts")
            final_parts.extend(texts)
    if final_parts:
        offset, duration = translation.get("offset"), translation.get("duration")
        end_ms = offset + duration if isinstance(offset, (int, float)) and isinstance(duration, (int, float)) else None
        yield {"kind": "translation", "id": str(identity), "text": "".join(final_parts), "speech_end_ms": end_ms}


class FinalizedCaptions:
    def __init__(self):
        self.prefixes = {}

    def accept(self, identity, text):
        previous = self.prefixes.get(identity, "")
        if text == previous or previous.startswith(text):
            return ""
        if not text.startswith(previous):
            raise ValueError("Previously finalized caption changed")
        if len(self.prefixes) >= 2000 and identity not in self.prefixes:
            raise ValueError("Caption count limit reached; start a new session")
        self.prefixes[identity] = text
        return text[len(previous):]


def speech_chunks(text, limit=512):
    """Keep every Unicode code point; prefer whitespace/punctuation boundaries under 512 UTF-8 bytes."""
    chunk = ""
    for char in text:
        if len(char.encode("utf-8")) > limit:
            raise ValueError("Speech byte limit is smaller than a Unicode character")
        while len((chunk + char).encode("utf-8")) > limit:
            cut = max((i + 1 for i, c in enumerate(chunk) if c.isspace() or c in ".!?।,;"), default=0)
            if cut:
                yield chunk[:cut]
                chunk = chunk[cut:]
            else:
                yield chunk
                chunk = ""
        chunk += char
    if chunk:
        yield chunk
