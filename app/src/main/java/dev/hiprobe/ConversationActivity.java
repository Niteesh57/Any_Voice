package dev.hiprobe;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit face-to-face interpreter; configuration is separate from the floating bubble. */
public final class ConversationActivity extends Activity {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService network=Executors.newSingleThreadExecutor();
    private final ArrayList<JSONObject> languages=new ArrayList<>(),voices=new ArrayList<>();
    private JSONArray allVoices;
    private TextView languagePicker,voicePicker;
    private int selectedLanguage=-1,selectedVoice=-1;
    private EditText partner;
    private TextView state,original,translation,start;
    private BrandView animation;
    private UserProfile profile;
    private JSONObject pending;
    private boolean preparing,libraryReady;
    private final Runnable refresh=new Runnable(){public void run(){
        boolean busy=ConversationState.busy();text(start,ConversationState.stopping?"Finishing…":busy?"Stop":preparing?"Preparing…":"Start");start.setEnabled(!ConversationState.stopping&&!preparing&&!LiveTranslation.isStopping());
        languagePicker.setEnabled(!busy&&!preparing);voicePicker.setEnabled(!busy&&!preparing);partner.setEnabled(!busy&&!preparing);
        text(state,ConversationState.status(preparing));
        state.setContentDescription(ConversationState.status(preparing));
        text(original,ConversationState.source);text(translation,ConversationState.translated);
        original.setVisibility(ConversationState.source.isEmpty()?View.GONE:View.VISIBLE);translation.setVisibility(ConversationState.translated.isEmpty()?View.GONE:View.VISIBLE);animation.active(busy||preparing);
        handler.postDelayed(this,300);
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);profile=new UserProfile(this);
        getWindow().setStatusBarColor(MainActivity.PAPER);getWindow().setNavigationBarColor(MainActivity.PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(MainActivity.PAPER);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(28),dp(24),dp(36));scroll.addView(root);setContentView(scroll);
        TextView back=label(root,"←  Any Voice",16);back.setMinHeight(dp(48));back.setOnClickListener(v->finish());label(root,"Let’s talk.",32);
        label(root,"YOU",11);label(root,profile.languageName()+" · "+profile.voiceName(),17);
        TextView direction=label(root,"⇅",24);direction.setTextColor(MainActivity.GREEN);direction.setGravity(Gravity.CENTER);
        label(root,"THEM",11);languagePicker=choice(root,"Choose language  ⌄");voicePicker=choice(root,"Choose voice  ⌄");
        languagePicker.setOnClickListener(v->{if(ConversationState.busy()||preparing||!libraryReady)return;ArrayList<String> names=new ArrayList<>();for(JSONObject row:languages)names.add(row.optString("name"));ChoiceSheet.show(this,"Their language",names,selectedLanguage,index->{if(ConversationState.busy()||preparing)return;selectedLanguage=index;languagePicker.setText(names.get(index)+"  ⌄");loadVoices(index);});});
        voicePicker.setOnClickListener(v->{if(ConversationState.busy()||preparing)return;ArrayList<String> names=new ArrayList<>();for(JSONObject row:voices)names.add(row.optString("name")+" · "+row.optString("gender"));if(names.isEmpty()){toast("Choose a language first");return;}ChoiceSheet.show(this,"Their voice",names,selectedVoice,index->{if(ConversationState.busy()||preparing)return;selectedVoice=index;voicePicker.setText(names.get(index)+"  ⌄");});});
        partner=new EditText(this);partner.setHint("Their name (optional)");partner.setText(getSharedPreferences("conversation_settings",MODE_PRIVATE).getString("partner_name",""));partner.setSingleLine(true);partner.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});root.addView(partner,new LinearLayout.LayoutParams(-1,dp(56)));
        partner.setTextSize(14);partner.setTextColor(MainActivity.INK);partner.setHintTextColor(MainActivity.MUTED);
        TextView info=label(root,"Take turns  ·  ⓘ",12);info.setTextColor(MainActivity.GREEN);info.setMinHeight(dp(48));info.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Conversation mode").setMessage("Take turns. Listening pauses while the translated voice plays.\n\nText is saved privately for this phone so Bubble can recall it. Names are inferred from language, not verified identities.\n\nLocking stops this mode. Media translation and Bubble resume when you stop the conversation.").setPositiveButton("Got it",null).show());
        animation=new BrandView(this,false,MainActivity.GREEN,true);root.addView(animation,new LinearLayout.LayoutParams(-1,dp(96)));
        state=label(root,ConversationState.message,14);start=label(root,"Start conversation",18);start.setGravity(Gravity.CENTER);start.setTextColor(-1);start.setBackground(shape(MainActivity.GREEN));root.addView(new VoiceVolumeView(this,MainActivity.GREEN,()->{}));
        start.setOnClickListener(v->{if(ConversationState.busy()){startService(new Intent(this,ListeningService.class).setAction(ListeningService.CONVERSATION_STOP));return;}begin();});
        original=label(root,"",14);original.setTextColor(MainActivity.MUTED);translation=label(root,"",20);
        languagePicker.setText("Loading languages…");
        network.execute(()->{try{JSONObject data=new BackendClient(this).voices();allVoices=data.getJSONArray("voices");JSONArray rows=data.getJSONArray("languages");ArrayList<String> names=new ArrayList<>();names.add("Choose other person’s language");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("id").split("-")[0].equals(profile.language().split("-")[0]))continue;languages.add(row);names.add(row.getString("name"));}
            handler.post(()->{if(!isFinishing()){libraryReady=true;languagePicker.setText("Choose language  ⌄");String savedLanguage=getSharedPreferences("conversation_settings",MODE_PRIVATE).getString("other_language","");for(int n=0;n<languages.size();n++)if(savedLanguage.equals(languages.get(n).optString("id"))){selectedLanguage=n;languagePicker.setText(languages.get(n).optString("name")+"  ⌄");loadVoices(n);}}});
        }catch(Exception e){handler.post(()->ConversationState.message="Voice library unavailable. Reopen this screen to retry.");}});
    }
    private void loadVoices(int index){voices.clear();selectedVoice=-1;ArrayList<String> names=new ArrayList<>();
        if(index>=0&&index<languages.size()&&allVoices!=null){String locale=languages.get(index).optString("id");for(int i=0;i<allVoices.length();i++){JSONObject voice=allVoices.optJSONObject(i);JSONArray supported=voice.optJSONArray("locales");boolean match=false;for(int j=0;supported!=null&&j<supported.length();j++)if(locale.equals(supported.optString(j)))match=true;if(match){voices.add(voice);names.add(voice.optString("name")+" · "+voice.optString("gender"));}}}
        voicePicker.setText(voices.isEmpty()?"No voices available":"Choose voice  ⌄");
        if(index>=0&&index<languages.size()){android.content.SharedPreferences saved=getSharedPreferences("conversation_settings",MODE_PRIVATE);if(languages.get(index).optString("id").equals(saved.getString("other_language","")))for(int n=0;n<voices.size();n++)if(voices.get(n).optString("id").equals(saved.getString("other_voice",""))){selectedVoice=n;voicePicker.setText(names.get(n)+"  ⌄");}}
    }
    private void begin(){try{
        int language=selectedLanguage,voice=selectedVoice;
        if(profile.voice().isEmpty()||profile.language().isEmpty()){toast("Choose your language and voice on the home screen first");return;}
        if(language<0||language>=languages.size()||voice<0||voice>=voices.size()){toast("Choose their language and a supported voice");return;}
        if(!new BackendClient(this).configured()){toast("Configure the demo connection on the home screen first");return;}
        pending=new JSONObject().put("other_language",languages.get(language).getString("id")).put("other_voice",voices.get(voice).getString("id")).put("my_name",profile.name()).put("partner_name",partner.getText().toString().trim());
        getSharedPreferences("conversation_settings",MODE_PRIVATE).edit().putString("other_language",pending.getString("other_language")).putString("other_voice",pending.getString("other_voice")).putString("partner_name",pending.getString("partner_name")).apply();
        if(ListeningState.running){startService(new Intent(this,ListeningService.class).setAction(ListeningService.CONVERSATION_START).putExtra("conversation_config",pending.toString()));pending=null;return;}
        preparing=true;requestStart();
    }catch(Exception e){preparing=false;toast(e.getMessage());}}
    private void requestStart(){if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},41);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);return;}
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),43);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==42)startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),43);else if(request==41&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)requestStart();else{preparing=false;pending=null;toast("Microphone permission is needed to translate");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=43)return;preparing=false;
        if(result==RESULT_OK&&data!=null&&pending!=null){startForegroundService(new Intent(this,ListeningService.class).setAction(ListeningService.START).putExtra("result",result).putExtra("projection",data).putExtra("conversation_config",pending.toString()));}pending=null;
    }
    private TextView label(LinearLayout root,String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextColor(MainActivity.INK);t.setTextSize(size);t.setTypeface(Typeface.create("sans-serif",size>=20?Typeface.BOLD:Typeface.NORMAL));t.setPadding(0,dp(9),0,dp(9));root.addView(t,new LinearLayout.LayoutParams(-1,-2));if(size==18)t.setMinHeight(dp(52));return t;}
    private TextView choice(LinearLayout root,String text){TextView view=label(root,text,16);view.setGravity(Gravity.CENTER_VERTICAL);view.setPadding(dp(16),0,dp(16),0);view.setBackground(shape(0xffeaf0e4));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(56));lp.bottomMargin=dp(10);view.setLayoutParams(lp);return view;}
    private void text(TextView view,String value){if(!view.getText().toString().equals(value))view.setText(value);}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
