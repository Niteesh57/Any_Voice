"""Falcon-only voice catalog and short authenticated previews."""
import asyncio
from dataclasses import replace
import io
import time
import wave
import httpx
from .murf_stream import MurfStream
from .providers import ProviderError

SAMPLE_ROWS = {
 "te-IN": ("Telugu", "తెలుగు", "నమస్కారం. మీకు నచ్చిన విషయాలను మీ భాషలో వినండి."),
 "hi-IN": ("Hindi", "हिन्दी", "नमस्ते। अपनी पसंद की बातें अपनी भाषा में सुनिए।"),
 "ta-IN": ("Tamil", "தமிழ்", "வணக்கம். உங்களுக்குப் பிடித்த விஷயங்களை உங்கள் மொழியில் கேளுங்கள்."),
 "kn-IN": ("Kannada", "ಕನ್ನಡ", "ನಮಸ್ಕಾರ. ನಿಮ್ಮ ಇಷ್ಟದ ವಿಷಯಗಳನ್ನು ನಿಮ್ಮ ಭಾಷೆಯಲ್ಲಿ ಕೇಳಿ."),
 "ml-IN": ("Malayalam", "മലയാളം", "നമസ്കാരം. നിങ്ങളുടെ പ്രിയപ്പെട്ട കാര്യങ്ങൾ നിങ്ങളുടെ ഭാഷയിൽ കേൾക്കൂ."),
 "mr-IN": ("Marathi", "मराठी", "नमस्कार. तुमच्या आवडीच्या गोष्टी तुमच्या भाषेत ऐका."),
 "bn-IN": ("Bengali", "বাংলা", "নমস্কার। আপনার পছন্দের বিষয় আপনার ভাষায় শুনুন।"),
 "gu-IN": ("Gujarati", "ગુજરાતી", "નમસ્તે. તમારી પસંદની વાતો તમારી ભાષામાં સાંભળો."),
 "pa-IN": ("Punjabi", "ਪੰਜਾਬੀ", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ। ਆਪਣੀ ਪਸੰਦ ਦੀਆਂ ਗੱਲਾਂ ਆਪਣੀ ਭਾਸ਼ਾ ਵਿੱਚ ਸੁਣੋ।"),
 "or-IN": ("Odia", "ଓଡ଼ିଆ", "ନମସ୍କାର। ନିଜ ପସନ୍ଦର କଥା ନିଜ ଭାଷାରେ ଶୁଣନ୍ତୁ।"),
 "as-IN": ("Assamese", "অসমীয়া", "নমস্কাৰ। আপোনাৰ পছন্দৰ কথাবোৰ আপোনাৰ ভাষাত শুনক।"),
 "en-IN": ("English", "English", "Welcome to Any Voice. Hear the things you love, in your language."),
}

import json
from pathlib import Path
AGORA_LANGUAGES = json.loads(Path(__file__).with_name("agora_languages.json").read_text(encoding="utf-8"))["languages"]
# Provider locale aliases, not voice lists. Hinglish has no Agora translation target.
LOCALE_ALIASES = {"en-UK":"en-GB", "en-SCOTT":"en-GB", "ar-XA":"ar-SA"}
def agora_locale(locale):
 value=LOCALE_ALIASES.get(locale,locale)
 return value if value in AGORA_LANGUAGES else None
SAMPLES = {locale:row[2] for locale,row in SAMPLE_ROWS.items()}
SAMPLES.update({
 "ar-SA":"مرحباً بك في أني فويس. استمع إلى ما تحب بلغتك.",
 "bg-BG":"Добре дошли. Слушайте любимите си неща на вашия език.",
 "cs-CZ":"Vítejte. Poslouchejte to, co máte rádi, ve svém jazyce.",
 "de-DE":"Willkommen bei Any Voice. Hören Sie, was Sie lieben, in Ihrer Sprache.",
 "el-GR":"Καλώς ήρθατε. Ακούστε όσα αγαπάτε στη γλώσσα σας.",
 "es-ES":"Bienvenido a Any Voice. Escucha lo que te gusta en tu idioma.",
 "es-MX":"Bienvenido a Any Voice. Escucha lo que te gusta en tu idioma.",
 "fr-FR":"Bienvenue. Écoutez ce que vous aimez dans votre langue.",
 "fr-CA":"Bienvenue. Écoutez ce que vous aimez dans votre langue.",
 "hr-HR":"Dobro došli. Slušajte ono što volite na svom jeziku.",
 "id-ID":"Selamat datang. Dengarkan hal yang Anda sukai dalam bahasa Anda.",
 "it-IT":"Benvenuto. Ascolta ciò che ami nella tua lingua.",
 "ja-JP":"ようこそ。好きな音声を、あなたの言葉で聞いてください。",
 "ko-KR":"환영합니다. 좋아하는 이야기를 나의 언어로 들어보세요.",
 "ms-MY":"Selamat datang. Dengar perkara yang anda suka dalam bahasa anda.",
 "nl-BE":"Welkom. Luister naar wat je leuk vindt, in je eigen taal.",
 "nl-NL":"Welkom. Luister naar wat je leuk vindt, in je eigen taal.",
 "pl-PL":"Witaj. Słuchaj tego, co lubisz, w swoim języku.",
 "pt-BR":"Bem-vindo. Ouça o que você gosta no seu idioma.",
 "ro-RO":"Bine ai venit. Ascultă ce îți place în limba ta.",
 "sk-SK":"Vitajte. Počúvajte to, čo máte radi, vo svojom jazyku.",
 "sv-SE":"Välkommen. Lyssna på det du tycker om på ditt språk.",
 "th-TH":"ยินดีต้อนรับ ฟังสิ่งที่คุณชอบในภาษาของคุณ",
 "tl-PH":"Maligayang pagdating. Pakinggan ang gusto mo sa iyong wika.",
 "tr-TR":"Hoş geldiniz. Sevdiklerinizi kendi dilinizde dinleyin.",
 "vi-VN":"Chào mừng bạn. Nghe những điều bạn yêu thích bằng ngôn ngữ của mình.",
 "zh-CN":"欢迎使用。用您自己的语言，聆听喜欢的内容。",
})
def sample_text(locale):
 return SAMPLES.get(agora_locale(locale),"Welcome to Any Voice. Hear the things you love, in your language.")

class VoiceCatalog:
 def __init__(self, settings):
  self.settings=settings; self.rows=[]; self.languages=[]; self.expires=0; self.lock=asyncio.Lock(); self.preview_lock=asyncio.Lock(); self.previews={}
 async def get(self, force=False):
  async with self.lock:
   if not force and self.rows and time.monotonic()<self.expires: return self.rows
   try:
    async with httpx.AsyncClient(timeout=20) as client:
     response=await client.get("https://api.murf.ai/v1/speech/voices",params={"model":"falcon-2.1"},headers={"api-key":self.settings.murf_key})
     response.raise_for_status(); raw=response.json()
    if not isinstance(raw,list): raise ValueError("catalog format")
    rows=[];languages={}
    for voice in raw:
     supported=voice.get("supportedLocales",{})
     if not isinstance(supported,dict): continue
     supported=dict(supported)
     native=voice.get("locale")
     if isinstance(native,str) and native not in supported:
      supported[native]={"detail":voice.get("displayLanguage",native)+(" ("+voice["accent"]+")" if voice.get("accent") else "")}
     locales=[]
     for locale,metadata in supported.items():
      target=agora_locale(locale)
      if not target: continue
      locales.append(locale)
      detail=metadata.get("detail",locale) if isinstance(metadata,dict) else locale
      languages[locale]={"id":locale,"name":detail,"native":detail,"agora_locale":target}
     if locales:
      rows.append({"id":voice["voiceId"],"name":voice.get("displayName",voice["voiceId"]).replace(" (M)","").replace(" (F)",""),
       "gender":voice.get("gender",""),"locales":locales,"native_locale":voice.get("locale",""),"style":"Conversational"})
    if not rows: raise ValueError("empty catalog")
    self.languages=sorted(languages.values(),key=lambda item:item["name"])
    self.rows=rows; self.expires=time.monotonic()+600
    return rows
   except Exception as exc: raise ProviderError("Voice library unavailable. Please try again.") from exc
 async def validate(self, voice_id, language):
  rows=await self.get(force=True)
  voice=next((v for v in rows if v["id"]==voice_id and language in v["locales"]),None)
  if not voice: raise ValueError("Select an available Falcon voice for this language")
  return voice
 async def preview(self, voice_id, language):
  await self.validate(voice_id,language)
  key=(voice_id,language)
  async with self.preview_lock:
   if key in self.previews: return self.previews[key]
   client=MurfStream(replace(self.settings,voice=voice_id,target_locale=language)); pcm=bytearray()
   def receive(data):
    if len(pcm)+len(data)>16000*2*20: raise ProviderError("Voice preview exceeded its limit")
    pcm.extend(data)
   try:
    await asyncio.wait_for(client.synthesize(sample_text(language),receive,lambda:None),30)
   finally: await client.close()
   output=io.BytesIO()
   with wave.open(output,"wb") as wav:
    wav.setnchannels(1);wav.setsampwidth(2);wav.setframerate(16000);wav.writeframes(pcm)
   if len(self.previews)>=32: self.previews.pop(next(iter(self.previews)))
   self.previews[key]=output.getvalue()
   return self.previews[key]
