package dev.hiprobe;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import org.json.JSONObject;
import java.util.ArrayDeque;

/** One user-enabled capture owner, one floating panel and one cloud input at a time. */
public final class ListeningService extends Service {
    static final String START="dev.hiprobe.START",STOP="dev.hiprobe.STOP",ASK="dev.hiprobe.ASK";
    private static final String CHANNEL="probe";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AutoModePolicy policy=new AutoModePolicy();
    private final ArrayDeque<short[]> mediaBuffer=new ArrayDeque<>();
    private VolumeGuard volume;
    private MediaProjection projection;
    private AudioRecord recorder,microphone;
    private Thread captureThread,micThread;
    private volatile boolean capturing,micCapturing;
    private volatile LiveTranslation connection;
    private volatile boolean mediaMode,joined;
    private volatile long attempt;
    private FloatingControl floating;
    private long enabledAt,modeSince;
    private volatile long lastSentMediaSignal;
    private String notified="";
    private volatile boolean hadAnswer;
    private final MediaProjection.Callback projectionCallback=new MediaProjection.Callback(){public void onStop(){finish("Android ended audio capture. Enable Any Voice again.");}};
    private final BroadcastReceiver screen=new BroadcastReceiver(){public void onReceive(Context c,Intent i){if(Intent.ACTION_SCREEN_OFF.equals(i.getAction())&&!mediaMode)closeConnection();}};
    @Override public void onCreate(){
        super.onCreate();volume=new VolumeGuard(this);
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL,"Any Voice",NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screen,filter);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(STOP.equals(intent.getAction())){finish("Disabled. Original audio restored.");return START_NOT_STICKY;}
        if(ASK.equals(intent.getAction())){askBubble();return START_NOT_STICKY;}
        if(!START.equals(intent.getAction())||capturing)return START_NOT_STICKY;
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
        short[] samples=new short[320];
        try{while(capturing){int count=recorder.read(samples,0,samples.length,AudioRecord.READ_BLOCKING);if(!capturing)break;if(count<0)throw new IllegalStateException("App audio read failed");if(count==0)continue;
            double energy=0;for(int i=0;i<count;i++)energy+=(double)samples[i]*samples[i];double rms=Math.sqrt(energy/count)/32768.;double db=rms>0?Math.max(-96,20*Math.log10(rms)):-96;
            long now=SystemClock.elapsedRealtime();synchronized(policy){policy.observe(db,now);}
            ListeningState.db=db;ListeningState.frames++;
            synchronized(mediaBuffer){
                mediaBuffer.addLast(java.util.Arrays.copyOf(samples,count));
                boolean forced,mediaPending;synchronized(policy){forced=policy.forced(now);mediaPending=policy.wantsMedia(now);}
                int limit=mediaMode||mediaPending?1000:100;while(mediaBuffer.size()>limit)mediaBuffer.removeFirst();
                LiveTranslation rtc=connection;
                if(mediaMode&&joined&&rtc!=null&&!forced){short[] frame=mediaBuffer.pollFirst();if(frame!=null){rtc.send(frame,frame.length);double sum=0;for(short sample:frame)sum+=(double)sample*sample;if(Math.sqrt(sum/frame.length)>58)lastSentMediaSignal=now;}}
            }
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
            LiveTranslation rtc=connection;if(rtc!=null)rtc.send(BubbleState.inputAllowed&&allowMicrophone?samples:silence,count);
        }}catch(Exception e){if(micCapturing&&generation==attempt)main.post(()->{if(generation==attempt)finish("Microphone stopped. Enable Any Voice again.");});}},"BubbleMicrophone");micThread.start();
    }
    private void stopMicrophone(){
        micCapturing=false;if(microphone!=null){try{microphone.stop();}catch(RuntimeException ignored){}if(micThread!=null)try{micThread.join(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}microphone.release();microphone=null;}micThread=null;
    }
    private void openConnection(boolean media){
        if(!capturing||connection!=null||LiveTranslation.isStopping())return;
        mediaMode=media;modeSince=SystemClock.elapsedRealtime();long generation=++attempt;joined=false;hadAnswer=false;
        BubbleState.active=!media;BubbleState.inputAllowed=false;BubbleState.phase="connecting";
        ListeningState.message=media?"Connecting media translation":"Connecting Bubble";BubbleState.message=ListeningState.message;
        try{
            connection=new LiveTranslation(this,new LiveTranslation.Listener(){
                public void ready(){main.post(()->{if(generation!=attempt||!capturing)return;joined=true;if(!media)try{startMicrophone(generation);}catch(Exception e){finish("Could not start microphone: "+e.getMessage());}});}
                public void routing(boolean translated){
                    if(!media)return;
                    java.util.concurrent.FutureTask<Void> task=new java.util.concurrent.FutureTask<>(()->{if(generation==attempt&&capturing)setReplacement(translated);return null;});
                    main.post(task);try{task.get(2,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception e){task.cancel(false);throw new IllegalStateException("Could not route media volume",e);}
                }
                public void failed(String message){main.post(()->{if(generation==attempt&&capturing)finish(message);});}
                public void status(JSONObject data){if(media||generation!=attempt||!capturing)return;
                    BubbleState.phase=data.optString("bubble_state");BubbleState.message=data.optString("message");BubbleState.reply=data.optString("translated_text");BubbleState.heard=data.optString("source_text");BubbleState.inputAllowed=data.optBoolean("input_allowed");
                    ListeningState.message=BubbleState.message;
                    if("speaking".equals(BubbleState.phase))hadAnswer=true;
                }
            },!media);connection.start();
        }catch(Exception e){finish("Could not connect: "+e.getMessage());}
    }
    private void closeConnection(){
        attempt++;joined=false;BubbleState.inputAllowed=false;BubbleState.active=false;stopMicrophone();
        LiveTranslation old=connection;connection=null;if(old!=null)old.close();
        ListeningState.replacing=false;try{volume.restore();}catch(RuntimeException e){ListeningState.message="Restore your media volume with the phone buttons";}
    }
    private void askBubble(){
        if(!capturing)return;
        long now=SystemClock.elapsedRealtime();synchronized(policy){if(policy.forced(now)){policy.automatic();}else policy.ask(now);}
    }
    private void setReplacement(boolean enabled){if(enabled)volume.mute();else volume.restore();ListeningState.replacing=enabled;}
    private final Runnable tick=new Runnable(){public void run(){
        if(!capturing)return;
        long now=SystemClock.elapsedRealtime();boolean wanted,forced;synchronized(policy){wanted=policy.wantsMedia(now);forced=policy.forced(now);}
        ListeningState.manualConversation=forced;
        boolean locked=!unlocked();
        if(locked&&!mediaMode&&connection!=null)closeConnection();
        if(!locked||wanted){
            if(connection!=null&&wanted!=mediaMode){
                boolean responding=!mediaMode&&("thinking".equals(BubbleState.phase)||"speaking".equals(BubbleState.phase));
                boolean drained=connection.outputIdle()&&(!mediaMode||now-lastSentMediaSignal>6500);
                if(!responding&&drained&&now-modeSince>1500)closeConnection();
            }
            if(connection==null&&!LiveTranslation.isStopping()&&now-enabledAt>900)openConnection(wanted);
        }
        if(!mediaMode&&connection!=null&&forced&&hadAnswer&&"listening".equals(BubbleState.phase)){synchronized(policy){policy.automatic();}hadAnswer=false;}
        try{
            if(mediaMode&&ListeningState.replacing){if(!volume.isMuted())volume.mute();}
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
    private void finish(String message){ListeningState.message=message;cleanup();stopSelf();}
    private void cleanup(){
        capturing=false;ListeningState.running=false;ListeningState.manualConversation=false;BubbleState.enabled=false;BubbleState.translationPending=false;
        closeConnection();main.removeCallbacksAndMessages(null);
        if(recorder!=null){try{recorder.stop();}catch(RuntimeException ignored){}if(captureThread!=null)try{captureThread.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}recorder.release();recorder=null;}captureThread=null;
        synchronized(mediaBuffer){mediaBuffer.clear();}
        if(projection!=null){projection.unregisterCallback(projectionCallback);projection.stop();projection=null;}
        if(floating!=null){floating.close();floating=null;}ListeningState.db=-96;stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy(){cleanup();try{unregisterReceiver(screen);}catch(IllegalArgumentException ignored){}super.onDestroy();}
    @Override public void onTaskRemoved(Intent root){finish("Disabled. Original audio restored.");super.onTaskRemoved(root);}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(floating!=null)floating.reposition();}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){out.println("Unified enabled: "+capturing+" Mode: "+(mediaMode?"media":"bubble")+" Joined: "+joined+" Microphone: "+micCapturing);synchronized(policy){out.println("Media wanted: "+policy.wantsMedia(SystemClock.elapsedRealtime())+" Ask override: "+policy.forced(SystemClock.elapsedRealtime()));}out.println(ListeningState.describe());}
}
