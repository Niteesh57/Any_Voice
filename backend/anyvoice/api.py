import asyncio
from contextlib import asynccontextmanager
import hmac
import time

from dotenv import load_dotenv
from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse, FileResponse, Response
from pydantic import BaseModel, Field, field_validator
from typing import Literal

from .config import Settings
from .voices import VoiceCatalog, agora_locale
from .providers import ProviderError
from .rtc import native_available
from .session import Pipeline, Session, token_for
from .bubble import BubblePipeline
from .qwen_audio import QwenAudio
from .memory import MemoryStore
from .tools import validate as validate_tool
from zoneinfo import ZoneInfo,ZoneInfoNotFoundError

load_dotenv()


class StartRequest(BaseModel):
    mode: Literal["translation", "bubble"] = "translation"
    timezone: str = Field(default="Asia/Kolkata",max_length=80)
    screen_tools: bool = False
    device_apps: dict[str,str] = Field(default_factory=dict,max_length=512)
    context: str = Field(default="", max_length=8000)
    source_language: str = "auto"
    target_language: str = "te-IN"
    voice_id: str | None = None
    voice_provider: Literal["murf-direct", "agora-engine"] | None = None


class DeviceResult(BaseModel):
    status: Literal["launch_requested","browser_requested","unavailable","failed","needs_unlock","execution_unknown","observed","scrolled","unchanged","end_reached","restricted","cancelled","screen_changed"]
    detail: str = Field(default="",max_length=300)
    package_name: str = Field(default="",max_length=200)
    text: str = Field(default="",max_length=18000)
    can_scroll: bool = False
    sensitive: bool = False
    truncated: bool = False
    content_hash: str = Field(default="",max_length=80)
    image_data: str = Field(default="",max_length=400000)
    captured_at: int = 0
    @field_validator('image_data')
    @classmethod
    def valid_jpeg(cls,value):
        import base64
        if value:
            try:raw=base64.b64decode(value,validate=True)
            except ValueError:raise ValueError('Invalid screenshot encoding')
            if not raw.startswith(b'\xff\xd8'):raise ValueError('Screenshot must be JPEG')
        return value

class ManualTool(BaseModel):
    request_id: str = Field(min_length=8,max_length=100)
    name: Literal["task_create","task_update","task_complete","task_reopen","task_list","memory_save","memory_search","memory_forget"]
    arguments: dict

def create_app(settings=None, pipeline_factory=Pipeline, native_check=native_available, bubble_factory=BubblePipeline):
    settings = settings or Settings.from_env()
    sessions = {}
    memory = MemoryStore(settings.memory_path)
    catalog = VoiceCatalog(settings)
    creating = asyncio.Lock()

    async def auth(authorization: str = Header(default="")):
        if not settings.access_token or not hmac.compare_digest(authorization, "Bearer " + settings.access_token):
            raise HTTPException(401, "Invalid backend access token")

    async def device(x_anyvoice_device: str = Header(default=""),x_anyvoice_device_key: str = Header(default="")):
        if not x_anyvoice_device and not x_anyvoice_device_key:return ""
        try:return memory.identity(x_anyvoice_device,x_anyvoice_device_key)
        except ValueError as exc:raise HTTPException(401,str(exc)) from exc

    def scoped(sid,owner):
        session=sessions.get(sid)
        if not session or session.owner!=owner:raise HTTPException(404,"Session not found")
        return session

    @asynccontextmanager
    async def lifespan(app):
        yield
        for session in sessions.values():
            session.stopped.set()
            if session.task:
                session.task.cancel()
        await asyncio.gather(*(s.task for s in sessions.values() if s.task), return_exceptions=True)
        for session in sessions.values():
            if session.audio: session.audio.close()

    app = FastAPI(title="Any Voice", version="0.17", lifespan=lifespan)

    @app.get("/health")
    async def health():
        return {"service": "Any Voice", "version": "0.17"}

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
                "memory_tools": True,
                "bubble_configured": bool(settings.dashscope_key and settings.bubble_llm_url and settings.bubble_llm_model),
                "worker_available": native, "source_language": "auto", "source_languages": settings.auto_languages, "target_selection": "voice-library",
                "message": "Ready" if not missing and native else "Configure provider credentials and run the Linux worker"}

    @app.get("/v1/voices", dependencies=[Depends(auth)])
    async def voices():
        try: rows=await catalog.get()
        except ProviderError as exc: raise HTTPException(503,str(exc)) from exc
        return {"voices":rows,"languages":[{**language,
            "detection":list(settings.auto_languages if language["agora_locale"] in settings.auto_languages else (*settings.auto_languages[:3],language["agora_locale"]))}
            for language in catalog.languages]}

    @app.get("/v1/voice-preview", dependencies=[Depends(auth)])
    async def voice_preview(voice_id: str, language: str):
        async with creating:
            if any(s.task and not s.task.done() for s in sessions.values()):
                raise HTTPException(409,"Stop listening before previewing a voice")
            if not agora_locale(language): raise HTTPException(422,"Select a supported language")
            try: audio=await catalog.preview(voice_id,language)
            except ValueError as exc: raise HTTPException(422,str(exc)) from exc
            except (ProviderError,asyncio.TimeoutError) as exc: raise HTTPException(502,"Voice preview unavailable. Try again.") from exc
            return Response(audio,media_type="audio/wav",headers={"Cache-Control":"private, max-age=600"})

    @app.get("/v1/bubble/diagnostics", dependencies=[Depends(auth)])
    async def bubble_diagnostics(owner: str = Depends(device)):
        session=next((s for s in sessions.values() if s.owner==owner and s.mode=="bubble" and s.task and not s.task.done()),None)
        if not session:return {"active":False}
        return {"active":True,"state":session.state,"phase":session.bubble_state,"wake_sequence":session.wake_sequence,
            "input_frames":session.input_frames,"input_rms":session.input_rms,"max_input_rms":session.max_input_rms,
            "screen_tools":session.screen_tools,"screen_research":session.screen_research,
            "voice_frames":session.audio_frames,"last_audio_sequence":session.audio.next_sequence-1 if session.audio else 0,
            "acknowledged":session.audio.acknowledged if session.audio else 0,"phrases":session.phrases}

    @app.post("/v1/sessions", dependencies=[Depends(auth)])
    async def start(request: StartRequest, owner: str = Depends(device)):
        try:ZoneInfo(request.timezone)
        except (ZoneInfoNotFoundError,ValueError):raise HTTPException(422,"Choose a valid timezone")
        if any(len(k)>200 or len(v)>100 for k,v in request.device_apps.items()):raise HTTPException(422,"Invalid app capabilities")
        if request.mode == "bubble" and not (settings.dashscope_key and settings.bubble_llm_url.startswith("https://") and settings.bubble_llm_model):
            raise HTTPException(503,"Configure Bubble Qwen credentials on the backend")
        if request.source_language not in ("auto", "en-IN") or not agora_locale(request.target_language):
            raise HTTPException(422, "Select a supported output language")
        try:
            settings.validate()
        except ValueError as exc:
            raise HTTPException(503, str(exc)) from exc
        if not native_check():
            raise HTTPException(503, "Agora worker requires the Linux runtime. Start the Docker backend.")
        if request.voice_id:
            try: await catalog.validate(request.voice_id,request.target_language)
            except ValueError as exc: raise HTTPException(422,str(exc)) from exc
            except ProviderError as exc: raise HTTPException(503,str(exc)) from exc
        else:
            raise HTTPException(422,"Select a voice for this language")
        async with creating:
            for sid, old in list(sessions.items()):
                if old.stopped.is_set() and time.monotonic() - old.touched > 60:
                    if old.audio: old.audio.close()
                    del sessions[sid]
            if any(s.task and not s.task.done() for s in sessions.values()):
                raise HTTPException(409, "A session is already active. Stop it before starting another.")
            session = Session(owner=owner,screen_tools=request.screen_tools,timezone=request.timezone,device_apps=request.device_apps,memory_store=memory,mode=request.mode, context=request.context, voice_provider=request.voice_provider or settings.voice_provider,
                target_language=agora_locale(request.target_language), voice_locale=request.target_language, voice_id=request.voice_id,
                source_language=request.source_language,
                source_languages=(settings.auto_languages if agora_locale(request.target_language) in settings.auto_languages else (*settings.auto_languages[:3],agora_locale(request.target_language))) if request.source_language == "auto" else ("en-IN",))
            if request.mode == "bubble":
                session.source_languages=("en-IN",)  # Command audio goes directly to Qwen, without an STT bot.
            sessions[session.id] = session
            try:
                pipeline = (bubble_factory if request.mode == "bubble" else pipeline_factory)(settings, session)
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
    async def status(sid: str,owner: str = Depends(device),screen_tools: bool | None = None):
        session = scoped(sid,owner)
        if not session:
            raise HTTPException(404, "Session not found")
        session.touched = time.monotonic()
        if owner and session.mode=="bubble" and screen_tools is not None:session.screen_tools=screen_tools
        return {**session.public(),"device_actions":memory.actions(owner) if owner else []}

    @app.post("/v1/sessions/{sid}/stop", dependencies=[Depends(auth)])
    async def stop(sid: str,owner: str = Depends(device)):
        session = scoped(sid,owner)
        if not session:
            raise HTTPException(404, "Session not found")
        session.stopped.set()
        if session.task and not session.task.done():
            session.task.cancel()
            await asyncio.gather(session.task, return_exceptions=True)
        session.source = session.translated = ""
        if session.audio: session.audio.close()
        return session.public()

    @app.get("/v1/sessions/{sid}/audio", dependencies=[Depends(auth)])
    async def audio_manifest(sid: str, after: int = 0,owner: str = Depends(device)):
        session = scoped(sid,owner)
        if not session or not session.audio:
            raise HTTPException(404, "Audio session not found")
        return session.audio.manifest(max(0, after))

    @app.get("/v1/sessions/{sid}/pcm-stream", dependencies=[Depends(auth)])
    async def pcm_stream(sid: str, after: int = 0,owner: str = Depends(device)):
        session = scoped(sid,owner)
        if not session or not session.audio:
            raise HTTPException(404, "Audio session not found")
        return StreamingResponse(session.audio.packets(max(0, after), lambda: session.stopped.is_set()),
            media_type="application/octet-stream", headers={"Cache-Control":"no-store", "X-Accel-Buffering":"no"})

    @app.get("/v1/sessions/{sid}/pcm-stream-v2", dependencies=[Depends(auth)])
    async def numbered_pcm_stream(sid: str, after: int = 0,owner: str = Depends(device)):
        session = scoped(sid,owner)
        if not session or not session.audio:
            raise HTTPException(404, "Audio session not found")
        return StreamingResponse(session.audio.packets(max(0, after), lambda: session.stopped.is_set(), numbered=True),
            media_type="application/octet-stream", headers={"Cache-Control":"no-store", "X-Accel-Buffering":"no"})

    @app.get("/v1/sessions/{sid}/pcm-stream-v3", dependencies=[Depends(auth)])
    async def routed_pcm_stream(sid: str, after: int = 0,owner: str = Depends(device)):
        session = scoped(sid,owner)
        if not session or not session.audio:
            raise HTTPException(404, "Audio session not found")
        return StreamingResponse(session.audio.packets(max(0, after), lambda: session.stopped.is_set(), routed=True),
            media_type="application/octet-stream", headers={"Cache-Control":"no-store", "X-Accel-Buffering":"no"})

    @app.get("/v1/sessions/{sid}/audio/{sequence}", dependencies=[Depends(auth)])
    async def audio_clip(sid: str, sequence: int,owner: str = Depends(device)):
        session = scoped(sid,owner)
        row = session.audio.clips.get(sequence) if session and session.audio else None
        if not row:
            raise HTTPException(404, "Audio clip not available")
        return FileResponse(row['path'], media_type="application/octet-stream")

    @app.post("/v1/sessions/{sid}/audio/{sequence}/played", dependencies=[Depends(auth)])
    async def played(sid: str, sequence: int,owner: str = Depends(device)):
        session = scoped(sid,owner)
        if not session or not session.audio:
            raise HTTPException(404, "Audio session not found")
        try:
            session.audio.acknowledge(sequence)
        except ValueError as exc:
            raise HTTPException(409, str(exc)) from exc
        return {"acknowledged": session.audio.acknowledged}

    @app.post("/v1/sessions/{sid}/cancel-research",dependencies=[Depends(auth)])
    async def cancel_research(sid: str,owner: str = Depends(device)):
        session=scoped(sid,owner);session.cancel_research=True;memory.clear_question(owner)
        return {"status":"cancelled"}

    @app.get("/v1/me/overview",dependencies=[Depends(auth)])
    async def overview(owner: str = Depends(device)):
        if not owner:raise HTTPException(401,"Device identity required")
        return memory.context(owner)

    @app.post("/v1/me/tools",dependencies=[Depends(auth)])
    async def manual_tool(request: ManualTool,owner: str = Depends(device)):
        if not owner:raise HTTPException(401,"Device identity required")
        try:return memory.execute(owner,request.request_id,request.name,validate_tool(request.name,request.arguments))
        except ValueError as exc:raise HTTPException(422,str(exc)[:300]) from exc

    @app.post("/v1/me/actions/{action_id}/result",dependencies=[Depends(auth)])
    async def device_result(action_id: str,result: DeviceResult,owner: str = Depends(device)):
        if not owner:raise HTTPException(401,"Device identity required")
        try:
            memory.action_result(owner,action_id,result.model_dump())
            return {"status":"received"}
        except ValueError as exc:raise HTTPException(404,str(exc)) from exc

    app.state.memory=memory
    app.state.sessions = sessions
    return app


app = create_app()
