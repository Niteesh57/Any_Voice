import asyncio
from contextlib import asynccontextmanager
import hmac
import time

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from .config import Settings
from .rtc import native_available
from .session import Pipeline, Session, token_for

load_dotenv()


class StartRequest(BaseModel):
    source_language: str = "en-IN"
    target_language: str = "te-IN"


def create_app(settings=None, pipeline_factory=Pipeline, native_check=native_available):
    settings = settings or Settings.from_env()
    sessions = {}
    creating = asyncio.Lock()

    async def auth(authorization: str = Header(default="")):
        if not settings.access_token or not hmac.compare_digest(authorization, "Bearer " + settings.access_token):
            raise HTTPException(401, "Invalid backend access token")

    @asynccontextmanager
    async def lifespan(app):
        yield
        for session in sessions.values():
            session.stopped.set()
            if session.task:
                session.task.cancel()
        await asyncio.gather(*(s.task for s in sessions.values() if s.task), return_exceptions=True)

    app = FastAPI(title="Any Voice", version="0.3", lifespan=lifespan)

    @app.get("/health")
    async def health():
        return {"service": "Any Voice", "version": "0.3"}

    @app.post("/v1/agent/idle/chat/completions", dependencies=[Depends(auth)])
    async def idle_callback():
        # Agora requires an LLM callback in the agent configuration. Manual turns and
        # a silent control UID mean it isn't used. If invoked, generate NO words.
        async def empty_completion():
            yield 'data: {"id":"idle","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}]}\n\n'
            yield "data: [DONE]\n\n"
        return StreamingResponse(empty_completion(), media_type="text/event-stream")

    @app.get("/v1/capabilities", dependencies=[Depends(auth)])
    async def capabilities():
        missing = settings.missing()
        native = native_check()
        return {"configured": not missing and native, "missing": missing,
                "worker_available": native, "source_language": "en-IN", "target_language": "te-IN",
                "message": "Ready" if not missing and native else "Configure provider credentials and run the Linux worker"}

    @app.post("/v1/sessions", dependencies=[Depends(auth)])
    async def start(request: StartRequest):
        if (request.source_language, request.target_language) != ("en-IN", "te-IN"):
            raise HTTPException(422, "Phase 1 supports English to Telugu only")
        try:
            settings.validate()
        except ValueError as exc:
            raise HTTPException(503, str(exc)) from exc
        if not native_check():
            raise HTTPException(503, "Agora worker requires the Linux runtime. Start the Docker backend.")
        async with creating:
            for sid, old in list(sessions.items()):
                if old.stopped.is_set() and time.monotonic() - old.touched > 60:
                    del sessions[sid]
            if any(s.task and not s.task.done() for s in sessions.values()):
                raise HTTPException(409, "A session is already active. Stop it before starting another.")
            session = Session()
            sessions[session.id] = session
            try:
                pipeline = pipeline_factory(settings, session)
                session.task = asyncio.create_task(pipeline.run())
                await asyncio.wait_for(session.ready.wait(), timeout=75)
                if session.state != "ready":
                    raise HTTPException(502, session.message)
                return {**session.public(), "app_id": settings.agora_app_id,
                        "channel": session.channel, "uid": session.source_uid, "worker_uid": session.worker_uid,
                        "token": token_for(settings, session, session.source_uid),
                        "max_seconds": settings.max_seconds}
            except BaseException as exc:
                session.stopped.set()
                if session.task:
                    session.task.cancel()
                    await asyncio.gather(session.task, return_exceptions=True)
                if isinstance(exc, asyncio.TimeoutError):
                    raise HTTPException(504, "Voice services took too long to connect") from exc
                raise

    @app.get("/v1/sessions/{sid}", dependencies=[Depends(auth)])
    async def status(sid: str):
        session = sessions.get(sid)
        if not session:
            raise HTTPException(404, "Session not found")
        session.touched = time.monotonic()
        return session.public()

    @app.post("/v1/sessions/{sid}/stop", dependencies=[Depends(auth)])
    async def stop(sid: str):
        session = sessions.get(sid)
        if not session:
            raise HTTPException(404, "Session not found")
        session.stopped.set()
        if session.task and not session.task.done():
            session.task.cancel()
            await asyncio.gather(session.task, return_exceptions=True)
        session.source = session.translated = ""
        return session.public()

    app.state.sessions = sessions
    return app


app = create_app()
