package dev.hiprobe;

import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.media.*;
import android.media.projection.*;
import android.os.*;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class ProbeService extends Service {
    static final String START = "dev.hiprobe.START", STOP = "dev.hiprobe.STOP";
    static final String TOGGLE = "dev.hiprobe.TOGGLE", TEST_VOICE = "dev.hiprobe.TEST_VOICE";
    private static final String CHANNEL = "probe";
    private final Handler main = new Handler(Looper.getMainLooper());
    private VolumeGuard volume;
    private MediaProjection projection;
    private AudioRecord recorder;
    private Thread captureThread;
    private volatile boolean capturing;
    private volatile long lastSignal;
    private TextToSpeech tts;
    private boolean voiceReady;
    private WindowManager windows;
    private LinearLayout panel;
    private TextView meter;
    private Button toggle;
    private long nextHi;
    private String failure;
    private final MediaProjection.Callback projectionCallback = new MediaProjection.Callback() {
        @Override public void onStop() { finish("Android ended the capture session. Media volume restored."); }
    };

    @Override public void onCreate() {
        super.onCreate();
        volume = new VolumeGuard(this);
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel(CHANNEL, "Audio feasibility test", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (STOP.equals(action)) { finish("Stopped. Media volume restored."); return START_NOT_STICKY; }
        if (TOGGLE.equals(action)) { if (capturing) setReplacement(!ProbeState.replacing); return START_NOT_STICKY; }
        if (TEST_VOICE.equals(action)) { if (capturing) speakHi(true); return START_NOT_STICKY; }
        if (!START.equals(action) || capturing) return START_NOT_STICKY;
        try {
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
            ProbeState.frames = ProbeState.mutedSignalFrames = ProbeState.audibleSignalFrames = 0;
            ProbeState.db = -96;
            lastSignal = 0; failure = null; capturing = true; ProbeState.running = true;
            setReplacement(true);
            if (!capturing) return START_NOT_STICKY;
            startVoice();
            createPanel();
            captureThread = new Thread(this::readAudio, "PlaybackCapture"); captureThread.start();
            main.post(tick);
        } catch (Exception e) { finish("Could not start: " + e.getMessage()); }
        return START_NOT_STICKY;
    }

    private void readAudio() {
        short[] samples = new short[1600];
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
                ProbeState.db = db; ProbeState.frames++;
                if (db > -60) {
                    lastSignal = SystemClock.elapsedRealtime();
                    if (ProbeState.replacing && volume.isMuted()) ProbeState.mutedSignalFrames++;
                    else if (!ProbeState.replacing && !volume.isMuted()) ProbeState.audibleSignalFrames++;
                }
            }
        } catch (Exception e) {
            if (capturing) main.post(() -> finish("Capture stopped: " + e.getMessage()));
        }
    }
    private void startVoice() {
        tts = new TextToSpeech(this, status -> {
            if (!capturing) return;
            if (status != TextToSpeech.SUCCESS) { ProbeState.voice = "Voice unavailable. Install/enable an English TTS voice."; return; }
            int language = tts.setLanguage(Locale.US);
            if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                ProbeState.voice = "English voice data missing. Download it in Android text-to-speech settings."; return;
            }
            tts.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build());
            tts.setSpeechRate(0.9f);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) { ProbeState.voice = "Speaking hi through alarm audio stream"; }
                @Override public void onDone(String id) { ProbeState.voice = "Hi voice ready (check alarm volume if silent)"; }
                @Override public void onError(String id) { ProbeState.voice = "TTS playback failed; check installed voice engine"; }
            });
            voiceReady = true; ProbeState.voice = "Hi voice ready (uses existing alarm volume)";
        });
    }
    private void speakHi(boolean manual) {
        if (!voiceReady || tts == null) { ProbeState.voice = "Voice engine not ready; check English TTS voice data"; return; }
        if (tts.isSpeaking()) return;
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 0.6f);
        if (tts.speak("Hi", TextToSpeech.QUEUE_FLUSH, params, manual ? "manual" : "captured") == TextToSpeech.ERROR) {
            ProbeState.voice = "Voice engine rejected hi playback";
        }
    }
    private void setReplacement(boolean enabled) {
        try {
            if (enabled) volume.mute(); else volume.restore();
            ProbeState.replacing = enabled; lastSignal = 0;
            if (tts != null) tts.stop();
            ProbeState.message = enabled ? "Waiting for capturable audio while media is silent."
                : "Comparison: original audio restored; hi is paused.";
        } catch (RuntimeException e) { finish("Volume routing failed: " + e.getMessage()); }
    }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!capturing) return;
            long now = SystemClock.elapsedRealtime();
            if (ProbeState.replacing && !volume.isMuted()) {
                setReplacement(false);
                ProbeState.message = "Media volume changed externally. Replacement paused; use Mute + hi to retry.";
            }
            if (ProbeState.replacing) {
                boolean signal = lastSignal > 0 && now - lastSignal < 1200;
                ProbeState.message = signal ? "Signal detected while muted. Check you hear ONLY hi."
                    : "No recent signal while muted. Try the original-audio comparison.";
                if (signal && now >= nextHi) { speakHi(false); nextHi = now + 900; }
            }
            if (meter != null) meter.setText((ProbeState.replacing ? "MUTED" : "ORIGINAL")
                + " | " + Math.round(ProbeState.db) + " dBFS\nMuted signal buffers: " + ProbeState.mutedSignalFrames);
            if (toggle != null) toggle.setText(ProbeState.replacing ? "Original (compare)" : "Mute + hi");
            main.postDelayed(this, 350);
        }
    };

    private Notification notification() {
        int immutable = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), immutable);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, ProbeService.class).setAction(STOP), immutable);
        PendingIntent compare = PendingIntent.getService(this, 2, new Intent(this, ProbeService.class).setAction(TOGGLE), immutable);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Any Voice is active").setContentText("Internal audio test • tap for diagnostics")
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(null, "Original / hi", compare).build())
            .addAction(new Notification.Action.Builder(null, "Stop + restore", stop).build()).build();
    }
    private void createPanel() {
        if (!Settings.canDrawOverlays(this)) return;
        try {
            windows = getSystemService(WindowManager.class);
            panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(10), dp(6), dp(10), dp(6)); panel.setBackgroundColor(Color.rgb(235, 245, 246));
            TextView drag = new TextView(this) {
                @Override public boolean performClick() { super.performClick(); return true; }
            };
            drag.setText("ANY VOICE  •  drag here"); drag.setTextColor(Color.BLACK);
            drag.setTextSize(14); drag.setMinHeight(dp(48)); panel.addView(drag);
            meter = new TextView(this); meter.setTextColor(Color.BLACK); meter.setTextSize(12); panel.addView(meter);
            toggle = panelButton("Original (compare)", v -> setReplacement(!ProbeState.replacing));
            panelButton("Test hi", v -> speakHi(true));
            panelButton("Stop + restore", v -> finish("Stopped from floating controls. Media volume restored."));
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(dp(220), WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.START; layout.x = dp(8); layout.y = dp(100);
            drag.setOnTouchListener(new View.OnTouchListener() {
                float x, y; int startX, startY;
                @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                        x = event.getRawX(); y = event.getRawY(); startX = layout.x; startY = layout.y; return true;
                    }
                    if (event.getAction() == android.view.MotionEvent.ACTION_MOVE) {
                        layout.x = Math.max(0, startX + (int)(event.getRawX() - x));
                        layout.y = Math.max(0, startY + (int)(event.getRawY() - y));
                        windows.updateViewLayout(panel, layout); return true;
                    }
                    if (event.getAction() == android.view.MotionEvent.ACTION_UP) { v.performClick(); return true; }
                    return false;
                }
            });
            windows.addView(panel, layout);
        } catch (RuntimeException e) {
            if (panel != null && panel.isAttachedToWindow()) windows.removeView(panel);
            panel = null; meter = null; toggle = null;
            ProbeState.message = "Floating controls unavailable. Use notification Stop or return to the app.";
        }
    }
    private Button panelButton(String text, View.OnClickListener action) {
        Button button = new Button(this); button.setText(text); button.setTextSize(12); button.setAllCaps(false);
        button.setMinHeight(dp(48)); button.setOnClickListener(action); panel.addView(button); return button;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void finish(String message) {
        failure = message; cleanup(); stopSelf();
    }
    private void cleanup() {
        capturing = false; ProbeState.running = false; ProbeState.replacing = false;
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
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        voiceReady = false;
        if (panel != null && windows != null) { windows.removeView(panel); panel = null; }
        meter = null; toggle = null;
        try { volume.restore(); }
        catch (RuntimeException e) { failure = "Use the phone volume buttons to restore media volume. " + e.getMessage(); }
        ProbeState.db = -96; ProbeState.voice = "Voice stopped";
        ProbeState.message = failure != null ? failure : "Session ended. Media volume restored.";
        stopForeground(STOP_FOREGROUND_REMOVE);
    }
    @Override public void onDestroy() { cleanup(); super.onDestroy(); }
    @Override public void onTaskRemoved(Intent root) { finish("App dismissed. Media volume restored."); super.onTaskRemoved(root); }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override protected void dump(java.io.FileDescriptor fd, java.io.PrintWriter writer, String[] args) {
        writer.println(ProbeState.describe());
        writer.println("Media volume is zero: " + volume.isMuted());
    }
}
