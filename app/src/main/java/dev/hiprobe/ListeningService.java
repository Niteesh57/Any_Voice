package dev.hiprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import org.json.JSONObject;

/** One user-enabled capture owner, one floating panel and one cloud input at a time. */
public final class ListeningService extends Service {
    private static volatile ListeningService current;
    static void screenOverlay(boolean show){ListeningService service=current;if(service!=null&&service.floating!=null)service.floating.visible(show);}
    static void cancelScreen(){ScreenTools.cancel();ListeningService service=current;if(service!=null&&service.connection!=null)service.connection.cancelResearch();}
    static final String START="dev.hiprobe.START",STOP="dev.hiprobe.STOP",ASK="dev.hiprobe.ASK";
    static final String CONVERSATION_START="dev.hiprobe.CONVERSATION_START",CONVERSATION_STOP="dev.hiprobe.CONVERSATION_STOP";
    private static final String CHANNEL="probe";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AutoModePolicy policy=new AutoModePolicy();
    private VolumeGuard volume;
    private MediaProjection projection;
    private AudioRecord recorder,microphone;
    private Thread captureThread,micThread;
    private volatile boolean capturing,micCapturing;
    private volatile LiveTranslation connection;
    private volatile boolean mediaMode,joined;
    private volatile boolean requestedMedia;
    private volatile long attempt;
    private FloatingControl floating;
    private long enabledAt,modeSince;
    private volatile long lastSentMediaSignal;
    private String notified="";
    private volatile boolean hadAnswer;
    private JSONObject pendingConversation;
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback(){public void onStop(){finish("Android ended audio capture. Enable Any Voice again.");}};
    private final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())){if(ConversationState.busy())finish("Conversation stopped on lock. Start manually to listen again.");else stopMicrophone();}}};
    @Override public void onCreate(){
        super.onCreate();current=this;volume=new VolumeGuard(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Any Voice",NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,filter);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){finish("Disabled. Original audio restored.");return START_NOT_STICKY;}
        if(CONVERSATION_START.equals(intent.getAction())){armConversation(intent);return START_NOT_STICKY;}
        if(CONVERSATION_STOP.equals(intent.getAction())){pendingConversation=null;ConversationState.requested=false;ConversationState.stopping=true;ConversationState.listening=false;ConversationState.message="Finishing audio · stopping conversation";stopMicrophone();if(connection!=null)connection.stopConversation();else ConversationState.clear();return START_NOT_STICKY;}
        if(ASK.equals(intent.getAction())){askBubble();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())||capturing)return START_NOT_STICKY;
        if(intent.hasExtra("conversation_config"))armConversation(intent);
        try{
            if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission is required");
            int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if(Build.VERSION.SDK_INT>=30)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(1,notification(),types);
            Intent data=intent.getParcelableExtra("projection");if(data==null)throw new IllegalArgumentException("Audio capture approval missing");
            projection=getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result",0),data);
            if(projection==null)throw new IllegalStateException("Audio capture unavailable");
            projection.registerCallback(projectionCallback,main);
            AudioPlaybackCaptureConfiguration config=new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).excludeUid(android.os.Process.myUid()).build();
            AudioFormat format=format();int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            recorder=new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum*2,6400)).setAudioPlaybackCaptureConfig(config).build();
            recorder.startRecording();if(recorder.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Audio capture could not start");
            capturing=true;ListeningState.running=true;BubbleState.enabled=true;BubbleState.active=false;BubbleState.inputAllowed=false;
            ListeningState.frames=0;ListeningState.detectedLanguage="";ListeningState.sourceText="";ListeningState.translatedText="";ListeningState.replacing=false;
            enabledAt=SystemClock.elapsedRealtime();modeSince=enabledAt;ListeningState.message="Checking app audio";BubbleState.message=ListeningState.message;
            floating=new FloatingControl(this,()->finish("Disabled. Original audio restored."),false,this::askBubble);floating.show();
            captureThread=new Thread(this::readMedia,"PlaybackCapture");captureThread.start();main.post(tick);
        }catch(Exception e){finish("Could not enable: "+e.getMessage());}
        return START_NOT_STICKY;
    }
    private static AudioFormat format(){return new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();}
    private boolean unlocked(){return getSystemService(PowerManager.class).isInteractive()&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private void readMedia(){
        short[] samples=new short[320],silence=new short[320];
        try{while(capturing){int count=recorder.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(!capturing)break;if(count<0)throw new IllegalStateException("App audio read failed");if(count==0)continue;
            double energy=0;for(int i=0;i<count;i++)energy+=(double)samples[i]*samples[i];double rms=Math.sqrt(energy/count)/32768.;double db=rms>0?Math.max(-96,20*Math.log10(rms)):-96;
            long now=SystemClock.elapsedRealtime();synchronized(policy){policy.observe(db,now);}
            ListeningState.db=db;ListeningState.frames++;
            // Live edge only: frames captured before the new RTC connection is ready are discarded.
            // Replaying pre-switch audio at capture speed creates a backlog that can never catch up.
            boolean forced;synchronized(policy){forced=policy.forced(now);}
            LiveTranslation rtc=connection;
            if(joined&&rtc!=null){boolean live=mediaMode&&requestedMedia&&!forced&&!ConversationState.busy();rtc.send(live?samples:silence,count);if(live&&rms*32768>58)lastSentMediaSignal=now;}
            if(db>-60){if(ListeningState.replacing)ListeningState.mutedSignalFrames++;else ListeningState.audibleSignalFrames++;}
        }}catch(Exception e){if(capturing)main.post(()->finish("Capture stopped: "+e.getMessage()));}
    }
    private void startMicrophone(long generation){
        if(!unlocked()||micCapturing)return;
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new SecurityException("Microphone permission missing");
        int minimum=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        microphone=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION).setAudioFormat(format()).setBufferSizeInBytes(Math.max(minimum*2,6400)).build();
        microphone.startRecording();if(microphone.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IllegalStateException("Microphone unavailable");
        AudioRecord source=microphone;micCapturing=true;
        micThread=new Thread(()->{short[] samples=new short[320],silence=new short[320];try{while(micCapturing&&generation==attempt){int count=source.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(!micCapturing||generation!=attempt)break;if(count<0)throw new IllegalStateException("Microphone read failed");if(count==0)continue;
            boolean allowMicrophone;synchronized(policy){allowMicrophone=policy.microphoneAllowed(SystemClock.elapsedRealtime());}
            LiveTranslation rtc=connection;if(rtc!=null){
                if(ConversationState.active)rtc.sendConversation(ConversationState.requested&&ConversationState.listening&&rtc.microphoneSafe()&&unlocked()?samples:silence,count);
                else rtc.sendMicrophone(!ConversationState.busy()&&BubbleState.inputAllowed&&allowMicrophone&&!mediaMode&&!requestedMedia&&unlocked()?samples:silence,count);
            }
        }}catch(Exception e){if(micCapturing&&generation==attempt)main.post(()->{if(generation==attempt)finish("Microphone stopped. Enable Any Voice again.");});}},"BubbleMicrophone");micThread.start();
    }
    private void stopMicrophone(){
        micCapturing=false;if(microphone!=null){try{microphone.stop();}catch(RuntimeException ignored){}if(micThread!=null)try{micThread.join(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}microphone.release();microphone=null;}micThread=null;
    }
    private void openConnection(boolean media){
        if(!capturing||connection!=null||LiveTranslation.isStopping())return;
        mediaMode=media;requestedMedia=media;modeSince=SystemClock.elapsedRealtime();long generation=++attempt;joined=false;hadAnswer=false;
        if(media){setReplacement(true);ListeningState.sourceText="";ListeningState.translatedText="";}
        BubbleState.active=!media;BubbleState.inputAllowed=false;BubbleState.phase="connecting";
        ListeningState.message=media?"Connecting media translation":"Connecting Bubble";BubbleState.message=ListeningState.message;
        try{
            connection=new LiveTranslation(this,new LiveTranslation.Listener(){
                public void ready(){main.post(()->{if(generation!=attempt||!capturing)return;joined=true;sendPendingConversation();});}
                public void routing(boolean translated){
                    if(!mediaMode)return;
                    java.util.concurrent.FutureTask<Void> task=new java.util.concurrent.FutureTask<>(()->{if(generation==attempt&&capturing)setReplacement(translated);return null;});
                    main.post(task);try{task.get(2,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){task.cancel(false);throw new IllegalStateException("Could not route media volume",e);}
                }
                public void failed(String message){main.post(()->{if(generation==attempt&&capturing)finish(message);});}
                public void status(JSONObject data){if(generation!=attempt||!capturing)return;
                    boolean actualMedia="media".equals(data.optString("input_mode","media"));
                    boolean actualConversation="bidir".equals(data.optString("input_mode"));
                    ConversationState.active=actualConversation;ConversationState.listening=data.optBoolean("conversation_listening");
                    if(actualConversation){ConversationState.message=data.optString("message");ConversationState.source=data.optString("source_text");ConversationState.translated=data.optString("translated_text");ConversationState.speaker=data.optString("conversation_speaker");ConversationState.id=data.optString("conversation_id");}
                    if(!actualConversation&&ConversationState.stopping&&!"bidir".equals(data.optString("desired_input"))){ConversationState.clear();ConversationState.message="Stopped. Previous mode restored.";}
                    main.post(()->{if(generation!=attempt||!capturing)return;
                        if(actualMedia!=mediaMode){mediaMode=actualMedia;modeSince=SystemClock.elapsedRealtime();if(actualMedia){stopMicrophone();setReplacement(true);}}
                        boolean wantMic=actualConversation&&ConversationState.requested||!actualConversation&&!actualMedia&&!ConversationState.busy();
                        if(wantMic&&unlocked()&&!micCapturing)try{startMicrophone(generation);}catch(Exception e){finish("Microphone could not start");}
                        else if(!wantMic&&micCapturing)stopMicrophone();
                    });
                    BubbleState.active=!actualMedia&&!actualConversation&&!ConversationState.busy();
                    if(!actualMedia)BubbleState.translationPending=false;
                    BubbleState.phase=data.optString("bubble_state");BubbleState.message=data.optString("message");BubbleState.reply=data.optString("translated_text");BubbleState.heard=data.optString("source_text");BubbleState.inputAllowed=data.optBoolean("input_allowed");BubbleState.waitingForReply=data.optBoolean("waiting_for_user");BubbleState.screenBusy=data.optBoolean("screen_research");
                    if(!actualMedia)ListeningState.message=BubbleState.message;
                    if("speaking".equals(BubbleState.phase))hadAnswer=true;
                }
            },!media,true);connection.start();
        }catch(Exception e){finish("Could not connect: "+e.getMessage());}
    }
    private void closeConnection(){
        attempt++;joined=false;BubbleState.inputAllowed=false;BubbleState.active=false;stopMicrophone();
        LiveTranslation old=connection;connection=null;if(old!=null)old.close();
        ListeningState.replacing=false;try{volume.restore();}catch(RuntimeException e){ListeningState.message="Restore your media volume with the phone buttons";}
    }
    private void askBubble(){
        if(!capturing)return;
        if(ConversationState.busy()){startActivity(new Intent(this,ConversationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;}
        long now=SystemClock.elapsedRealtime();boolean asking;synchronized(policy){asking=!policy.forced(now);if(asking)policy.ask(now);else policy.automatic();}
        BubbleState.translationPending=asking&&mediaMode;
        if(asking&&mediaMode)volume.mute();
    }
    private void setReplacement(boolean enabled){if(enabled)volume.mute();else volume.restore();ListeningState.replacing=enabled;}
    private void armConversation(Intent intent){try{pendingConversation=new JSONObject(intent.getStringExtra("conversation_config"));ConversationState.requested=true;ConversationState.stopping=false;ConversationState.message="Preparing conversation translator";stopMicrophone();sendPendingConversation();}catch(Exception e){ConversationState.clear();ConversationState.message="Choose languages again";}}
    private void sendPendingConversation(){if(joined&&connection!=null&&pendingConversation!=null){JSONObject config=pendingConversation;pendingConversation=null;connection.startConversation(config);}}
    private final Runnable tick=new Runnable(){public void run(){
        if(!capturing)return;
        long now=SystemClock.elapsedRealtime();boolean wanted,forced;synchronized(policy){policy.holdConversation(BubbleState.translationPending||BubbleState.waitingForReply||!mediaMode&&("thinking".equals(BubbleState.phase)||"speaking".equals(BubbleState.phase)));wanted=policy.wantsMedia(now);forced=policy.forced(now);}
        ListeningState.manualConversation=forced;
        boolean locked=!unlocked();
        if(locked&&ConversationState.busy()){finish("Conversation stopped on lock. Start manually to listen again.");return;}
        if(locked&&micCapturing)stopMicrophone();
        if(!locked||wanted){
            if(!ConversationState.busy()&&connection!=null&&joined&&wanted!=requestedMedia){
                requestedMedia=wanted;connection.input(wanted);
                if(!wanted){BubbleState.inputAllowed=false;ListeningState.message="Finishing translated audio before Bubble";}
            }
            if(connection==null&&!LiveTranslation.isStopping()&&now-enabledAt>900)openConnection(ConversationState.busy()?false:wanted);
        }
        if(!mediaMode&&connection!=null&&forced&&hadAnswer&&"listening".equals(BubbleState.phase)){synchronized(policy){policy.automatic();}hadAnswer=false;}
        try{
            if(ConversationState.busy())volume.mute();
            else if(mediaMode&&ListeningState.replacing){if(!volume.isMuted())volume.mute();}
            else if(!mediaMode&&forced&&connection!=null)volume.mute();
            else if(!mediaMode)volume.restore();
        }catch(RuntimeException e){finish("Volume routing failed: "+e.getMessage());return;}
        if(locked&&!wanted){ListeningState.message="Locked · microphone paused";BubbleState.message=ListeningState.message;}
        if(floating!=null)floating.update();notifyState();main.postDelayed(this,200);
    }};
    private Notification notification(){
        int flags=PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),flags);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,ListeningService.class).setAction(STOP),flags);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("Any Voice enabled").setContentText(ListeningState.message).setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"Disable",stop).build()).build();
    }
    private void notifyState(){if(!notified.equals(ListeningState.message)){notified=ListeningState.message;getSystemService(NotificationManager.class).notify(1,notification());}}
    private void finish(String message){ListeningState.message=message;if(ConversationState.busy())ConversationState.message=message;cleanup();stopSelf();}
    private void cleanup(){
        pendingConversation=null;ConversationState.clear();
        ScreenTools.cancel();capturing=false;ListeningState.running=false;ListeningState.manualConversation=false;BubbleState.enabled=false;BubbleState.translationPending=false;BubbleState.waitingForReply=false;BubbleState.screenBusy=false;
        closeConnection();main.removeCallbacksAndMessages(null);
        if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}if(captureThread!=null)try{captureThread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}recorder.release();recorder=null;}captureThread=null;
        if(projection!=null){projection.unregisterCallback(projectionCallback);projection.stop();projection=null;}
        if(floating!=null){floating.close();floating=null;}ListeningState.db=-96;stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy(){if(current==this)current=null;cleanup();try{unregisterReceiver(screen);}catch(IllegalArgumentException ignored){}super.onDestroy();}
    @Override public void onTaskRemoved(Intent root){finish("Disabled. Original audio restored.");super.onTaskRemoved(root);}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(floating!=null)floating.reposition();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){out.println("Unified enabled: "+capturing+" Mode: "+(mediaMode?"media":"bubble")+" Joined: "+joined+" Microphone: "+micCapturing);synchronized(policy){out.println("Media wanted: "+policy.wantsMedia(SystemClock.elapsedRealtime())+" Ask override: "+policy.forced(SystemClock.elapsedRealtime()));}out.println(ListeningState.describe());}
}
