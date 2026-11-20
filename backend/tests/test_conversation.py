import asyncio
import json
import os
import tempfile
import httpx
import unittest
from types import SimpleNamespace
from anyvoice.memory import MemoryStore
from anyvoice.session import Session
from anyvoice.conversation import ConversationTranslator

OWNER='3'*32
class ConversationTests(unittest.IsolatedAsyncioTestCase):
 async def test_explicit_api_start_checks_owner_pair_and_both_voice_ids(self):
  from unittest.mock import AsyncMock,patch
  from anyvoice.api import create_app
  from test_pipeline import configured
  app=create_app(configured(),native_check=lambda:True);s=Session(mode='unified',owner=OWNER,target_language='te-IN',voice_locale='te-IN',voice_id='mine')
  start=AsyncMock();s.pipeline=SimpleNamespace(start_conversation=start);app.state.sessions['fixture']=s
  headers={'Authorization':'Bearer '+configured().access_token,'X-AnyVoice-Device':OWNER,'X-AnyVoice-Device-Key':'a'*64}
  async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://fixture') as client:
   before=await client.get('/v1/sessions/fixture',headers=headers)
   self.assertFalse(before.json()['conversation_listening']);start.assert_not_called()
   other={**headers,'X-AnyVoice-Device':'4'*32}
   body={'other_language':'en-IN','other_voice':'partner'}
   self.assertEqual((await client.post('/v1/sessions/fixture/conversation/start',headers=other,json=body)).status_code,404)
   self.assertEqual((await client.post('/v1/sessions/fixture/conversation/start',headers=headers,json={**body,'other_language':'te-IN'})).status_code,422)
   async def validate(voice,locale):
    if voice=='unsupported':raise ValueError('Unsupported voice-language pair')
   with patch('anyvoice.voices.VoiceCatalog.validate',new=AsyncMock(side_effect=validate)) as check:
    self.assertEqual((await client.post('/v1/sessions/fixture/conversation/start',headers=headers,json={**body,'other_voice':'unsupported'})).status_code,422)
    start.assert_not_called();check.reset_mock()
    self.assertEqual((await client.post('/v1/sessions/fixture/conversation/start',headers=headers,json=body)).status_code,200)
    self.assertEqual(check.await_args_list[0].args,('mine','te-IN'));self.assertEqual(check.await_args_list[1].args,('partner','en-IN'))
    start.assert_awaited_once()
 async def test_previous_release_sql_remains_compatible_and_local_evidence_time(self):
  from anyvoice.agent import MemoryAgent
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  store.db.execute('INSERT INTO media_segments VALUES(?,?,?,?,?,?,?,?)',(OWNER,'1'*32,'old','source','en-IN','Legacy video statement','2026-11-20T05:00:00+00:00','2026-11-20T05:00:00+00:00'));store.db.commit()
  row=store.media_recall(OWNER)['segments'][0]
  self.assertEqual(row['source_kind'],'media');self.assertEqual(row['speaker_basis'],'unknown')
  agent=MemoryAgent(None,store,OWNER,'Asia/Kolkata',{})
  localized,_=agent.observation(row);self.assertTrue(localized['local_time'].endswith('10:30:00+05:30'))

 async def test_provider_cleanup_waits_for_conversation_setup(self):
  from anyvoice.providers import AgoraServices
  from test_pipeline import configured
  opening=asyncio.Event();release=asyncio.Event();calls=[]
  async def handle(request):
   calls.append(request.url.path)
   if request.url.path.endswith('/join'):
    body=json.loads(request.content);self.assertEqual(body['rtcConfig']['subscribeAudioUids'],['1003'])
    self.assertEqual(body['translateConfig']['languages'],[{'source':'te-IN','target':['en-IN']},{'source':'en-IN','target':['te-IN']}])
    opening.set();await release.wait();return httpx.Response(200,json={'agent_id':'conversation-fixture'})
   return httpx.Response(200,json={})
  service=AgoraServices(configured(),Session(mode='unified'),lambda uid:'fixture',httpx.AsyncClient(transport=httpx.MockTransport(handle)))
  task=asyncio.create_task(service.open_conversation('en-IN'));await opening.wait()
  closing=asyncio.create_task(service.close());await asyncio.sleep(.02);self.assertFalse(closing.done())
  release.set();await task;self.assertEqual(await closing,[])
  self.assertEqual(sum(p.endswith('/leave') for p in calls),1)
 def translator(self,store):
  session=Session(mode='unified',input_mode='bidir',desired_input='bidir',owner=OWNER,memory_store=store,target_language='te-IN',voice_locale='te-IN',voice_id='my-selected-voice',route_epoch=2)
  pipeline=SimpleNamespace(session=session,phrases=asyncio.Queue())
  config={'other_language':'en-IN','other_locale':'en-IN','other_voice':'their-selected-voice','my_name':'Me','partner_name':'Demo'}
  return ConversationTranslator(pipeline,config),pipeline
 def source(self,t,identity,language,text,uid=1003):
  t.receive(json.dumps({'transcript':{'uid':uid,'language':language,'sentenceId':identity,'text':text,'isFinal':True}}).encode())
 def translation(self,t,identity,language,text):
  t.receive(json.dumps({'translation':{'uid':1003,'sentenceId':identity,'results':[{'language':language,'texts':[text],'isFinal':True}]}}).encode())
 async def test_both_directions_use_validated_voices_and_dedup_and_persist_provenance(self):
  with tempfile.TemporaryDirectory() as directory:
   path=os.path.join(directory,'memory.db');store=MemoryStore(path);t,p=self.translator(store)
   self.translation(t,1,'te-IN','రెండు నిమిషాలు ఉడికించండి.')
   self.source(t,1,'en-IN','Cook the noodles for two minutes.')
   self.translation(t,1,'te-IN','రెండు నిమిషాలు ఉడికించండి.')
   first=p.phrases.get_nowait();self.assertEqual(first[3],{'voice_id':'my-selected-voice','locale':'te-IN'})
   self.source(t,2,'te-IN','నీటిని ముందుగా మరిగించాలా?')
   self.translation(t,2,'en-IN','Should I boil the water first?')
   second=p.phrases.get_nowait();self.assertEqual(second[3],{'voice_id':'their-selected-voice','locale':'en-IN'})
   self.assertTrue(p.phrases.empty());store.close();store=MemoryStore(path)
   rows=store.media_recall(OWNER,source_kind='conversation')['segments']
   self.assertEqual(len(rows),4);self.assertEqual({r['session_id'] for r in rows},{t.id})
   self.assertEqual({r['speaker_label'] for r in rows},{'Me','Demo'})
   self.assertTrue(all(r['speaker_basis']=='language_inferred' for r in rows));store.close()
 async def test_source_filters_stop_unrelated_media_and_same_language_output(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close);t,p=self.translator(store)
  self.source(t,1,'en-IN','Unrelated video',uid=1001)
  self.assertFalse(t.sources)
  self.source(t,1,'en-IN','Hello')
  self.translation(t,1,'en-IN','Hello')
  self.assertTrue(p.phrases.empty())

 async def test_numbers_keep_previous_direction_and_never_reverse_voice(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close);t,p=self.translator(store)
  self.source(t,1,'te-IN','నీటిని ముందుగా మరిగించాలా?')
  self.translation(t,1,'en-IN','Should I boil the water first?');p.phrases.get_nowait()
  self.source(t,2,'en-IN','one seven')
  phrase=p.phrases.get_nowait();self.assertEqual(phrase[0],'one seven')
  self.assertEqual(phrase[3]['voice_id'],'their-selected-voice')
  self.translation(t,2,'te-IN','ఒకటి ఏడు')
  self.assertTrue(p.phrases.empty())
  self.source(t,3,'en-IN','This is a complete clear English reply from the other person.')
  self.translation(t,3,'te-IN','ఇది నా సమాధానం')
  self.assertEqual(p.phrases.get_nowait()[3]['voice_id'],'my-selected-voice')

 async def test_manual_direction_and_provenance_survive_wrong_language_detection(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close);t,p=self.translator(store)
  t.set_turn('my');self.source(t,1,'en-IN','seventeen')
  self.assertEqual(p.phrases.get_nowait()[3]['voice_id'],'their-selected-voice')
  t.set_turn('other');self.source(t,2,'te-IN','పదిహేడు')
  self.assertEqual(p.phrases.get_nowait()[3]['voice_id'],'my-selected-voice')
  self.assertEqual(t.session.conversation_turn,'other')
  rows=store.media_recall(OWNER,source_kind='conversation')['segments']
  self.assertTrue(all(row['speaker_basis']=='user_selected' for row in rows))
  with self.assertRaises(ValueError):t.set_turn('invalid')

 async def test_manual_turn_boundary_rejects_late_source_captions(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close);t,p=self.translator(store)
  t.set_turn('my');self.source(t,str(int(t.started)-1000),'te-IN','నీటిని ముందుగా మరిగించాలా?')
  self.assertFalse(t.sources);self.assertTrue(p.phrases.empty())

 async def test_turn_api_validates_owner_active_mode_and_side(self):
  from anyvoice.api import create_app
  from test_pipeline import configured
  store=MemoryStore(':memory:');self.addCleanup(store.close);t,p=self.translator(store)
  p.conversation=t;p.session.pipeline=p
  app=create_app(configured(),native_check=lambda:True);app.state.sessions['fixture']=p.session
  headers={'Authorization':'Bearer '+configured().access_token,'X-AnyVoice-Device':OWNER,'X-AnyVoice-Device-Key':'a'*64}
  async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app),base_url='http://fixture') as client:
   route='/v1/sessions/fixture/conversation/turn'
   self.assertEqual((await client.post(route+'?side=my',headers={**headers,'X-AnyVoice-Device':'4'*32})).status_code,404)
   self.assertEqual((await client.post(route+'?side=invalid',headers=headers)).status_code,422)
   response=await client.post(route+'?side=my',headers=headers)
   self.assertEqual(response.status_code,200);self.assertEqual(response.json()['conversation_turn'],'my')
   p.session.input_mode='media'
   self.assertEqual((await client.post(route+'?side=other',headers=headers)).status_code,409)
 async def test_evidence_keeps_video_and_conversation_separate_and_balanced(self):
  store=MemoryStore(':memory:');self.addCleanup(store.close)
  store.media_segment(OWNER,'1'*32,'same-id','source','en-IN','Cook noodles for two minutes.')
  for n in range(60):store.media_segment(OWNER,'2'*32,str(n),'source','en-IN','Cook noodles for three minutes. '+'x'*200,source_kind='conversation',speaker_label='Demo',speaker_basis='language_inferred')
  store.media_segment('4'*32,'5'*32,'other','source','en-IN','private unrelated conversation',source_kind='conversation')
  both=store.media_recall(OWNER,budget=1000)['segments']
  self.assertEqual({r['source_kind'] for r in both},{'media','conversation'})
  self.assertIn('two minutes',json.dumps(both));self.assertIn('three minutes',json.dumps(both))
  context=store.context(OWNER)
  self.assertTrue(context['recent_media']['segments']);self.assertTrue(context['recent_translation_conversations']['segments'])
  self.assertNotIn('private unrelated',json.dumps(context))
