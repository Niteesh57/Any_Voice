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
    private Spinner languagePicker,voicePicker;
    private EditText partner;
    private TextView state,original,translation,start;
    private BrandView animation;
    private UserProfile profile;
    private JSONObject pending;
    private boolean preparing;
    private final Runnable refresh=new Runnable(){public void run(){
        boolean busy=ConversationState.busy();start.setText(ConversationState.stopping?"Finishing…":busy?"Stop conversation":preparing?"Preparing…":"Start conversation");start.setEnabled(!ConversationState.stopping&&!preparing&&!LiveTranslation.isStopping());
        languagePicker.setEnabled(!busy&&!preparing);voicePicker.setEnabled(!busy&&!preparing);partner.setEnabled(!busy&&!preparing);
        state.setText(ConversationState.message);original.setText(ConversationState.source.isEmpty()?"Original words appear here":ConversationState.source);
        translation.setText(ConversationState.translated.isEmpty()?"Translated words appear here":ConversationState.translated);animation.active(busy);
        handler.postDelayed(this,300);
    }};
    @Override public void onCreate(Bundle saved){super.onCreate(saved);profile=new UserProfile(this);
        getWindow().setStatusBarColor(MainActivity.PAPER);getWindow().setNavigationBarColor(MainActivity.PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setBackgroundColor(MainActivity.PAPER);LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(24),dp(28),dp(24),dp(36));scroll.addView(root);setContentView(scroll);
        TextView back=label(root,"←  Any Voice",16);back.setMinHeight(dp(48));back.setOnClickListener(v->finish());label(root,"A conversation.\nTwo languages.",32);
        label(root,"Bidirectional translator",16);label(root,"Your language · "+profile.languageName()+"\nYour voice · "+profile.voiceName(),14);
        label(root,"Other person’s language",14);languagePicker=new Spinner(this);root.addView(languagePicker,new LinearLayout.LayoutParams(-1,dp(52)));
        label(root,"Voice for your translated replies",14);voicePicker=new Spinner(this);root.addView(voicePicker,new LinearLayout.LayoutParams(-1,dp(52)));
        partner=new EditText(this);partner.setHint("Their name (optional)");partner.setText(getSharedPreferences("conversation_settings",MODE_PRIVATE).getString("partner_name",""));partner.setSingleLine(true);partner.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});root.addView(partner,new LinearLayout.LayoutParams(-1,dp(56)));
        label(root,"Speak one at a time. Listening pauses while the translated voice plays. Names are labels inferred from language, not verified voice identities.",13);
        label(root,"Captured text is saved on the demo backend, isolated to this phone, for Bubble recall. Screen-off or locking stops this mode; it never restarts by itself.",13);
        animation=new BrandView(this,true,MainActivity.GREEN);root.addView(animation,new LinearLayout.LayoutParams(-1,dp(66)));
        state=label(root,ConversationState.message,14);start=label(root,"Start conversation",18);start.setGravity(Gravity.CENTER);start.setTextColor(-1);start.setBackground(shape(MainActivity.GREEN));root.addView(new VoiceVolumeView(this,MainActivity.GREEN,()->{}));
        start.setOnClickListener(v->{if(ConversationState.busy()){startService(new Intent(this,ListeningService.class).setAction(ListeningService.CONVERSATION_STOP));return;}begin();});
        label(root,"SAID · speaker inferred from language",11);original=label(root,"Original words appear here",16);label(root,"TRANSLATION",11);translation=label(root,"Translated words appear here",20);
        languagePicker.setAdapter(adapter(Collections.singletonList("Loading supported languages…")));voicePicker.setAdapter(adapter(Collections.singletonList("Choose a language first")));
        languagePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){loadVoices(position-1);}});
        network.execute(()->{try{JSONObject data=new BackendClient(this).voices();allVoices=data.getJSONArray("voices");JSONArray rows=data.getJSONArray("languages");ArrayList<String> names=new ArrayList<>();names.add("Choose other person’s language");
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("id").split("-")[0].equals(profile.language().split("-")[0]))continue;languages.add(row);names.add(row.getString("name"));}
            handler.post(()->{if(!isFinishing()){languagePicker.setAdapter(adapter(names));String savedLanguage=getSharedPreferences("conversation_settings",MODE_PRIVATE).getString("other_language","");for(int n=0;n<languages.size();n++)if(savedLanguage.equals(languages.get(n).optString("id")))languagePicker.setSelection(n+1);}});
        }catch(Exception e){handler.post(()->ConversationState.message="Voice library unavailable. Reopen this screen to retry.");}});
    }
    private void loadVoices(int index){voices.clear();ArrayList<String> names=new ArrayList<>();names.add("Choose a supported voice");
        if(index>=0&&index<languages.size()&&allVoices!=null){String locale=languages.get(index).optString("id");for(int i=0;i<allVoices.length();i++){JSONObject voice=allVoices.optJSONObject(i);JSONArray supported=voice.optJSONArray("locales");boolean match=false;for(int j=0;supported!=null&&j<supported.length();j++)if(locale.equals(supported.optString(j)))match=true;if(match){voices.add(voice);names.add(voice.optString("name")+" · "+voice.optString("gender"));}}}
        voicePicker.setAdapter(adapter(names));
        if(index>=0&&index<languages.size()){android.content.SharedPreferences saved=getSharedPreferences("conversation_settings",MODE_PRIVATE);if(languages.get(index).optString("id").equals(saved.getString("other_language","")))for(int n=0;n<voices.size();n++)if(voices.get(n).optString("id").equals(saved.getString("other_voice","")))voicePicker.setSelection(n+1);}
    }
    private void begin(){try{
        int language=languagePicker.getSelectedItemPosition()-1,voice=voicePicker.getSelectedItemPosition()-1;
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
    private ArrayAdapter<String> adapter(List<String> items){return new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items);}
    private GradientDrawable shape(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(18));return d;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private void toast(String text){Toast.makeText(this,text,Toast.LENGTH_LONG).show();}
    @Override protected void onResume(){super.onResume();handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
    @Override protected void onDestroy(){network.shutdownNow();super.onDestroy();}
}
