"""Small persistent, installation-scoped store for Bubble. No raw audio is stored."""
import hashlib
import hmac
import json
import sqlite3
import threading
import uuid
from datetime import datetime, timezone
from pathlib import Path


def now():return datetime.now(timezone.utc).isoformat()
def uid():return uuid.uuid4().hex

def encode(value):return json.dumps(value,ensure_ascii=False,separators=(',',':'))

class MemoryStore:
 def __init__(self,path):
  if path!=':memory:':Path(path).parent.mkdir(parents=True,exist_ok=True)
  self.screen_results={}
  self.lock=threading.RLock();self.db=sqlite3.connect(path,check_same_thread=False)
  self.db.row_factory=sqlite3.Row
  self.db.executescript('''
   PRAGMA journal_mode=WAL;
   CREATE TABLE IF NOT EXISTS questions(owner TEXT PRIMARY KEY,id TEXT,payload TEXT,state TEXT,answer TEXT,updated TEXT);
   CREATE TABLE IF NOT EXISTS owners(id TEXT PRIMARY KEY, proof TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,owner TEXT,title TEXT,status TEXT,due_date TEXT,created TEXT,updated TEXT,completed TEXT);
   CREATE INDEX IF NOT EXISTS tasks_owner ON tasks(owner,updated);
   CREATE TABLE IF NOT EXISTS memories(id TEXT PRIMARY KEY,owner TEXT,key TEXT,value TEXT,updated TEXT,UNIQUE(owner,key));
   CREATE TABLE IF NOT EXISTS turns(id TEXT PRIMARY KEY,owner TEXT,user_text TEXT,reply TEXT,created TEXT);
   CREATE INDEX IF NOT EXISTS turns_owner ON turns(owner,created);
   CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,owner TEXT,kind TEXT,payload TEXT,created TEXT);
   CREATE TABLE IF NOT EXISTS receipts(owner TEXT,request_id TEXT,result TEXT,PRIMARY KEY(owner,request_id));
   CREATE TABLE IF NOT EXISTS actions(id TEXT PRIMARY KEY,owner TEXT,name TEXT,args TEXT,status TEXT,result TEXT,expires REAL);
   CREATE TABLE IF NOT EXISTS media_segments(owner TEXT,session_id TEXT,segment_id TEXT,kind TEXT,language TEXT,text TEXT,created TEXT,updated TEXT,PRIMARY KEY(owner,session_id,segment_id,kind));
   CREATE INDEX IF NOT EXISTS media_owner ON media_segments(owner,updated);
   CREATE TABLE IF NOT EXISTS caption_provenance(owner TEXT,session_id TEXT,segment_id TEXT,kind TEXT,source_kind TEXT,speaker_label TEXT,speaker_basis TEXT,rtc_session_id TEXT,PRIMARY KEY(owner,session_id,segment_id,kind));
  ''');self.db.commit()
 def close(self):self.db.close()
 def identity(self,owner,key):
  if len(owner)!=32 or any(c not in '0123456789abcdef' for c in owner) or not 32<=len(key)<=128:raise ValueError('Invalid device identity')
  proof=hashlib.sha256(key.encode()).hexdigest()
  with self.lock,self.db:
   row=self.db.execute('SELECT proof FROM owners WHERE id=?',(owner,)).fetchone()
   if row and not hmac.compare_digest(row['proof'],proof):raise ValueError('Invalid device key')
   if not row:self.db.execute('INSERT INTO owners VALUES(?,?)',(owner,proof))
  return owner
 def rows(self,sql,args=()):return [dict(r) for r in self.db.execute(sql,args).fetchall()]
 def context(self,owner):
  with self.lock:
   return {'question':self.question(owner),'tasks':self.rows('SELECT * FROM tasks WHERE owner=? ORDER BY updated DESC LIMIT 60',(owner,)),
    'memories':self.rows('SELECT * FROM memories WHERE owner=? ORDER BY updated DESC LIMIT 40',(owner,)),
    'recent_conversation':list(reversed(self.rows('SELECT user_text,reply,created FROM turns WHERE owner=? ORDER BY created DESC LIMIT 10',(owner,)))),
    'recent_media':self.media_recall(owner,source_kind='media',limit=40,budget=8000),
    'recent_translation_conversations':self.media_recall(owner,source_kind='conversation',limit=40,budget=8000)}
 def media_segment(self,owner,session_id,segment_id,kind,language,text,source_kind='media',speaker_label='Video speaker',speaker_basis='unknown',rtc_session_id=''):
  if not owner or not segment_id or not text.strip():return
  stamp=now()
  with self.lock,self.db:
   self.db.execute('INSERT INTO media_segments VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(owner,session_id,segment_id,kind) DO UPDATE SET language=excluded.language,text=excluded.text,updated=excluded.updated',
    (owner,session_id,segment_id,kind,language or '',text[:16000],stamp,stamp))
   self.db.execute('INSERT INTO caption_provenance VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(owner,session_id,segment_id,kind) DO UPDATE SET speaker_label=excluded.speaker_label,speaker_basis=excluded.speaker_basis',
    (owner,session_id,segment_id,kind,source_kind,speaker_label,speaker_basis,rtc_session_id))
 def media_recall(self,owner,query='',session_id=None,limit=60,budget=16000,before=None,source_kind='all'):
  with self.lock:
   where='owner=?';args=[owner]
   if session_id:where+=' AND session_id=?';args.append(session_id)
   if source_kind!='all':where+=' AND source_kind=?';args.append(source_kind)
   if before is not None:where+=' AND evidence_cursor<?';args.append(before)
   if query:where+=' AND instr(lower(text),lower(?))>0';args.append(query)
   evidence="(SELECT m.rowid AS evidence_cursor,m.*,COALESCE(p.source_kind,'media') AS source_kind,COALESCE(p.speaker_label,'Video speaker') AS speaker_label,COALESCE(p.speaker_basis,'unknown') AS speaker_basis FROM media_segments m LEFT JOIN caption_provenance p ON m.owner=p.owner AND m.session_id=p.session_id AND m.segment_id=p.segment_id AND m.kind=p.kind)"
   fields='evidence_cursor AS cursor,session_id,segment_id,kind,language,text,created,source_kind,speaker_label,speaker_basis'
   if source_kind=='all' and not session_id and before is None:
    # Retrieve both evidence sources even when one has many more recent utterances.
    rows=[]
    for category in ('media','conversation'):
     rows+=self.rows('SELECT '+fields+' FROM '+evidence+' WHERE '+where+' AND source_kind=? ORDER BY evidence_cursor DESC LIMIT ?',(*args,category,max(1,limit//2)))
    rows.sort(key=lambda r:r['cursor'],reverse=True)
   else:rows=self.rows('SELECT '+fields+' FROM '+evidence+' WHERE '+where+' ORDER BY evidence_cursor DESC LIMIT ?',(*args,limit))
   limited=len(rows)>=limit or sum(len(r['text']) for r in rows)>budget
   selected=[];remaining=budget
   categories={r['source_kind'] for r in rows};budgets={k:budget//len(categories) for k in categories} if len(categories)>1 else None
   for row in rows:
    available=budgets[row['source_kind']] if budgets else remaining
    if available<=0:continue
    text=row['text'];row['text']=text[-available:];remaining-=len(row['text']);selected.append(row)
    if budgets:budgets[row['source_kind']]-=len(row['text'])
   return {'segments':list(reversed(selected)),'coverage':'Captured speech only. source_kind distinguishes media from face-to-face conversation. Source and translation are two versions of ONE statement. session_id and segment_id identify that statement; cursor identifies its stored evidence row. speaker_basis=language_inferred is an assumption, not verified identity. Never claim to have watched the entire video.',
    'next_before':min(r['cursor'] for r in selected) if selected else None,
    'sessions':self.rows('SELECT session_id,source_kind,MIN(created) AS started,MAX(updated) AS updated,COUNT(*) AS caption_rows FROM '+evidence+' WHERE owner=? '+('AND source_kind=? ' if source_kind!='all' else '')+'GROUP BY session_id,source_kind ORDER BY MAX(evidence_cursor) DESC LIMIT 8',(owner,source_kind) if source_kind!='all' else (owner,)),
    'excerpt_limited':limited}
 def turn(self,owner,turn_id,user_text,reply):
  with self.lock,self.db:self.db.execute('INSERT INTO turns VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET user_text=excluded.user_text,reply=excluded.reply',(turn_id,owner,user_text[:3000],reply[:6000],now()))
 def execute(self,owner,request_id,name,args):
  """Mutations and their retry receipts commit atomically."""
  with self.lock,self.db:
   receipt=self.db.execute('SELECT result FROM receipts WHERE owner=? AND request_id=?',(owner,request_id)).fetchone()
   if receipt:return json.loads(receipt['result'])
   result=self._execute(owner,name,args)
   self.db.execute('INSERT INTO receipts VALUES(?,?,?)',(owner,request_id,encode(result)))
   return result
 def _execute(self,owner,name,a):
  stamp=now()
  if name=='cancel_question':
   self.db.execute('DELETE FROM questions WHERE owner=?',(owner,));return {'status':'cancelled'}
  if name=='ask_user':
   a={'choices':[],**a}
   identity=uid();self.db.execute('INSERT INTO questions VALUES(?,?,?,?,?,?) ON CONFLICT(owner) DO UPDATE SET id=excluded.id,payload=excluded.payload,state=excluded.state,answer=excluded.answer,updated=excluded.updated',(owner,identity,encode(a),'pending','',stamp))
   return {'status':'waiting_for_user','question':self.question(owner)}
  if name=='answer_question':
   question=self.question(owner)
   if not question or question['id']!=a['question_id'] or question['state']!='pending':raise ValueError('This question is no longer waiting for an answer')
   if question['choices'] and a.get('choice_id') not in [c['id'] for c in question['choices']]:raise ValueError('Choose a current option ID or ask for clarification')
   self.db.execute("UPDATE questions SET state='answered',answer=?,updated=? WHERE owner=?",(encode(a),stamp,owner))
   return {'status':'answered','question':self.question(owner)}
  if name=='task_create':
   identity=uid();self.db.execute('INSERT INTO tasks VALUES(?,?,?,?,?,?,?,?)',(identity,owner,a['title'],'open',a.get('due_date'),stamp,stamp,None))
   self.event(owner,'task_created',{'task_id':identity,'title':a['title']})
   return {'status':'saved','task':self.task(owner,identity)}
  if name in ('task_update','task_complete','task_reopen'):
   task=self.task(owner,a['task_id'])
   if not task:raise ValueError('Task not found for this device. Ask which task or list tasks.')
   status='done' if name=='task_complete' else 'open' if name=='task_reopen' else task['status']
   if name=='task_complete' and task['status']=='done':return {'status':'already_done','task':task}
   if name=='task_reopen' and task['status']=='open':return {'status':'already_open','task':task}
   title=a.get('title',task['title']);due=a.get('due_date',task['due_date']);completed=(task['completed'] or stamp) if status=='done' else None
   self.db.execute('UPDATE tasks SET title=?,status=?,due_date=?,updated=?,completed=? WHERE owner=? AND id=?',(title,status,due,stamp,completed,owner,a['task_id']))
   self.event(owner,'task_completed' if name=='task_complete' else 'task_reopened' if name=='task_reopen' else 'task_updated',{'task_id':a['task_id'],'title':title})
   return {'status':'updated','task':self.task(owner,a['task_id'])}
  if name=='task_list':
   rows=self.rows('SELECT * FROM tasks WHERE owner=? ORDER BY updated DESC LIMIT 100',(owner,))
   return {'question':self.question(owner),'tasks':[r for r in rows if a.get('status','all')=='all' or r['status']==a['status']]}
  if name=='memory_save':
   self.db.execute('INSERT INTO memories VALUES(?,?,?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value,updated=excluded.updated',(uid(),owner,a['key'],a['text'],stamp))
   return {'status':'remembered','memory':self.rows('SELECT * FROM memories WHERE owner=? AND key=?',(owner,a['key']))[0]}
  if name=='memory_search':
   query=a.get('query','').casefold()
   rows=self.rows('SELECT * FROM memories WHERE owner=? ORDER BY updated DESC',(owner,))
   turns=self.rows('SELECT user_text,reply,created FROM turns WHERE owner=? ORDER BY created DESC LIMIT 500',(owner,))
   return {'memories':[r for r in rows if query in (r['key']+' '+r['value']).casefold()][:30],
    'conversation':[r for r in turns if query and query in (r['user_text']+' '+r['reply']).casefold()][:10],
    'media':self.media_recall(owner,query=query,limit=30)}
  if name=='media_recall':return self.media_recall(owner,**a)
  if name=='memory_forget':
   count=self.db.execute('DELETE FROM memories WHERE owner=? AND id=?',(owner,a['memory_id'])).rowcount
   if not count:raise ValueError('Memory not found for this device')
   # Remove derived conversational copies and cached tool results of forgotten facts.
   self.db.execute('DELETE FROM questions WHERE owner=?',(owner,))
   self.db.execute('DELETE FROM turns WHERE owner=?',(owner,));self.db.execute('DELETE FROM receipts WHERE owner=?',(owner,))
   return {'status':'forgotten','conversation_context_cleared':True}
  if name=='daily_review':
   from zoneinfo import ZoneInfo
   zone=ZoneInfo(a['timezone']);day=a['date']
   events=self.rows('SELECT kind,payload,created FROM events WHERE owner=? ORDER BY created DESC LIMIT 2000',(owner,))
   return {'date':day,'recorded_events':[{'kind':r['kind'],'details':json.loads(r['payload']),'at':r['created']} for r in events if datetime.fromisoformat(r['created']).astimezone(zone).date().isoformat()==day],
    'conversation_reports':[{'user_text':r['user_text'],'reply':r['reply'],'at':r['created']} for r in self.rows('SELECT user_text,reply,created FROM turns WHERE owner=? ORDER BY created DESC LIMIT 500',(owner,)) if datetime.fromisoformat(r['created']).astimezone(zone).date().isoformat()==day][:40],
    'open_tasks':self.rows("SELECT * FROM tasks WHERE owner=? AND status='open' ORDER BY updated DESC LIMIT 60",(owner,))}
  raise ValueError('Unknown memory tool')
 def clear_screen_results(self,owner):
  with self.lock:
   for key in list(self.screen_results):
    if key[0]==owner:self.screen_results.pop(key,None)
 def question(self,owner):
  rows=self.rows('SELECT * FROM questions WHERE owner=?',(owner,))
  if not rows:return None
  row=rows[0]
  if (datetime.now(timezone.utc)-datetime.fromisoformat(row['updated'])).total_seconds()>1800:return None
  return {**json.loads(row['payload']),'id':row['id'],'state':row['state'],'answer':json.loads(row['answer']) if row['answer'] else None}
 def clear_question(self,owner):
  with self.lock,self.db:self.db.execute('DELETE FROM questions WHERE owner=?',(owner,))
 def task(self,owner,identity):
  rows=self.rows('SELECT * FROM tasks WHERE owner=? AND id=?',(owner,identity));return rows[0] if rows else None
 def event(self,owner,kind,payload):self.db.execute('INSERT INTO events VALUES(?,?,?,?,?)',(uid(),owner,kind,encode(payload),now()))
 def cancel_actions(self,owner,scope=None):
  with self.lock,self.db:
   for row in self.rows("SELECT id,args FROM actions WHERE owner=? AND status='pending'",(owner,)):
    if scope is None or json.loads(row['args']).get('_scope')==scope:
     self.db.execute("UPDATE actions SET status='cancelled',result=? WHERE owner=? AND id=?",(encode({'status':'cancelled'}),owner,row['id']))
 def queue_action(self,owner,identity,name,args,expires):
  with self.lock,self.db:self.db.execute('INSERT OR IGNORE INTO actions VALUES(?,?,?,?,?,?,?)',(identity,owner,name,encode(args),'pending','{}',expires))
 def actions(self,owner):
  import time
  with self.lock,self.db:
   self.db.execute("UPDATE actions SET status='expired',result=? WHERE owner=? AND status='pending' AND expires<?",(encode({'status':'expired'}),owner,time.time()))
   return [{'id':r['id'],'name':r['name'],'arguments':json.loads(r['args'])} for r in self.rows("SELECT * FROM actions WHERE owner=? AND status='pending' ORDER BY expires LIMIT 4",(owner,))]
 def action_result(self,owner,identity,result=None):
  import time
  with self.lock,self.db:
   rows=self.rows('SELECT * FROM actions WHERE owner=? AND id=?',(owner,identity))
   if not rows:raise ValueError('Action not found')
   row=rows[0]
   for cache_key,(created,_) in list(self.screen_results.items()):
    if time.time()-created>120:self.screen_results.pop(cache_key,None)
   cached=self.screen_results.get((owner,identity))
   if result is None and cached:return cached[1]
   if result is not None and row['status']=='pending' and row['expires']>=time.time():
    durable=result
    if row['name'] in ('read_screen','scroll_screen'):
     self.screen_results[(owner,identity)]=(time.time(),result)
     durable={'status':result.get('status','failed'),'detail':'Screen contents are ephemeral and not retained'}
    self.db.execute("UPDATE actions SET status='done',result=? WHERE owner=? AND id=?",(encode(durable),owner,identity));return result
   if row['status']=='pending' and row['expires']<time.time():return {'status':'expired'}
   return json.loads(row['result']) if row['status']!='pending' else None
