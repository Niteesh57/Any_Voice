"""Explicit interpreter; labels are inferred from language, not voice identity."""
import time
import uuid
from .captions import decode_message,FinalizedCaptions

class ConversationTranslator:
 def __init__(self,pipeline,config):
  self.pipeline=pipeline;self.session=pipeline.session;self.config=config
  self.id=uuid.uuid4().hex;self.sources={};self.pending={};self.finals=FinalizedCaptions();self.started=time.time()*1000
 def receive(self,data):
  observed=False
  for target in (self.session.target_language,self.config['other_language']):
   for item in decode_message(data,self.session.conversation_uid,target):
    observed=True
    if item['kind']=='source':
     if target==self.session.target_language:self.source(item)
    else:self.translation(item,target)
  return observed
 def source(self,item):
  language=item.get('language','');identity=item.get('id')
  if not item.get('final') or not identity:return
  try:stamp=float(identity)
  except (TypeError,ValueError):stamp=0
  if stamp>1e12 and stamp<self.started:return
  if language.split('-')[0]==self.session.target_language.split('-')[0]:
   speaker=self.config.get('my_name') or 'You';target=self.config['other_language'];voice=self.config['other_voice']
  elif language.split('-')[0]==self.config['other_language'].split('-')[0]:
   speaker=self.config.get('partner_name') or 'Other person';target=self.session.target_language;voice=self.session.voice_id
  else:return
  self.sources[identity]=(speaker,target,voice,item['text']);self.remember(item,'source',language,speaker)
  pending=self.pending.pop((identity,target),None)
  if pending:self.translation(pending,target)
 def remember(self,item,kind,language,speaker):
  if self.session.owner and self.session.memory_store:
   self.session.memory_store.media_segment(self.session.owner,self.id,item['id'],kind,language,item['text'],source_kind='conversation',speaker_label=speaker,speaker_basis='language_inferred',rtc_session_id=self.session.id)
 def translation(self,item,target):
  if item.get('speech_end_ms') and item['speech_end_ms']<self.started:return
  identity=item['id'];source=self.sources.get(identity)
  if source is None:
   self.pending[identity,target]=item
   if len(self.pending)>128:self.pending.pop(next(iter(self.pending)))
   return
  speaker,expected,voice,text=source
  if target!=expected:return
  delta=self.finals.accept(identity+'|'+target,item['text'])
  if not delta.strip():return
  self.remember(item,'translation',target,speaker)
  self.session.source=speaker+' · '+text;self.session.translated=item['text']
  self.session.conversation_speaker=speaker;self.session.conversation_direction=target
  locale=self.config['other_locale'] if target==self.config['other_language'] else (self.session.voice_locale or self.session.target_language)
  self.pipeline.phrases.put_nowait((delta,time.monotonic(),self.session.route_epoch,{'voice_id':voice,'locale':locale},True))
