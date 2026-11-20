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
    private final boolean unified;
    private final BackendClient backend;
    private final OutputVolume voiceVolume;
    private final Listener listener;
    private final ScheduledExecutorService network = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean closed = new AtomicBoolean();
    private static final class InputFrame {final byte[] bytes;final int track;InputFrame(byte[] bytes,int track){this.bytes=bytes;this.track=track;}}
    private final ArrayBlockingQueue<InputFrame> upload = new ArrayBlockingQueue<>(200);
    private volatile RtcEngineEx engine;
    private volatile boolean mediaJoined,micJoined,conversationJoined;
    private final ConcurrentHashMap<Integer,Integer> chunkEnds=new ConcurrentHashMap<>();
    private RtcConnection micConnection;
    private RtcConnection conversationConnection;
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
    private volatile long lastPacketAt;
    boolean outputIdle(){return downloadedSequence<=playedSequence&&prepared.isEmpty()&&SystemClock.elapsedRealtime()-lastPacketAt>2000;}
    String diagnosticSession(){return sessionId;}
    private volatile String terminalMessage;
    private volatile int terminalSequence;
    private int customTrack = -1;
    private int micTrack = -1;
    private int conversationTrack=-1;
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
        this(context,listener,bubble,false);
    }
    LiveTranslation(Context context,Listener listener,boolean bubble,boolean unified){
        this.context=context.getApplicationContext();this.listener=listener;this.bubble=bubble;this.unified=unified;
        backend=new BackendClient(context);voiceVolume=new OutputVolume(context);
    }
    void start() {
        network.execute(() -> {
            try {
                JSONObject data = unified?backend.startUnified(!bubble):bubble ? backend.startBubble() : backend.start();
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
                mediaJoined=true;readyIfJoined();
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
        engine = (RtcEngineEx)RtcEngine.create(config);
        require(engine.enableAudio(), "enable audio");
        require(engine.enableLocalAudio(false), "disable microphone capture");
        // RTC uploads captured playback only. Translated speech is played by our own ordered AudioTrack.
        AudioTrackConfig trackConfig = new AudioTrackConfig();
        trackConfig.enableLocalPlayback = false;
        customTrack = engine.createCustomAudioTrack(Constants.AudioTrackType.AUDIO_TRACK_MIXABLE, trackConfig);
        if (customTrack < 0) throw new IllegalStateException("Could not create source audio track");
        if(unified){micTrack=engine.createCustomAudioTrack(Constants.AudioTrackType.AUDIO_TRACK_MIXABLE,trackConfig);if(micTrack<0)throw new IllegalStateException("Could not create question audio track");}
        if(unified){conversationTrack=engine.createCustomAudioTrack(Constants.AudioTrackType.AUDIO_TRACK_MIXABLE,trackConfig);if(conversationTrack<0)throw new IllegalStateException("Could not create conversation audio track");}
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
        if(unified){
            micConnection=new RtcConnection(data.getString("channel"),data.getInt("microphone_uid"));
            ChannelMediaOptions micOptions=new ChannelMediaOptions();micOptions.channelProfile=Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;micOptions.clientRoleType=Constants.CLIENT_ROLE_BROADCASTER;
            micOptions.publishMicrophoneTrack=false;micOptions.publishCameraTrack=false;micOptions.publishCustomAudioTrack=true;micOptions.publishCustomAudioTrackId=micTrack;
            micOptions.autoSubscribeAudio=false;micOptions.autoSubscribeVideo=false;
            require(engine.joinChannelEx(data.getString("microphone_token"),micConnection,micOptions,new IRtcEngineEventHandler(){
                @Override public void onJoinChannelSuccess(String channel,int uid,int elapsed){if(!closed.get()){micJoined=true;readyIfJoined();}}
                @Override public void onError(int code){if(!closed.get())fail("Question audio connection error ("+code+")");}
            }),"join question input");
            conversationConnection=new RtcConnection(data.getString("channel"),data.getInt("conversation_uid"));
            ChannelMediaOptions conversationOptions=new ChannelMediaOptions();conversationOptions.channelProfile=Constants.CHANNEL_PROFILE_LIVE_BROADCASTING;conversationOptions.clientRoleType=Constants.CLIENT_ROLE_BROADCASTER;
            conversationOptions.publishMicrophoneTrack=false;conversationOptions.publishCameraTrack=false;conversationOptions.publishCustomAudioTrack=true;conversationOptions.publishCustomAudioTrackId=conversationTrack;conversationOptions.autoSubscribeAudio=false;conversationOptions.autoSubscribeVideo=false;
            require(engine.joinChannelEx(data.getString("conversation_token"),conversationConnection,conversationOptions,new IRtcEngineEventHandler(){
                @Override public void onJoinChannelSuccess(String channel,int uid,int elapsed){if(!closed.get()){conversationJoined=true;readyIfJoined();}}
                @Override public void onError(int code){if(!closed.get())fail("Conversation connection error ("+code+")");}
            }),"join conversation input");
        }
    }
    private synchronized void readyIfJoined(){if(!closed.get()&&!joined&&mediaJoined&&(!unified||micJoined&&conversationJoined)){joined=true;listener.ready();}}
    private void poll() {
        if (closed.get() || sessionId == null) return;
        try {
            JSONObject status = backend.status(sessionId);
            if(closed.get())return;
            String state = status.getString("state");
            JSONObject boundaries=status.optJSONObject("chunk_ends");if(boundaries!=null){java.util.Iterator<String> keys=boundaries.keys();while(keys.hasNext()){String key=keys.next();chunkEnds.put(Integer.parseInt(key),boundaries.getInt(key));}}
            int epoch = status.getInt("route_epoch");
            boolean translated = unified ? !"original".equals(status.getString("route_mode")) : "translated".equals(status.getString("route_mode"));
            if (epoch != policy.epoch || translated != policy.translated) policy = new Policy(epoch,translated);
            if (state.equals("error") || state.equals("stopped")) {
                terminalSequence = status.optInt("last_audio_sequence", downloadedSequence);
                terminalMessage = status.optString("message", "Session ended");
            }
            if(bubble||unified){
                JSONArray actions=status.optJSONArray("device_actions");
                if(actions!=null)for(int i=0;i<actions.length();i++){
                    if(closed.get())return;
                    JSONObject action=actions.getJSONObject(i);
                    JSONObject result=new DeviceTools(context).run(action);
                    try{backend.deviceResult(action.getString("id"),result);}catch(Exception ignored){} // Stored receipt is retried, never re-executed.
                }
                if(!status.optBoolean("screen_research"))ScreenTools.clearResults();
                listener.status(status);
            }
            if(!bubble||unified) {
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
        sendTrack(samples,count,customTrack);
    }
    void sendMicrophone(short[] samples,int count){sendTrack(samples,count,unified?micTrack:customTrack);}
    void sendConversation(short[] samples,int count){sendTrack(samples,count,conversationTrack);}
    boolean microphoneSafe(){return downloadedSequence<=playedSequence&&prepared.isEmpty()&&SystemClock.elapsedRealtime()-lastPacketAt>400;}
    private void sendTrack(short[] samples,int count,int track){
        if (!joined || closed.get() || terminalMessage != null) return;
        // AudioRecord reads 20 ms at a time; preserve little-endian PCM16.
        byte[] bytes = new byte[count * 2];
        for (int i = 0; i < count; i++) { bytes[2*i] = (byte)samples[i]; bytes[2*i+1] = (byte)(samples[i] >> 8); }
        if (!upload.offer(new InputFrame(bytes,track))) fail("Audio upload connection is congested.");
    }
    private void publish() {
        try {
            while (!closed.get()) {
                InputFrame frame = upload.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                RtcEngine rtc = engine;
                if (rtc != null && joined) require(rtc.pushExternalAudioFrame(frame.bytes, SystemClock.elapsedRealtime(), 16000, 1,
                    Constants.BytesPerSample.TWO_BYTES_PER_SAMPLE, frame.track), "send source audio");
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
                        after = sequence; downloadedSequence = sequence;lastPacketAt=SystemClock.elapsedRealtime();
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
        int activeChunk=0;
        boolean first = true;
        try {
            while (!closed.get()) {
                float gain = voiceVolume.gain();
                if (gain != appliedGain) { output.setVolume(gain); appliedGain = gain; }
                long consumed = Integer.toUnsignedLong(output.getPlaybackHeadPosition());
                while (!ends.isEmpty() && ends.peekFirst()[1] <= consumed) {
                    long[] done = ends.removeFirst();
                    playedSequence = (int)done[0]; if(!bubble||unified)ListeningState.playingChunk = (int)done[2];
                }
                Policy wanted = policy;
                int endSequence=activeChunk==0?0:chunkEnds.getOrDefault(activeChunk,-1);
                boolean atBoundary=activeChunk==0||endSequence>=0&&playedSequence>=endSequence;
                if (wanted != applied && atBoundary) {
                    android.util.Log.i("AnyVoicePlayback","handoff old_epoch="+applied.epoch+" new_epoch="+wanted.epoch+" completed_chunk="+activeChunk+" played="+playedSequence+" boundary="+endSequence);
                    // Playback ACK, not generation completion, defines the safe sentence handoff.
                    output.pause(); output.flush();
                    if (!ends.isEmpty()) playedSequence = (int)ends.peekLast()[0];
                    ends.clear(); framesWritten = 0; first = true; if(!bubble)ListeningState.playingChunk = 0;
                    listener.routing(wanted.translated); // Applies media volume on the service thread before playback.
                    if (closed.get()) return;
                    output.play(); applied = wanted;activeChunk=0;
                }
                if (clip == null) clip = prepared.poll(50, TimeUnit.MILLISECONDS);
                if (clip == null) {
                    if (terminalMessage != null && downloadedSequence >= terminalSequence && ends.isEmpty()) { fail(terminalMessage); return; }
                    continue;
                }
                if(policy!=applied&&activeChunk!=0&&clip.chunk!=activeChunk){Thread.sleep(10);continue;}
                if (clip.epoch < applied.epoch || (clip.epoch == applied.epoch && !applied.translated)) {
                    playedSequence = clip.sequence; clip = null; continue;
                }
                if (clip.epoch > applied.epoch) { Thread.sleep(10); continue; }
                if (first) {
                    long deadline = SystemClock.elapsedRealtime() + 100;
                    while (prepared.isEmpty() && SystemClock.elapsedRealtime() < deadline && !closed.get()) Thread.sleep(5);
                    first = false;
                }
                activeChunk=clip.chunk;
                int offset = 0;
                while (offset < clip.pcm.length && !closed.get()) {
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
    void cancelResearch(){String id=sessionId;if(id!=null)new Thread(()->{try{backend.cancelResearch(id);}catch(Exception ignored){}},"CancelResearch").start();}
    void input(boolean media){network.execute(()->{try{if(!closed.get()&&sessionId!=null)backend.input(sessionId,media);}catch(Exception e){if(!closed.get())fail("Could not switch audio input");}});}
    void startConversation(JSONObject config){network.execute(()->{try{if(!closed.get()&&sessionId!=null)backend.startConversation(sessionId,config);}catch(Exception e){ConversationState.clear();ConversationState.message="Could not start translator: "+e.getMessage();}});}
    void conversationTurn(String side){ConversationState.turnPending=true;ConversationState.readyToListen=false;network.execute(()->{try{if(!closed.get()&&sessionId!=null)backend.conversationTurn(sessionId,side);}catch(Exception e){ConversationState.turnError="Could not change turn. Try again.";}finally{ConversationState.turnPending=false;}});}
    void stopConversation(){network.execute(()->{try{if(!closed.get()&&sessionId!=null)backend.stopConversation(sessionId);}catch(Exception e){if(!closed.get())fail("Could not stop conversation translator");}});}
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
                    if(micConnection!=null)engine.leaveChannelEx(micConnection);
                    if(conversationConnection!=null)engine.leaveChannelEx(conversationConnection);
                    engine.leaveChannel();
                    if (customTrack >= 0) engine.destroyCustomAudioTrack(customTrack);
                    if(micTrack>=0)engine.destroyCustomAudioTrack(micTrack);
                    if(conversationTrack>=0)engine.destroyCustomAudioTrack(conversationTrack);
                    RtcEngine.destroy(); engine = null;
                }
                if (output != null) { output.release(); output = null; }
            }
            stopRemote();
            network.shutdown();
            try{network.awaitTermination(90,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            } finally { stopping = false; }
        }, "TranslationCleanup").start();
    }
    private static void join(Thread thread) {
        if (thread != null) { try { thread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
    }
}
