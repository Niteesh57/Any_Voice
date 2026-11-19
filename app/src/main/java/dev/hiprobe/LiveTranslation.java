package dev.hiprobe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.SystemClock;
import org.json.JSONObject;
import org.json.JSONArray;
import io.agora.rtc2.*;
import io.agora.rtc2.audio.AudioTrackConfig;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Agora custom input and external rendering. No microphone track or local source playback. */
final class LiveTranslation {
    private static volatile boolean stopping;
    static boolean isStopping() { return stopping; }
    interface Listener { void ready(); void routing(boolean translated); void failed(String message); default void status(JSONObject data) {} }
    private final Context context;
    private final boolean bubble;
    private final BackendClient backend;
    private final OutputVolume voiceVolume;
    private final Listener listener;
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayBlockingQueue<byte[]> upload = new ArrayBlockingQueue<>(100);
    private volatile RtcEngine engine;
    private volatile boolean joined;
    private volatile String sessionId;
    private volatile boolean outputEnabled = true;
    private volatile int playedSequence;
    private static final class Policy {
        final int epoch; final boolean translated;
        Policy(int epoch, boolean translated) { this.epoch = epoch; this.translated = translated; }
    }
    private volatile Policy policy = new Policy(0, false);
    private int acknowledgedSequence;
    private volatile int downloadedSequence;
    private volatile String terminalMessage;
    private volatile int terminalSequence;
    private int customTrack = -1;
    private int workerUid;
    private AudioTrack output;
    private Thread playback, publishing, downloading;
    private volatile java.net.HttpURLConnection audioConnection;
    private final ArrayBlockingQueue<PreparedAudio> prepared = new ArrayBlockingQueue<>(100);
    private static final class PreparedAudio {
        final int sequence, chunk, part, epoch; final byte[] pcm;
        PreparedAudio(int sequence, int chunk, int part, int epoch, byte[] pcm) {
            this.sequence = sequence; this.chunk = chunk; this.part = part; this.epoch = epoch; this.pcm = pcm;
        }
    }


    LiveTranslation(Context context, Listener listener) { this(context,listener,false); }
    LiveTranslation(Context context, Listener listener, boolean bubble) {
        this.context=context.getApplicationContext();this.listener=listener;this.bubble=bubble;
        backend=new BackendClient(context);voiceVolume=new OutputVolume(context);
    }
    void start() {
        network.execute(() -> {
            try {
                JSONObject data = bubble ? backend.startBubble() : backend.start();
                sessionId = data.getString("id");
                if (!"queued-pcm".equals(data.optString("playback_mode")))
                    throw new IllegalStateException("Backend needs the queued-audio update");
                if (data.optInt("audio_protocol") != 3)
                    throw new IllegalStateException("Backend needs the automatic-language update");
                if (closed.get()) { stopRemote(); return; }
                if(bubble)policy=new Policy(data.getInt("route_epoch"),true);
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
        // RTC uploads captured playback only. Translated speech is played by our own ordered AudioTrack.
        AudioTrackConfig trackConfig = new AudioTrackConfig();
        trackConfig.enableLocalPlayback = false;
        customTrack = engine.createCustomAudioTrack(Constants.AudioTrackType.AUDIO_TRACK_MIXABLE, trackConfig);
        if (customTrack < 0) throw new IllegalStateException("Could not create source audio track");
        int minimum = AudioTrack.getMinBufferSize(16000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) throw new IllegalStateException("Audio output format unavailable");
        output = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(Math.max(minimum, 3200)).setTransferMode(AudioTrack.MODE_STREAM).build();
        output.setVolume(voiceVolume.gain()); output.play();
        playback = new Thread(this::render, "VoicePlayback"); playback.start();
        downloading = new Thread(this::downloadAudio, "VoicePrefetch"); downloading.start();
        publishing = new Thread(this::publish, "SourceUpload"); publishing.start();
        ChannelMediaOptions options = new ChannelMediaOptions();
        options.channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;
        options.clientRoleType = Constants.CLIENT_ROLE_BROADCASTER;
        options.publishMicrophoneTrack = false;
        options.publishCameraTrack = false;
        options.publishCustomAudioTrack = true;
        options.publishCustomAudioTrackId = customTrack;
        options.autoSubscribeAudio = false; options.autoSubscribeVideo = false;
        require(engine.joinChannel(data.getString("token"), data.getString("channel"), data.getInt("uid"), options), "join channel");
    }
    private void poll() {
        if (closed.get() || sessionId == null) return;
        try {
            JSONObject status = backend.status(sessionId);
            String state = status.getString("state");
            int epoch = status.getInt("route_epoch");
            boolean translated = "translated".equals(status.getString("route_mode"));
            if (epoch != policy.epoch || translated != policy.translated) policy = new Policy(epoch,translated);
            if (state.equals("error") || state.equals("stopped")) {
                terminalSequence = status.optInt("last_audio_sequence", downloadedSequence);
                terminalMessage = status.optString("message", "Session ended");
            }
            if(bubble)listener.status(status);
            else {
                ListeningState.sourceText=status.optString("source_text");
                ListeningState.translatedText=status.optString("translated_text");
                ListeningState.phrases=status.optInt("phrases");
                ListeningState.detectedLanguage=status.optString("detected_language");
                ListeningState.message=status.optString("message","Detecting language");
            }
            if (playedSequence > acknowledgedSequence) {
                int completed = playedSequence;
                try { backend.audioPlayed(sessionId, completed); acknowledgedSequence = completed; }
                catch (Exception ignored) { /* Retry on the next heartbeat; playback continues. */ }
            }
        } catch (Exception e) { fail("Connection to backend lost. Original audio will be restored."); }
    }
    void send(short[] samples, int count) {
        if (!joined || closed.get() || terminalMessage != null) return;
        // AudioRecord reads 20 ms at a time; preserve little-endian PCM16.
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) { bytes[2*i] = (byte)samples[i]; bytes[2*i+1] = (byte)(samples[i] >> 8); }
        if (!upload.offer(bytes)) fail("Audio upload connection is congested.");
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
    private void downloadAudio() {
        int after = 0;
        int lastChunk = 0, lastPart = 0, lastEpoch = -1;
        while (!closed.get()) {
            java.net.HttpURLConnection connection = null;
            try {
                if (!joined || sessionId == null) { Thread.sleep(50); continue; }
                connection = backend.audioStream(sessionId, after); audioConnection = connection;
                try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.BufferedInputStream(connection.getInputStream()))) {
                    while (!closed.get()) {
                        int sequence = in.readInt(), chunk = in.readInt(), part = in.readInt(), epoch = in.readInt(), size = in.readInt();
                        if (sequence == 0 && chunk == 0 && part == 0 && epoch == 0 && size == 0) continue;
                        if (sequence != after + 1 || size < 2 || size > 3200 || size % 2 != 0)
                            throw new IllegalStateException("Invalid numbered audio packet");
                        boolean sameChunk = chunk == lastChunk && part == lastPart + 1;
                        boolean nextChunk = chunk == lastChunk + 1 && part == 1;
                        if (epoch > lastEpoch) { sameChunk = false; nextChunk = part == 1; }
                        if (epoch < lastEpoch) throw new IllegalStateException("Audio language routing is out of order");
                        if ((!sameChunk && !nextChunk) || chunk < 1 || part < 1)
                            throw new IllegalStateException("Voice chunk numbering is out of order");
                        byte[] pcm = new byte[size]; in.readFully(pcm);
                        prepared.put(new PreparedAudio(sequence, chunk, part, epoch, pcm));
                        after = sequence; downloadedSequence = sequence;
                        lastChunk = chunk; lastPart = part; lastEpoch = epoch;
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (IllegalStateException e) { if (!closed.get()) fail(e.getMessage()); return; }
            catch (Exception e) {
                if (closed.get()) return;
                try { Thread.sleep(100); } catch (InterruptedException interrupted) { return; }
            } finally {
                if (connection != null) connection.disconnect();
                audioConnection = null;
            }
        }
    }
    private void render() {
        long framesWritten = 0;
        float appliedGain = -1;
        java.util.ArrayDeque<long[]> ends = new java.util.ArrayDeque<>();
        Policy applied = policy;
        PreparedAudio clip = null;
        boolean first = true;
        try {
            while (!closed.get()) {
                float gain = voiceVolume.gain();
                if (gain != appliedGain) { output.setVolume(gain); appliedGain = gain; }
                Policy wanted = policy;
                if (wanted != applied) {
                    // Only a confirmed language change cancels obsolete translated audio.
                    output.pause(); output.flush();
                    if (!ends.isEmpty()) playedSequence = (int)ends.peekLast()[0];
                    ends.clear(); framesWritten = 0; first = true; if(!bubble)ListeningState.playingChunk = 0;
                    listener.routing(wanted.translated); // Applies media volume on the service thread before playback.
                    if (closed.get()) return;
                    output.play(); applied = wanted;
                }
                long consumed = Integer.toUnsignedLong(output.getPlaybackHeadPosition());
                while (!ends.isEmpty() && ends.peekFirst()[1] <= consumed) {
                    long[] done = ends.removeFirst();
                    playedSequence = (int)done[0]; if(!bubble)ListeningState.playingChunk = (int)done[2];
                }
                if (clip == null) clip = prepared.poll(50, TimeUnit.MILLISECONDS);
                if (clip == null) {
                    if (terminalMessage != null && downloadedSequence >= terminalSequence && ends.isEmpty()) { fail(terminalMessage); return; }
                    continue;
                }
                if (clip.epoch < applied.epoch || (clip.epoch == applied.epoch && !applied.translated)) {
                    playedSequence = clip.sequence; clip = null; continue;
                }
                if (clip.epoch > applied.epoch) { Thread.sleep(10); continue; }
                if (first) {
                    long deadline = SystemClock.elapsedRealtime() + 100;
                    while (prepared.isEmpty() && SystemClock.elapsedRealtime() < deadline && !closed.get()) Thread.sleep(5);
                    first = false;
                }
                if (policy != applied) continue;
                int offset = 0;
                while (offset < clip.pcm.length && !closed.get() && policy == applied) {
                    int written = output.write(clip.pcm, offset, Math.min(3200, clip.pcm.length-offset), AudioTrack.WRITE_BLOCKING);
                    if (written <= 0) throw new IllegalStateException("Translated audio playback stopped");
                    offset += written; framesWritten += written / 2;
                }
                ends.addLast(new long[]{clip.sequence, framesWritten, clip.chunk});
                clip = null;
                // Ordinary chunks finish in order. No flush or interruption at chunk boundaries.
            }
        } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        catch (RuntimeException e) { if (!closed.get()) fail("Audio routing failed: " + e.getMessage()); }
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
        if (audioConnection != null) audioConnection.disconnect();
        joined = false; outputEnabled = false; upload.clear(); prepared.clear();
        new Thread(() -> {
            try {
            synchronized (this) {
                if (publishing != null) publishing.interrupt();
                if (downloading != null) downloading.interrupt();
                if (playback != null) playback.interrupt();
                if (output != null) { try { output.pause(); output.flush(); } catch (RuntimeException ignored) { } }
                join(publishing); join(playback); join(downloading);
                if (engine != null) {
                    engine.leaveChannel();
                    if (customTrack >= 0) engine.destroyCustomAudioTrack(customTrack);
                    RtcEngine.destroy(); engine = null;
                }
                if (output != null) { output.release(); output = null; }
            }
            stopRemote();
            network.shutdown();
            if(bubble)try{network.awaitTermination(90,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } finally { stopping = false; }
        }, "TranslationCleanup").start();
    }
    private static void join(Thread thread) {
        if (thread != null) { try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
}
