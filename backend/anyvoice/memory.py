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
  self.lock=threading.RLock();self.db=sqlite3.connect(path,check_same_thread=False)
  self.db.row_factory=sqlite3.Row
  self.db.executescript('''
   PRAGMA journal_mode=WAL;
   CREATE TABLE IF NOT EXISTS owners(id TEXT PRIMARY KEY, proof TEXT NOT NULL);
   CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,owner TEXT,title TEXT,status TEXT,due_date TEXT,created TEXT,updated TEXT,completed TEXT);
   CREATE INDEX IF NOT EXISTS tasks_owner ON tasks(owner,updated);
   CREATE TABLE IF NOT EXISTS memories(id TEXT PRIMARY KEY,owner TEXT,key TEXT,value TEXT,updated TEXT,UNIQUE(owner,key));
   CREATE TABLE IF NOT EXISTS turns(id TEXT PRIMARY KEY,owner TEXT,user_text TEXT,reply TEXT,created TEXT);
   CREATE INDEX IF NOT EXISTS turns_owner ON turns(owner,created);
   CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,owner TEXT,kind TEXT,payload TEXT,created TEXT);
   CREATE TABLE IF NOT EXISTS receipts(owner TEXT,request_id TEXT,result TEXT,PRIMARY KEY(owner,request_id));
   CREATE TABLE IF NOT EXISTS actions(id TEXT PRIMARY KEY,owner TEXT,name TEXT,args TEXT,status TEXT,result TEXT,expires REAL);
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
   return {'tasks':self.rows('SELECT * FROM tasks WHERE owner=? ORDER BY updated DESC LIMIT 60',(owner,)),
    'memories':self.rows('SELECT * FROM memories WHERE owner=? ORDER BY updated DESC LIMIT 40',(owner,)),
    'recent_conversation':list(reversed(self.rows('SELECT user_text,reply,created FROM turns WHERE owner=? ORDER BY created DESC LIMIT 10',(owner,))))}
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
   return {'tasks':[r for r in rows if a.get('status','all')=='all' or r['status']==a['status']]}
  if name=='memory_save':
   self.db.execute('INSERT INTO memories VALUES(?,?,?,?,?) ON CONFLICT(owner,key) DO UPDATE SET value=excluded.value,updated=excluded.updated',(uid(),owner,a['key'],a['text'],stamp))
   return {'status':'remembered','memory':self.rows('SELECT * FROM memories WHERE owner=? AND key=?',(owner,a['key']))[0]}
  if name=='memory_search':
   query=a.get('query','').casefold()
   rows=self.rows('SELECT * FROM memories WHERE owner=? ORDER BY updated DESC',(owner,))
   turns=self.rows('SELECT user_text,reply,created FROM turns WHERE owner=? ORDER BY created DESC LIMIT 500',(owner,))
   return {'memories':[r for r in rows if query in (r['key']+' '+r['value']).casefold()][:30],
    'conversation':[r for r in turns if query and query in (r['user_text']+' '+r['reply']).casefold()][:10]}
  if name=='memory_forget':
   count=self.db.execute('DELETE FROM memories WHERE owner=? AND id=?',(owner,a['memory_id'])).rowcount
   if not count:raise ValueError('Memory not found for this device')
   # Remove derived conversational copies and cached tool results of forgotten facts.
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
 def task(self,owner,identity):
  rows=self.rows('SELECT * FROM tasks WHERE owner=? AND id=?',(owner,identity));return rows[0] if rows else None
 def event(self,owner,kind,payload):self.db.execute('INSERT INTO events VALUES(?,?,?,?,?)',(uid(),owner,kind,encode(payload),now()))
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
   if result is not None and row['status']=='pending' and row['expires']>=time.time():
    self.db.execute("UPDATE actions SET status='done',result=? WHERE owner=? AND id=?",(encode(result),owner,identity));return result
   if row['status']=='pending' and row['expires']<time.time():return {'status':'expired'}
   return json.loads(row['result']) if row['status']!='pending' else None
