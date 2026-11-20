package dev.hiprobe;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.AudioAttributes;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import org.json.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    static final int INK=0xff183c35, GREEN=0xff1d5c49, MUTED=0xff738078, PAPER=0xfff6f5ef, LINE=0xffe3e7de, LIME=0xffd9edb5;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private UserProfile profile;
    private OutputVolume voiceVolume;
    private LinearLayout body;
    private TextView stateLabel,source,translated,startLabel,voiceSummary,languageTitle,screenStatus;
    private BrandView wave;
    private boolean checking,resumed;
    private JSONObject catalog;
    private Dialog picker;
    private MediaPlayer preview;
    private TextView previewControl;
    private File previewFile;
    private int previewGeneration;
    private final Runnable refresh=new Runnable(){public void run(){
        if(startLabel!=null){
            boolean running=ListeningState.running;
            updateText(startLabel,running?"Disable":checking?"Connecting…":"Enable");
            startLabel.setEnabled(!checking&&!LiveTranslation.isStopping());
            startLabel.setContentDescription(startLabel.getText());
            updateText(stateLabel,running?friendlyStatus():checking?"Checking your connection":idleStatus());
            wave.active(running);
            updateText(source,ListeningState.sourceText.isEmpty()?"Your original speech appears here.":ListeningState.sourceText);
            updateText(translated,ListeningState.translatedText.isEmpty()?"Your translation appears here.":ListeningState.translatedText);
        }
        if(screenStatus!=null)updateText(screenStatus,ScreenTools.state(MainActivity.this));
        handler.postDelayed(this,350);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);profile=new UserProfile(this);voiceVolume=new OutputVolume(this);
        if(!ListeningState.running)try{new VolumeGuard(this).restore();}catch(RuntimeException ignored){}
        getWindow().setStatusBarColor(PAPER);getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if(profile.name().isEmpty())onboarding();else home();
    }
    private String idleStatus(){
        String message=ListeningState.message;
        return message.startsWith("Stopped")||message.startsWith("Ready")||message.startsWith("Session ended")?"Ready when you are":message;
    }
    private String friendlyStatus(){
        if(BubbleState.active||ListeningState.message.startsWith("Locked"))return ListeningState.message;
        if(ListeningState.message.startsWith("Could")||ListeningState.message.contains("failed")||ListeningState.message.contains("lost"))return ListeningState.message;
        if(ListeningState.detectedLanguage.isEmpty())return "Listening · finding the language";
        return ListeningState.replacing?"Translating · "+profile.languageName():"Your language · original voice";
    }
    private LinearLayout page(){
        startLabel=null;
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);scroll.setBackgroundColor(PAPER);
        body=column();body.setPadding(dp(24),dp(20),dp(24),dp(24));scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{body.setPadding(dp(24),dp(16)+insets.getSystemWindowInsetTop(),dp(24),dp(20)+insets.getSystemWindowInsetBottom());return insets;});
        setContentView(scroll);return body;
    }
    private void header(LinearLayout parent,boolean profileButton){
        LinearLayout row=row();parent.addView(row,new LinearLayout.LayoutParams(-1,dp(52)));
        BrandView logo=new BrandView(this,false,GREEN);logo.setContentDescription("Any Voice");row.addView(logo,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView brand=label(row,"any voice",22,INK,700);LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.setMargins(dp(9),0,0,0);brand.setLayoutParams(bp);brand.setLetterSpacing(-.04f);
        if(profileButton){TextView avatar=chip(profile.name().substring(0,1).toUpperCase(Locale.ROOT),INK,LIME);avatar.setContentDescription("Your profile and settings");row.addView(avatar,new LinearLayout.LayoutParams(dp(44),dp(44)));avatar.setOnClickListener(v->profileDialog());}
    }
    private void onboarding(){
        LinearLayout root=page();header(root,false);space(root,36);
        TextView tag=label(root,"A VOICE THAT FEELS LIKE HOME",11,GREEN,700);tag.setLetterSpacing(.12f);
        label(root,"Make it\nyours.",44,INK,700).setLetterSpacing(-.045f);
        label(root,"A little about you. A whole new way to listen.",15,MUTED,400);space(root,25);
        label(root,"What should we call you?",14,INK,600);
        EditText name=field(root,"Your name",profile.name(),false);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});name.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        space(root,18);label(root,"Your gender",14,INK,600);
        final String[] gender={profile.gender()};genderControls(root,gender);
        label(root,"Your profile stays on this phone.",12,MUTED,400);space(root,30);
        action(root,"Find my voice  →",GREEN,Color.WHITE,v->{
            String value=name.getText().toString().trim();if(value.isEmpty()){name.setError("Enter your name");return;}
            profile.identity(value,gender[0]);home();openVoices();
        });space(root,30);hackathon(root);enter(root);
    }
    private void home(){
        LinearLayout root=page();header(root,true);space(root,25);
        TextView hello=label(root,"HELLO, "+profile.name().toUpperCase(Locale.ROOT),11,GREEN,700);hello.setLetterSpacing(.13f);
        label(root,"Every voice.\nCloser to you.",35,INK,700).setLetterSpacing(-.04f);
        label(root,"Listen in the language you call home.",14,MUTED,400);space(root,22);
        LinearLayout hero=column();hero.setPadding(dp(23),dp(23),dp(23),dp(22));hero.setBackground(shape(GREEN,28));root.addView(hero);
        TextView eyebrow=label(hero,"YOUR LISTENING LANGUAGE",10,LIME,700);eyebrow.setLetterSpacing(.13f);
        LinearLayout languageRow=row();hero.addView(languageRow);
        languageTitle=label(languageRow,profile.nativeName(),36,PAPER,600);languageTitle.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        TextView change=chip("Change  ↗",PAPER,0x22ffffff);languageRow.addView(change,new LinearLayout.LayoutParams(dp(88),dp(40)));change.setOnClickListener(v->openVoices());
        voiceSummary=label(hero,profile.voice().isEmpty()?"Choose a language and a voice":profile.languageName()+"  ·  "+profile.voiceName()+"’s voice",13,0xffd0ded4,400);
        wave=new BrandView(this,true,LIME);hero.addView(wave,new LinearLayout.LayoutParams(-1,dp(86)));
        stateLabel=label(hero,"Ready when you are",12,0xffd0ded4,400);stateLabel.setGravity(Gravity.CENTER);space(hero,14);
        startLabel=action(hero,"Enable",PAPER,INK,v->{
            if(checking||LiveTranslation.isStopping())return;
            stopPreview();if(ListeningState.running)command(ListeningService.STOP);else preflight();
        });space(hero,12);hero.addView(new VoiceVolumeView(this,LIME,this::applyPreviewVolume));space(root,18);
        LinearLayout voiceCard=card(root);LinearLayout vr=row();voiceCard.addView(vr);
        BrandView voiceIcon=new BrandView(this,false,GREEN);voiceIcon.setBackground(shape(0xffedf2e3,14));vr.addView(voiceIcon,new LinearLayout.LayoutParams(dp(45),dp(45)));
        LinearLayout voiceInfo=column();LinearLayout.LayoutParams vi=new LinearLayout.LayoutParams(0,-2,1);vi.setMargins(dp(13),0,0,0);vr.addView(voiceInfo,vi);
        label(voiceInfo,"Your voice, your choice",15,INK,600);label(voiceInfo,profile.voice().isEmpty()?"Explore voices and listen to a preview":profile.voiceName()+" · "+profile.voiceGender()+" · Conversational",12,MUTED,400);
        label(vr,"↗",23,GREEN,400);voiceCard.setContentDescription("Choose language and voice, current voice "+profile.voiceName());voiceCard.setOnClickListener(v->openVoices());
        space(root,12);
        LinearLayout floating=card(root);LinearLayout fr=row();floating.addView(fr);
        BrandView small=new BrandView(this,false,GREEN);fr.addView(small,new LinearLayout.LayoutParams(dp(42),dp(42)));
        LinearLayout fi=column();LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(0,-2,1);fp.setMargins(dp(15),0,0,0);fr.addView(fi,fp);
        label(fi,"Always within reach",15,INK,600);label(fi,Settings.canDrawOverlays(this)?"Drag the bubble. Tap for controls.":"Enable your floating shortcut",12,MUTED,400);
        label(fr,"↗",23,GREEN,400);floating.setOnClickListener(v->{if(!Settings.canDrawOverlays(this))startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));else toast("Your single floating bubble appears when Any Voice is enabled.");});
        space(root,14);
        action(root,"Screen assist  →",0xffedf2e3,GREEN,v->screenAssist());
        screenStatus=label(root,"Checking screen access",12,MUTED,400);space(root,8);
        action(root,"Tasks & memory  →",0xffedf2e3,GREEN,v->openMemory());space(root,12);
        label(root,"One bubble. Two ways to listen.",20,INK,700);
        label(root,"App audio takes priority for translation. After a quiet period, Bubble listens for your questions. Tap Ask Bubble in the floating controls to ask over a video.",13,MUTED,400);
        label(root,"While enabled, the microphone is used in conversation mode only when unlocked. Locking pauses the microphone. Disable stops both modes.",11,MUTED,400);
        space(root,20);
        LinearLayout captions=column();root.addView(captions);TextView transcriptToggle=label(captions,"Live transcript   ⌄",13,GREEN,600);
        LinearLayout transcript=card(captions);transcript.setVisibility(View.GONE);
        label(transcript,"ORIGINAL",10,MUTED,700);source=label(transcript,"Your original speech appears here.",14,INK,400);
        label(transcript,profile.languageName().toUpperCase(Locale.ROOT),10,GREEN,700);translated=label(transcript,"Your translation appears here.",16,INK,500);
        transcriptToggle.setOnClickListener(v->{boolean hidden=transcript.getVisibility()==View.GONE;transcript.setVisibility(hidden?View.VISIBLE:View.GONE);transcriptToggle.setText(hidden?"Live transcript   ⌃":"Live transcript   ⌄");if(hidden)enter(transcript);});
        label(root,"Already in your language? Hear the original voice.\nMedia translation uses app audio. Bubble uses your microphone only when enabled.",12,MUTED,400);space(root,24);hackathon(root);enter(root);
    }
    private void screenAssist(){
        boolean enabled=ScreenTools.enabled(MainActivity.this);
        boolean authorized=ScreenTools.authorized(MainActivity.this);
        new AlertDialog.Builder(this).setTitle(enabled?"Screen assist is ready":authorized?"Screen assist is reconnecting":"Screen assist setup")
            .setMessage("When you ask about the current screen, Bubble sends visible app text and a screenshot to its cloud AI. It can scroll up to three times to compare results. Screens are not collected in the background or saved as memories automatically. Password fields and Android-protected screenshots are withheld.\n\nAndroid accessibility access is needed for reading and scrolling. No tapping, typing, purchases or messages are enabled.")
            .setNegativeButton(authorized?"Android settings":"Cancel",(d,w)->{if(authorized)startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));})
            .setNeutralButton("Turn off",(d,w)->{ScreenTools.setEnabled(this,false);toast("Screen assist off");})
            .setPositiveButton(authorized?"Done":"Enable screen assist",(d,w)->{ScreenTools.setEnabled(this,true);if(!authorized)startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}).show();
    }
    private void openMemory(){
        network.execute(()->{try{JSONObject data=new BackendClient(this).overview();handler.post(()->{if(isFinishing())return;
            LinearLayout content=column();content.setPadding(dp(22),dp(12),dp(22),dp(18));
            label(content,"Your tasks & memory",23,INK,700);label(content,"Saved for this phone across voice sessions and backend restarts.",12,MUTED,400);
            JSONArray tasks=data.optJSONArray("tasks");label(content,"Tasks",17,INK,700);
            if(tasks==null||tasks.length()==0)label(content,"Ask Bubble: remember I need to finish my presentation tomorrow.",13,MUTED,400);
            if(tasks!=null)for(int i=0;i<tasks.length();i++){
                JSONObject task=tasks.optJSONObject(i);if(task==null)continue;
                boolean done=task.optString("status").equals("done");
                CheckBox item=new CheckBox(this);String due=task.optString("due_date","");if(due.equals("null"))due="";
                item.setText(task.optString("title")+(due.isEmpty()?"":" · "+due));item.setTextColor(INK);item.setChecked(done);content.addView(item);
                item.setOnCheckedChangeListener((button,checked)->{item.setEnabled(false);network.execute(()->{try{new BackendClient(this).tool(checked?"task_complete":"task_reopen",new JSONObject().put("task_id",task.optString("id")));handler.post(()->item.setEnabled(true));}catch(Exception e){handler.post(()->{item.setOnCheckedChangeListener(null);item.setChecked(!checked);toast("Update failed. Reopen Tasks & memory to retry.");});}});});
            }
            label(content,"Remembered facts",17,INK,700);JSONArray facts=data.optJSONArray("memories");
            if(facts==null||facts.length()==0)label(content,"Say: remember that I prefer short explanations.",13,MUTED,400);
            if(facts!=null)for(int i=0;i<facts.length();i++){
                JSONObject fact=facts.optJSONObject(i);if(fact==null)continue;
                label(content,fact.optString("key"),13,GREEN,600);TextView value=label(content,fact.optString("value"),14,INK,400);
                TextView forget=action(content,"Forget",0xffedf2e3,GREEN,v->{});
                forget.setOnClickListener(v->{forget.setEnabled(false);network.execute(()->{try{new BackendClient(this).tool("memory_forget",new JSONObject().put("memory_id",fact.optString("id")));handler.post(()->{value.setText("Removed");forget.setText("Forgotten");toast("Memory removed; previous conversational copies cleared");});}catch(Exception e){handler.post(()->{forget.setEnabled(true);toast(e.getMessage());});}});});
            }
            ScrollView scroll=new ScrollView(this);scroll.addView(content);new AlertDialog.Builder(this).setView(scroll).setPositiveButton("Close",null).show();
        });}catch(Exception e){handler.post(()->toast(e.getMessage()));}});
    }
    private void hackathon(LinearLayout parent){
        View line=new View(this);line.setBackgroundColor(LINE);parent.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));space(parent,16);
        label(parent,"BUILT FOR  /  BUILD WITH AGORA",10,MUTED,700).setLetterSpacing(.09f);
        label(parent,"Bengaluru 2026  ·  KNOTiC",13,INK,600);
        label(parent,"Agora  ×  Murf AI",15,GREEN,700);
    }
    private void profileDialog(){
        stopPreview();LinearLayout fields=column();fields.setPadding(dp(24),dp(8),dp(24),dp(16));
        label(fields,"Make yourself at home.",23,INK,700);EditText name=field(fields,"Your name",profile.name(),false);name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(30)});
        String[] gender={profile.gender()};genderControls(fields,gender);
        TextView connection=action(fields,"Demo connection",0xffedf2e3,GREEN,v->connectionSettings());connection.setEnabled(!ListeningState.running);
        label(fields,"Any Voice · "+BuildConfig.VERSION_NAME+"\nBuilt for Build with Agora — Bengaluru 2026\nPowered by Agora RTC and Murf Falcon",12,MUTED,400);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(fields).setNegativeButton("Close",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(w->{if(name.getText().toString().trim().isEmpty()){name.setError("Enter your name");return;}profile.identity(name.getText().toString(),gender[0]);dialog.dismiss();home();}));dialog.show();
    }
    private void genderControls(LinearLayout parent,String[] selected){
        LinearLayout choices=row();parent.addView(choices);ArrayList<TextView> chips=new ArrayList<>();
        for(String value:new String[]{"Male","Female","Prefer not to say"}){
            TextView item=chip(value,value.equals(selected[0])?Color.WHITE:INK,value.equals(selected[0])?GREEN:0xffe9ede3);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(46),1);lp.setMargins(0,dp(8),dp(6),dp(8));choices.addView(item,lp);chips.add(item);
            item.setOnClickListener(v->{selected[0]=value;for(TextView option:chips){boolean checked=option.getText().toString().equals(value);option.setTextColor(checked?Color.WHITE:INK);option.setBackground(shape(checked?GREEN:0xffe9ede3,14));option.setSelected(checked);}});
        }
    }
    private void openVoices(){
        if(BubbleState.enabled){toast("Disable Any Voice to change or preview voices.");return;}
        if(ListeningState.running){toast("Disable Any Voice to change your language or voice.");return;}
        if(!new BackendClient(this).configured()){connectionSettings();return;}

        checking=true;
        network.execute(()->{try{JSONObject result=new BackendClient(this).voices();handler.post(()->{checking=false;if(isFinishing()||isDestroyed())return;catalog=result;if(resumed)voicePicker();});}
            catch(Exception e){handler.post(()->{checking=false;toast("Could not load voices. Check your connection and try again.");});}});
    }
    private void voicePicker(){
        stopPreview();if(picker!=null)picker.dismiss();
        Dialog dialog=new Dialog(this);picker=dialog;
        LinearLayout sheet=column();sheet.setPadding(dp(22),dp(15),dp(22),dp(14));sheet.setBackground(shape(PAPER,28));
        View handle=new View(this);handle.setBackground(shape(0xffcbd4c9,4));LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(dp(36),dp(4));hp.gravity=Gravity.CENTER;hp.bottomMargin=dp(15);sheet.addView(handle,hp);
        LinearLayout title=row();sheet.addView(title);TextView heading=label(title,"Find your voice",25,INK,700);heading.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));TextView close=chip("×",INK,0xffe9ede3);title.addView(close,new LinearLayout.LayoutParams(dp(40),dp(40)));close.setContentDescription("Close voice selection");close.setOnClickListener(v->dialog.dismiss());
        label(sheet,"Pick a language. Hear a voice. Make it yours.",13,MUTED,400);
        JSONArray languages=catalog.optJSONArray("languages"),voices=catalog.optJSONArray("voices");if(languages==null||voices==null){toast("Voice library unavailable");return;}
        ArrayList<JSONObject> languageRows=new ArrayList<>();ArrayList<String> names=new ArrayList<>();int chosen=0;
        for(int i=0;i<languages.length();i++){JSONObject language=languages.optJSONObject(i);if(language==null)continue;if(language.optString("id").equals(profile.language()))chosen=names.size();String localeCode=language.optString("agora_locale",language.optString("id"));Locale local=Locale.forLanguageTag(localeCode);String nativeLabel=local.getDisplayLanguage(local);if(!nativeLabel.isEmpty())try{language.put("native",nativeLabel);}catch(JSONException ignored){}languageRows.add(language);names.add(nativeLabel+"  ·  "+language.optString("name"));}
        Spinner language=new Spinner(this);language.setPadding(dp(10),0,dp(8),0);language.setBackground(shape(Color.WHITE,14));language.setContentDescription("Choose listening language");ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,names);language.setAdapter(adapter);sheet.addView(language,new LinearLayout.LayoutParams(-1,dp(54)));language.setSelection(chosen);
        LinearLayout filter=row();sheet.addView(filter);String[] gender={"All"};ArrayList<TextView> filters=new ArrayList<>();
        sheet.addView(new VoiceVolumeView(this,GREEN,this::applyPreviewVolume));
        TextView count=label(sheet,"Loading voices…",12,MUTED,400);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);LinearLayout list=column();scroll.addView(list);sheet.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView detected=label(sheet,"",11,MUTED,400);
        Runnable render=()->{
            stopPreview();list.removeAllViews();JSONObject lang=languageRows.get(language.getSelectedItemPosition());String locale=lang.optString("id");int number=0;
            ArrayList<JSONObject> matches=new ArrayList<>();
            for(int i=0;i<voices.length();i++){JSONObject voice=voices.optJSONObject(i);if(voice==null)continue;JSONArray locales=voice.optJSONArray("locales");boolean supported=false;for(int j=0;locales!=null&&j<locales.length();j++)if(locale.equals(locales.optString(j)))supported=true;
                if(supported&&(gender[0].equals("All")||gender[0].equalsIgnoreCase(voice.optString("gender"))))matches.add(voice);}
            matches.sort((a,b)->{boolean ac=a.optString("id").equals(profile.voice()),bc=b.optString("id").equals(profile.voice());return ac!=bc?(ac?-1:1):a.optString("name").compareTo(b.optString("name"));});
            for(JSONObject voice:matches){number++;boolean selected=locale.equals(profile.language())&&voice.optString("id").equals(profile.voice());
                LinearLayout card=card(list);card.setPadding(dp(14),dp(12),dp(14),dp(12));LinearLayout row=row();card.addView(row);
                TextView initial=chip(voice.optString("name","V").substring(0,1),GREEN,selected?LIME:0xffedf2e3);row.addView(initial,new LinearLayout.LayoutParams(dp(42),dp(42)));
                LinearLayout info=column();LinearLayout.LayoutParams il=new LinearLayout.LayoutParams(0,-2,1);il.setMargins(dp(11),0,dp(7),0);row.addView(info,il);label(info,voice.optString("name")+(selected?"  ✓":""),16,INK,600);label(info,voice.optString("gender")+" · Conversational",11,MUTED,400);
                TextView previewButton=chip("▶",GREEN,0xffedf2e3);previewButton.setContentDescription("Preview "+voice.optString("name"));row.addView(previewButton,new LinearLayout.LayoutParams(dp(46),dp(46)));previewButton.setOnClickListener(v->playPreview(voice.optString("id"),locale,previewButton));
                card.setOnClickListener(v->{profile.selection(locale,lang.optString("name"),lang.optString("native"),voice.optString("id"),voice.optString("name"),voice.optString("gender"));dialog.dismiss();home();toast(voice.optString("name")+" is your new voice");});
                LinearLayout.LayoutParams cp=(LinearLayout.LayoutParams)card.getLayoutParams();cp.bottomMargin=dp(9);card.setLayoutParams(cp);
            }
            count.setText(number+" voices · tap a card to select");
            JSONArray detection=lang.optJSONArray("detection");ArrayList<String> dn=new ArrayList<>();for(int i=0;detection!=null&&i<detection.length();i++){String id=detection.optString(i);for(JSONObject entry:languageRows)if(id.equals(entry.optString("agora_locale",entry.optString("id")))&&!dn.contains(Locale.forLanguageTag(id).getDisplayLanguage(Locale.ENGLISH)))dn.add(Locale.forLanguageTag(id).getDisplayLanguage(Locale.ENGLISH));}
            detected.setText("Auto-detects "+android.text.TextUtils.join(", ",dn)+". Same-language audio keeps its original voice.");
        };
        for(String value:new String[]{"All","Male","Female"}){TextView item=chip(value,value.equals(gender[0])?Color.WHITE:INK,value.equals(gender[0])?GREEN:0xffe9ede3);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(40),1);lp.setMargins(0,dp(6),dp(6),dp(6));filter.addView(item,lp);filters.add(item);item.setOnClickListener(v->{gender[0]=value;for(TextView option:filters){boolean checked=option.getText().toString().equals(value);option.setTextColor(checked?Color.WHITE:INK);option.setBackground(shape(checked?GREEN:0xffe9ede3,14));}render.run();});}
        language.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){public void onItemSelected(AdapterView<?> parent,View view,int position,long id){render.run();}public void onNothingSelected(AdapterView<?> parent){}});
        dialog.setOnKeyListener((d,key,event)->voiceVolume.key(event));dialog.setContentView(sheet);dialog.setOnDismissListener(v->{stopPreview();if(picker==dialog)picker=null;});Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.32f);window.setGravity(Gravity.BOTTOM);window.setWindowAnimations(R.style.VoiceSheetAnimation);window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.84));}
        dialog.show();if(window!=null)window.setLayout(-1,(int)(getResources().getDisplayMetrics().heightPixels*.84));render.run();
    }
    private void playPreview(String voice,String language,TextView button){
        if(previewControl==button&&preview!=null){stopPreview();return;}
        stopPreview();previewControl=button;int generation=previewGeneration;button.setText("…");button.setEnabled(false);
        network.execute(()->{try{byte[] audio=new BackendClient(this).preview(voice,language);handler.post(()->{
            if(generation!=previewGeneration||!resumed||isDestroyed()){button.setText("▶");button.setEnabled(true);return;}
            try{previewFile=File.createTempFile("voice-preview-",".wav",getCacheDir());Files.write(previewFile.toPath(),audio);preview=new MediaPlayer();preview.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build());preview.setDataSource(previewFile.getAbsolutePath());preview.setOnPreparedListener(player->{if(generation==previewGeneration){button.setText("Ⅱ");applyPreviewVolume();player.start();}});preview.setOnCompletionListener(player->{if(generation==previewGeneration)stopPreview();});preview.setOnErrorListener((player,what,extra)->{if(generation==previewGeneration){stopPreview();toast("Preview could not play");}return true;});preview.prepareAsync();button.setEnabled(true);}
            catch(Exception e){stopPreview();button.setText("▶");button.setEnabled(true);toast("Preview could not play");}
        });}catch(Exception e){handler.post(()->{button.setText("▶");button.setEnabled(true);if(generation==previewGeneration)toast("Preview unavailable. Please try again.");});}});
    }
    private void applyPreviewVolume(){
        if(preview!=null)try{float gain=voiceVolume.gain();preview.setVolume(gain,gain);}catch(IllegalStateException ignored){}
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        return voiceVolume!=null&&voiceVolume.key(event)||super.dispatchKeyEvent(event);
    }
    private void stopPreview(){previewGeneration++;if(previewControl!=null){previewControl.setText("▶");previewControl.setEnabled(true);previewControl=null;}if(preview!=null){preview.release();preview=null;}if(previewFile!=null){previewFile.delete();previewFile=null;}}
    private void preflight(){
        if(profile.voice().isEmpty()||profile.language().isEmpty()){openVoices();return;}
        BackendClient client=new BackendClient(this);if(!client.configured()){connectionSettings();return;}checking=true;
        network.execute(()->{try{JSONObject result=client.capabilities();if(!result.optBoolean("configured")||!result.optBoolean("bubble_configured"))throw new IllegalStateException("Demo backend isn’t ready. Check the connection settings.");handler.post(()->{checking=false;if(resumed&&!isFinishing())requestStart();});}
            catch(Exception e){handler.post(()->{checking=false;toast(e.getMessage());});}});
    }
    private void connectionSettings(){
        if(ListeningState.running||BubbleState.enabled){toast("Disable Any Voice before changing the connection.");return;}
        BackendClient saved=new BackendClient(this);LinearLayout fields=column();fields.setPadding(dp(24),dp(12),dp(24),dp(16));label(fields,"Demo connection",23,INK,700);
        EditText url=field(fields,"Backend address",saved.url(),false),token=field(fields,"App access token",saved.access(),true);
        label(fields,"Voice engine",12,MUTED,600);Spinner mode=new Spinner(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Fast streaming · Agora + Murf","Agora Conversational AI Engine"});mode.setAdapter(adapter);mode.setSelection(saved.voiceProvider().equals("agora-engine")?1:0);fields.addView(mode);
        AlertDialog dialog=new AlertDialog.Builder(this).setView(fields).setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();dialog.setOnShowListener(v->dialog.getButton(-1).setOnClickListener(w->{try{BackendClient.save(this,url.getText().toString(),token.getText().toString(),mode.getSelectedItemPosition()==1?"agora-engine":"murf-direct");catalog=null;dialog.dismiss();toast("Connection saved");}catch(Exception e){url.setError(e.getMessage());}}));dialog.show();
    }
    private void requestStart(){
        if(ListeningState.running||LiveTranslation.isStopping())return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},10);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},11);return;}
        requestCapture();
    }
    private void requestCapture(){startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),20);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
                        if(request==10&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)requestStart();
        else if(request==11)requestCapture();else{BubbleState.translationPending=false;toast("Allow audio capture to start listening.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=20)return;if(result!=RESULT_OK||data==null){BubbleState.translationPending=false;return;}startForegroundService(new Intent(this,ListeningService.class).setAction(ListeningService.START).putExtra("result",result).putExtra("projection",data));}
    private void command(String action){startService(new Intent(this,ListeningService.class).setAction(action));}
    @Override protected void onResume(){super.onResume();resumed=true;handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onPause(){resumed=false;stopPreview();handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){stopPreview();if(picker!=null)picker.dismiss();network.shutdownNow();super.onDestroy();}
    private void updateText(TextView view,String value){if(!android.text.TextUtils.equals(view.getText(),value))view.setText(value);}
    private void toast(String value){if(!isFinishing()&&!isDestroyed())Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);return view;}
    private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private GradientDrawable shape(int color,int radius){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(radius));return shape;}
    private void space(LinearLayout parent,int height){parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(height)));}
    private LinearLayout card(LinearLayout parent){LinearLayout view=column();view.setPadding(dp(17),dp(15),dp(17),dp(15));GradientDrawable shape=shape(Color.WHITE,20);shape.setStroke(dp(1),LINE);view.setBackground(shape);parent.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;}
    private TextView label(LinearLayout parent,String value,int size,int color,int weight){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setTypeface(Typeface.create(Typeface.create("sans-serif",Typeface.NORMAL),weight,false));view.setPadding(0,dp(4),0,dp(4));view.setLineSpacing(dp(2),1);parent.addView(view);return view;}
    private TextView chip(String value,int color,int background){TextView view=new TextView(this);view.setText(value);view.setTextColor(color);view.setTextSize(12);view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));view.setGravity(Gravity.CENTER);view.setBackground(shape(background,14));return view;}
    private TextView action(LinearLayout parent,String value,int background,int color,View.OnClickListener listener){TextView view=chip(value,color,background);view.setTextSize(16);view.setMinHeight(dp(54));view.setPadding(dp(12),dp(12),dp(12),dp(12));view.setContentDescription(value);view.setFocusable(true);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(0,dp(4),0,dp(4));parent.addView(view,lp);view.setOnClickListener(v->{if(ValueAnimator.areAnimatorsEnabled()){v.animate().scaleX(.98f).scaleY(.98f).setDuration(80).withEndAction(()->v.animate().scaleX(1).scaleY(1).setDuration(120).start()).start();}listener.onClick(v);});return view;}
    private EditText field(LinearLayout parent,String hint,String value,boolean password){EditText view=new EditText(this);view.setSingleLine(true);view.setHint(hint);view.setText(value);view.setTextSize(16);view.setTextColor(INK);view.setPadding(dp(16),dp(10),dp(16),dp(10));view.setBackground(shape(Color.WHITE,14));view.setInputType(password?InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD:InputType.TYPE_CLASS_TEXT);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.setMargins(0,dp(7),0,dp(7));parent.addView(view,lp);return view;}
    private void enter(View view){if(ValueAnimator.areAnimatorsEnabled()){view.setAlpha(0);view.setTranslationY(dp(10));view.animate().alpha(1).translationY(0).setDuration(350).setInterpolator(new DecelerateInterpolator()).start();}}
}
