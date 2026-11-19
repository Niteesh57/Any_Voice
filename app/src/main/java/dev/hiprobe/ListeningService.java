package dev.hiprobe;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.*;
import android.media.projection.*;
import android.os.*;




public final class ListeningService extends Service {
    static final String START = "dev.hiprobe.START", STOP = "dev.hiprobe.STOP";
    private static final String CHANNEL = "probe";
    private final Handler main = new Handler(Looper.getMainLooper());
    private VolumeGuard volume;
    private MediaProjection projection;
    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean capturing;
    private FloatingControl floating;
    private String failure;
    private LiveTranslation translation;
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { finish("Android ended the capture session. Media volume restored."); }
    };

    @Override public void onCreate() {
        super.onCreate();
        volume = new VolumeGuard(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Any Voice listening", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (STOP.equals(action)) { finish("Stopped. Media volume restored."); return START_NOT_STICKY; }
        if (!START.equals(action) || capturing) return START_NOT_STICKY;
        try {
            if (LiveTranslation.isStopping()) throw new IllegalStateException("Previous session is still stopping. Try again in a moment.");
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Audio capture permission has not been granted");
            }
            startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Intent data = intent.getParcelableExtra("projection");
            if (data == null) throw new IllegalArgumentException("Capture permission missing");
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(intent.getIntExtra("result", 0), data);
            if (projection == null) throw new IllegalStateException("Capture session unavailable");
            projection.registerCallback(projectionCallback, main);
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .excludeUid(android.os.Process.myUid()).build();
            AudioFormat format = new AudioFormat.Builder().setSampleRate(16000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("16 kHz capture format unavailable");
            recorder = new AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(Math.max(minimum * 2, 6400))
                .setAudioPlaybackCaptureConfig(config).build();
            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord did not initialize");
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new IllegalStateException("Capture did not start");
            ListeningState.frames = ListeningState.mutedSignalFrames = ListeningState.audibleSignalFrames = 0;
            ListeningState.db = -96;
            ListeningState.detectedLanguage = "";
            ListeningState.sourceText = ""; ListeningState.translatedText = ""; ListeningState.phrases = 0; ListeningState.playingChunk = 0;
            failure = null; capturing = true; ListeningState.running = true;
            ListeningState.replacing = false;
            {
                ListeningState.message = "Connecting to your translation backend…";
                translation = new LiveTranslation(this, new LiveTranslation.Listener() {
                    @Override public void ready() { main.post(() -> { if (capturing) ListeningState.message = "Detecting language - original audio"; }); }
                    @Override public void routing(boolean translated) {
                        java.util.concurrent.FutureTask<Void> change = new java.util.concurrent.FutureTask<>(() -> {
                            if (capturing) setReplacement(translated);
                            return null;
                        });
                        main.post(change);
                        try { change.get(2, java.util.concurrent.TimeUnit.SECONDS); }
                        catch (Exception e) { change.cancel(false); throw new IllegalStateException("Could not switch media audio", e); }
                    }
                    @Override public void failed(String message) { main.post(() -> { if (capturing) finish(message); }); }
                });
                translation.start();
            }
            if (!capturing) return START_NOT_STICKY;
            floating = new FloatingControl(this, () -> finish("Stopped. Original audio restored."));
            floating.show();
            captureThread = new Thread(this::readAudio, "PlaybackCapture"); captureThread.start();
            main.post(tick);
        } catch (Exception e) { finish("Could not start: " + e.getMessage()); }
        return START_NOT_STICKY;
    }

    private void readAudio() {
        short[] samples = new short[320];
        try {
            while (capturing) {
                int count = recorder.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
                if (!capturing) break;
                if (count < 0) throw new IllegalStateException("Audio read error " + count);
                if (count == 0) continue;
                double energy = 0;
                for (int i = 0; i < count; i++) energy += (double) samples[i] * samples[i];
                double rms = Math.sqrt(energy / count) / 32768.0;
                double db = rms > 0 ? Math.max(-96, 20 * Math.log10(rms)) : -96;
                ListeningState.db = db; ListeningState.frames++;
                LiveTranslation session = translation;
                if (session != null) session.send(samples, count);
                if (db > -60) {
                    if (ListeningState.replacing && volume.isMuted()) ListeningState.mutedSignalFrames++;
                    else if (!ListeningState.replacing && !volume.isMuted()) ListeningState.audibleSignalFrames++;
                }
            }
        } catch (Exception e) {
            if (capturing) main.post(() -> finish("Capture stopped: " + e.getMessage()));
        }
    }
    private void setReplacement(boolean enabled) {
        try {
            if (enabled) volume.mute(); else volume.restore();
            ListeningState.replacing = enabled;
            ListeningState.message = enabled ? "Translating to your language" : "Your language - original voice";
        } catch (RuntimeException e) { finish("Volume routing failed: " + e.getMessage()); }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!capturing) return;
            if (ListeningState.replacing && !volume.isMuted()) {
                finish("Volume changed. Original audio restored."); return;
            }
            if (floating != null) floating.update();
            main.postDelayed(this,350);
        }
    };
    private Notification notification() {
        int immutable = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),immutable);
        PendingIntent stop = PendingIntent.getService(this,1,new Intent(this,ListeningService.class).setAction(STOP),immutable);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Any Voice is listening").setContentText("Listen in " + new UserProfile(this).languageName())
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Stop listening",stop).build()).build();
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config) {
        super.onConfigurationChanged(config);if(floating!=null)floating.reposition();
    }
    private void finish(String message) {
        failure = message; cleanup(); stopSelf();
    }
    private void cleanup() {
        capturing = false; ListeningState.running = false; ListeningState.replacing = false;
        if (translation != null) { translation.close(); translation = null; }
        main.removeCallbacksAndMessages(null);
        if (recorder != null) {
            try { recorder.stop(); } catch (RuntimeException ignored) { }
            if (captureThread != null) {
                try { captureThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            recorder.release(); recorder = null;
        }
        captureThread = null;
        if (projection != null) { projection.unregisterCallback(projectionCallback); projection.stop(); projection = null; }
        if (floating != null) { floating.close(); floating = null; }
        try { volume.restore(); }
        catch (RuntimeException e) { failure = "Use the phone volume buttons to restore media volume. " + e.getMessage(); }
        ListeningState.db = -96; ListeningState.detectedLanguage = "";
        ListeningState.message = failure != null ? failure : "Session ended. Media volume restored.";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    @Override public void onTaskRemoved(Intent root) { finish("App dismissed. Media volume restored."); super.onTaskRemoved(root); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println(ListeningState.describe());
        writer.println("Media volume is zero: " + volume.isMuted());
    }
}
