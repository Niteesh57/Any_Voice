package dev.hiprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.os.*;
import org.json.JSONObject;

/** User-started microphone foreground service. No capture while locked or translating. */
public final class BubbleService extends Service {
    static final String ENABLE="dev.hiprobe.BUBBLE_ENABLE",DISABLE="dev.hiprobe.BUBBLE_DISABLE",PAUSE="dev.hiprobe.BUBBLE_PAUSE";
    private static final String CHANNEL="bubble";
    private final Handler main=new Handler(Looper.getMainLooper());
    private AudioRecord recorder;
    private Thread capture;
    private volatile boolean recording;
    private LiveTranslation connection;
    private FloatingControl floating;
    private ToneGenerator chime;
    private String notified="";
    private boolean conversation;
    private volatile long attempt;
    private final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context context,Intent intent){
        if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))pause("Paused while phone is locked");
        else reconcile();
    }};
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Bubble voice assistant",NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,filter);
        main.post(tick);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null||DISABLE.equals(intent.getAction())){disable();return START_NOT_STICKY;}
        if(PAUSE.equals(intent.getAction())){pause("Paused during translation");return START_NOT_STICKY;}
        if(!ENABLE.equals(intent.getAction()))return START_NOT_STICKY;
        try {
            BubbleState.enabled=true;
            startForeground(2,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            BubbleState.message="Tap Ask Bubble in the floating window";
            reconcile();
        } catch(Exception e){fail("Could not enable Bubble: "+e.getMessage());}
        return START_NOT_STICKY;
    }
    private boolean unlocked(){return getSystemService(PowerManager.class).isInteractive()&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private void reconcile(){
        if(!BubbleState.enabled)return;
        if(!unlocked()){pause("Paused while phone is locked");return;}
        if(ListeningState.running||BubbleState.translationPending){pause("Paused during translation");return;}
        if(floating==null){floating=new FloatingControl(this,this::disable,true,this::startConversation);floating.show();}
        if(!conversation||connection!=null||LiveTranslation.isStopping())return;
        try {
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission is required");
            AudioFormat format=new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(format).setBufferSizeInBytes(Math.max(min*2,6400)).build();
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IllegalStateException("Microphone unavailable");
            recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone could not start");
            long generation=++attempt;BubbleState.active=true;BubbleState.inputAllowed=false;BubbleState.phase="connecting";BubbleState.message="Connecting Bubble through Agora";
            connection=new LiveTranslation(this,new LiveTranslation.Listener(){
                public void ready(){}
                public void routing(boolean translated){} // Bubble never changes media volume.
                public void failed(String message){main.post(()->{if(generation==attempt&&BubbleState.enabled)fail(message);});}
                public void status(JSONObject data){
                    if(generation!=attempt||!BubbleState.active)return;
                    BubbleState.phase=data.optString("bubble_state","standby");BubbleState.message=data.optString("message");
                    BubbleState.heard=data.optString("source_text");BubbleState.reply=data.optString("translated_text");BubbleState.inputAllowed=data.optBoolean("input_allowed");

                }
            },true);connection.start();recording=true;
            capture=new Thread(()->read(generation),"BubbleMicrophone");capture.start();
            notifyState();
        }catch(Exception e){fail("Bubble microphone failed: "+e.getMessage());}
    }
    private void read(long generation){
        AudioRecord source=recorder;short[] samples=new short[320],silence=new short[320];
        try {
            while(recording&&generation==attempt){int count=source.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(!recording||generation!=attempt)break;if(count<0)throw new IllegalStateException("Microphone read failed");if(count==0)continue;
                LiveTranslation rtc=connection;
                if(rtc!=null)rtc.send(BubbleState.inputAllowed?samples:silence,count);
            }
        }catch(Exception e){if(recording&&generation==attempt)main.post(()->{if(generation==attempt)fail("Bubble microphone stopped");});}
    }
    private void startConversation(){
        if(ListeningState.running||BubbleState.translationPending){android.widget.Toast.makeText(this,"Stop translation before asking Bubble",android.widget.Toast.LENGTH_SHORT).show();return;}
        if(conversation){conversation=false;pause("Tap Ask Bubble in the floating window");reconcile();return;}
        conversation=true;reconcile();
    }
    private void pause(String reason){
        conversation=false;
        if(floating!=null){floating.close();floating=null;}
        if(connection==null&&!recording){BubbleState.active=false;BubbleState.phase="paused";BubbleState.message=reason;notifyState();return;}
        attempt++;BubbleState.active=false;BubbleState.inputAllowed=false;recording=false;
        if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}if(capture!=null)try{capture.join(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}recorder.release();recorder=null;}
        capture=null;if(connection!=null){connection.close();connection=null;}
        if(floating!=null){floating.close();floating=null;}
        BubbleState.phase="paused";BubbleState.message=reason;notifyState();
    }
    private void fail(String message){BubbleState.enabled=false;pause(message);BubbleState.phase="error";BubbleState.message=message;stopSelf();}
    private void disable(){BubbleState.enabled=false;pause("Bubble disabled");BubbleState.phase="disabled";BubbleState.message="Bubble disabled";stopSelf();}
    private final Runnable tick=new Runnable(){public void run(){if(BubbleState.enabled){reconcile();if(floating!=null)floating.update();notifyState();}main.postDelayed(this,350);}};
    private Notification notification(){
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),flags);
        PendingIntent stop=PendingIntent.getService(this,2,new Intent(this,BubbleService.class).setAction(DISABLE),flags);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Bubble voice assistant")
            .setContentText(BubbleState.message).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Disable Bubble",stop).build()).build();
    }
    private void notifyState(){if(!BubbleState.enabled)return;String value=BubbleState.message;if(!value.equals(notified)){notified=value;getSystemService(NotificationManager.class).notify(2,notification());}}
    @Override public void onDestroy(){String finalPhase=BubbleState.phase,finalMessage=BubbleState.message;BubbleState.enabled=false;pause("Bubble disabled");BubbleState.phase=finalPhase;BubbleState.message=finalMessage;main.removeCallbacksAndMessages(null);try{unregisterReceiver(screen);}catch(IllegalArgumentException ignored){}if(chime!=null)chime.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){out.println("Enabled: "+BubbleState.enabled+" Active microphone: "+recording+" Phase: "+BubbleState.phase+" Upload permitted: "+BubbleState.inputAllowed);out.println(BubbleState.message);}
}
