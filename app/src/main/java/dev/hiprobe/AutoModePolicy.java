package dev.hiprobe;

/** Pure timing policy: sustained playback wins; short gaps do not change modes. */
final class AutoModePolicy {
    static final long ENTER_MS=400, QUIET_MS=6500, OVERRIDE_MS=30000;
    private long lastSignal=-1, voicedMs, lastSample=-1, forceUntil;
    private boolean media,hold;
    void holdConversation(boolean value){hold=value;}
    void observe(double db,long now){
        long step=lastSample<0?20:Math.max(0,Math.min(100,now-lastSample));lastSample=now;
        if(db>-55){lastSignal=now;voicedMs=Math.min(1000,voicedMs+step);if(voicedMs>=ENTER_MS)media=true;}
        else voicedMs=Math.max(0,voicedMs-step/2);
        if(lastSignal>=0&&now-lastSignal>=QUIET_MS){media=false;voicedMs=0;}
    }
    boolean microphoneAllowed(long now){return forced(now)||lastSignal<0||now-lastSignal>250;}
    boolean wantsMedia(long now){return !hold&&now>=forceUntil&&media;}
    boolean recentMedia(long now){return lastSignal>=0&&now-lastSignal<QUIET_MS;}
    void ask(long now){forceUntil=now+OVERRIDE_MS;}
    void automatic(){forceUntil=0;}
    boolean forced(long now){return hold||now<forceUntil;}
}
