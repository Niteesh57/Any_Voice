"""Typed tool contracts shared by the audio planner and HTTP task controls."""
import asyncio
import time
from datetime import date
from typing import Literal
from pydantic import BaseModel,ConfigDict,Field

class Args(BaseModel):model_config=ConfigDict(extra='forbid')
class TaskCreate(Args):
 title:str=Field(min_length=1,max_length=200)
 due_date:date|None=None
class TaskId(Args):task_id:str=Field(pattern='^[a-f0-9]{32}$')
class TaskUpdate(TaskId):
 title:str|None=Field(default=None,min_length=1,max_length=200)
 due_date:date|None=None
class TaskList(Args):status:Literal['open','done','all']='all'
class Remember(Args):
 key:str=Field(min_length=1,max_length=80)
 text:str=Field(min_length=1,max_length=1000)
class Search(Args):query:str=Field(default='',max_length=200)
class Forget(Args):memory_id:str=Field(pattern='^[a-f0-9]{32}$')
class Daily(Args):day:date|None=None
class OpenApp(Args):app:str=Field(min_length=1,max_length=200)
class YouTube(Args):query:str=Field(min_length=1,max_length=200)
MODELS={'task_create':TaskCreate,'task_update':TaskUpdate,'task_complete':TaskId,'task_reopen':TaskId,'task_list':TaskList,
 'memory_save':Remember,'memory_search':Search,'memory_forget':Forget,'daily_review':Daily,'open_app':OpenApp,'search_youtube':YouTube}
DESCRIPTIONS={
 'task_create':'Save an explicitly requested task. due_date is an absolute local YYYY-MM-DD date, not a timed reminder.',
 'task_update':'Edit an existing task by its ID. Omitted fields remain unchanged.',
 'task_complete':'Mark the unambiguously identified task complete. If several could mean that, ask first.',
 'task_reopen':'Reopen a completed task by ID.',
 'task_list':'Retrieve saved tasks. Use IDs from the result for later edits.',
 'memory_save':'Remember an explicitly requested fact/preference. Reuse its key to correct it.',
 'memory_search':'Search personal memories and past conversation for relevant facts.',
 'memory_forget':'Delete the identified personal memory; clears conversational copies too.',
 'daily_review':'Retrieve recorded task events and open work for a local day. Never invent activities.',
 'open_app':'Ask this phone to open an installed app using an app ID from the supplied capabilities.',
 'search_youtube':'Ask the phone to open YouTube search results, with browser fallback. Does not select/play a video.'}

def definitions(device_apps):
 names=list(MODELS) if device_apps else [n for n in MODELS if n not in ('open_app','search_youtube')]
 return [{'type':'function','function':{'name':n,'description':DESCRIPTIONS[n],'parameters':MODELS[n].model_json_schema()}} for n in names]

def validate(name,args):
 if name not in MODELS:raise ValueError('Unknown tool')
 result=MODELS[name].model_validate(args)
 data=result.model_dump(mode='json',exclude_unset=True)
 if name=='task_update' and data.get('title','not-null') is None:raise ValueError('A task title cannot be empty')
 for key in ('title','key','text','query','app'):
  if key in data and not data[key].strip():raise ValueError('Provide non-empty '+key)
 return data

class ToolExecutor:
 def __init__(self,store,owner,zone,apps):self.store=store;self.owner=owner;self.zone=zone;self.apps=apps
 async def execute(self,identity,name,args):
  try:
   data=validate(name,args)
   import hashlib,json
   identity=identity.split(":")[0]+":"+str(int(hashlib.sha256((str(name)+json.dumps(data,sort_keys=True)).encode()).hexdigest()[:12],16))
   if name=='daily_review':
    from datetime import datetime
    from zoneinfo import ZoneInfo
    data={'date':data.get('day') or datetime.now(ZoneInfo(self.zone)).date().isoformat(),'timezone':self.zone}
   if name in ('open_app','search_youtube'):
    if not self.apps:return {'status':'unavailable','reason':'Phone actions are not enabled for this client'}
    if name=='open_app' and data['app'] not in self.apps:return {'status':'unavailable','reason':'Use a supported app ID from this phone capability list'}
    self.store.queue_action(self.owner,identity,name,data,time.time()+25)
    deadline=time.monotonic()+26
    while time.monotonic()<deadline:
     result=self.store.action_result(self.owner,identity)
     if result is not None:return result
     await asyncio.sleep(.15)
    return {'status':'expired','reason':'The phone did not acknowledge this action; do not claim it opened'}
   return self.store.execute(self.owner,identity,name,data)
  except (ValueError,TypeError) as exc:return {'status':'error','reason':str(exc)[:300]}
