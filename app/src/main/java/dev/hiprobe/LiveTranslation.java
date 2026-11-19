package dev.hiprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONObject;
import io.agora.rtc2.*;
import io.agora.rtc2.audio.AudioTrackConfig;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Agora custom input and external rendering. No microphone track or local source playback. */
final class LiveTranslation {
    private static volatile boolean stopping;
    static boolean isStopping() { return stopping; }
    interface Listener { void ready(); void failed(String message); }
    private final Context context;
    private final BackendClient backend;
    private final Listener listener;
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> upload = new ArrayBlockingQueue<>(100);
    private volatile RtcEngine engine;
    private volatile boolean joined;
    private volatile String sessionId;
    private volatile boolean outputEnabled = true;
    private int customTrack = -1;
    private int workerUid;
    private AudioTrack output;
    private Thread playback, publishing;

    LiveTranslation(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener; backend = new BackendClient(context);
    }
    void start() {
        network.execute(() -> {
            try {
                JSONObject data = backend.start();
                sessionId = data.getString("id");
                if (closed.get()) { stopRemote(); return; }
                workerUid = data.getInt("worker_uid");
                initialize(data);
                network.scheduleWithFixedDelay(this::poll, 0, 250, TimeUnit.MILLISECONDS);
                network.schedule(() -> { if (!joined && !closed.get()) fail("Could not join the audio channel. Check your connection."); }, 25, TimeUnit.SECONDS);
            } catch (Exception e) { if (!closed.get()) fail("Could not start: " + e.getMessage()); }
        });
    }
    private synchronized void initialize(JSONObject data) throws Exception {
        if (closed.get()) return;
        RtcEngineConfig config = new RtcEngineConfig();
        config.mContext = context; config.mAppId = data.getString("app_id");
        config.mEventHandler = new IRtcEngineEventHandler() {
            @Override public void onJoinChannelSuccess(String channel, int uid, int elapsed) {
                if (closed.get()) return;
                joined = true;
                listener.ready();
            }
            @Override public void onUserJoined(int uid, int elapsed) {
                RtcEngine rtc = engine;
                if (rtc != null && uid != workerUid) rtc.muteRemoteAudioStream(uid, true);
            }
            @Override public void onError(int code) { if (!closed.get()) fail("Audio channel error (" + code + "). Stop and try again."); }
            @Override public void onConnectionStateChanged(int state, int reason) {
                if (state == Constants.CONNECTION_STATE_FAILED && !closed.get()) fail("Audio connection failed. Stop and try again.");
            }
            @Override public void onTokenPrivilegeWillExpire(String token) { fail("Session is expiring. Stop and start a new session."); }
        };
        engine = RtcEngine.create(config);
        require(engine.enableAudio(), "enable audio");
        require(engine.enableLocalAudio(false), "disable microphone capture");
        require(engine.setExternalAudioSink(true, 48000, 1), "configure translated audio output");
        AudioTrackConfig trackConfig = new AudioTrackConfig();
        trackConfig.enableLocalPlayback = false;
        customTrack = engine.createCustomAudioTrack(Constants.AudioTrackType.AUDIO_TRACK_MIXABLE, trackConfig);
        if (customTrack < 0) throw new IllegalStateException("Could not create source audio track");
        int minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("Audio output format unavailable");
        output = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(minimum * 2, 9600)).setTransferMode(AudioTrack.MODE_STREAM).build();
        output.setVolume(0.75f); output.play();
        playback = new Thread(this::render, "TeluguPlayback"); playback.start();
        publishing = new Thread(this::publish, "EnglishUpload"); publishing.start();
        ChannelMediaOptions options = new ChannelMediaOptions();
        options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;
        options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        options.publishMicrophoneTrack = false;
        options.publishCameraTrack = false;
        options.publishCustomAudioTrack = true;
        options.publishCustomAudioTrackId = customTrack;
        options.autoSubscribeAudio = true; options.autoSubscribeVideo = false;
        require(engine.joinChannel(data.getString("token"), data.getString("channel"), data.getInt("uid"), options), "join channel");
    }
    private void poll() {
        if (closed.get() || sessionId == null) return;
        try {
            JSONObject status = backend.status(sessionId);
            String state = status.getString("state");
            if (state.equals("error") || state.equals("stopped")) { fail(status.optString("message", "Session ended")); return; }
            ProbeState.sourceText = status.optString("source_text");
            ProbeState.translatedText = status.optString("translated_text");
            ProbeState.phrases = status.optInt("phrases");
            ProbeState.message = status.optString("message", "Listening to English");
        } catch (Exception e) { fail("Connection to backend lost. Original audio will be restored."); }
    }
    void send(short[] samples, int count) {
        if (!joined || closed.get()) return;
        // AudioRecord reads 20 ms at a time; preserve little-endian PCM16.
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) { bytes[2*i] = (byte)samples[i]; bytes[2*i+1] = (byte)(samples[i] >> 8); }
        if (!upload.offer(bytes)) fail("Audio upload fell behind. Stop and restart.");
    }
    private void publish() {
        try {
            while (!closed.get()) {
                byte[] bytes = upload.poll(200, TimeUnit.MILLISECONDS);
                if (bytes == null) continue;
                RtcEngine rtc = engine;
                if (rtc != null && joined) require(rtc.pushExternalAudioFrame(bytes, SystemClock.elapsedRealtime(), 16000, 1,
                    Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE, customTrack), "send source audio");
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e) { if (!closed.get()) fail(e.getMessage()); }
    }
    private void render() {
        byte[] frame = new byte[960]; // 10 ms at 48 kHz, mono PCM16.
        try {
            while (!closed.get()) {
                RtcEngine rtc = engine;
                if (!joined || rtc == null) { Thread.sleep(10); continue; }
                int code = rtc.pullPlaybackAudioFrame(frame, frame.length);
                if (code == 0 && outputEnabled) {
                    int offset = 0;
                    while (offset < frame.length && !closed.get()) {
                        int written = output.write(frame, offset, frame.length - offset, AudioTrack.WRITE_BLOCKING);
                        if (written <= 0) throw new IllegalStateException("Translated audio playback stopped");
                        offset += written;
                    }
                } else Thread.sleep(10);
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e) { if (!closed.get()) fail("Translated audio playback failed"); }
    }
    private void fail(String message) { if (!closed.get()) listener.failed(message); }
    private static void require(int code, String action) { if (code != 0) throw new IllegalStateException("Could not " + action + " (" + code + ")"); }
    private void stopRemote() {
        String id = sessionId;
        if (id != null) { try { backend.stop(id); } catch (Exception ignored) { /* Backend lease expires if unreachable. */ } }
    }
    void close() {
        if (!closed.compareAndSet(false, true)) return;
        stopping = true;
        joined = false; outputEnabled = false; upload.clear();
        new Thread(() -> {
            try {
            synchronized (this) {
                if (publishing != null) publishing.interrupt();
                if (playback != null) playback.interrupt();
                if (output != null) { try { output.pause(); output.flush(); } catch (RuntimeException ignored) { } }
                join(publishing); join(playback);
                if (engine != null) {
                    engine.leaveChannel();
                    if (customTrack >= 0) engine.destroyCustomAudioTrack(customTrack);
                    RtcEngine.destroy(); engine = null;
                }
                if (output != null) { output.release(); output = null; }
            }
            stopRemote();
            network.shutdown();
            } finally { stopping = false; }
        }, "TranslationCleanup").start();
    }
    private static void join(Thread thread) {
        if (thread != null) { try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
}
