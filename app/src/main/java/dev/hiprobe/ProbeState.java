package dev.hiprobe;

/** In-memory diagnostics only: no audio or transcript is stored. */
final class ProbeState {
    static volatile boolean running;
    static volatile boolean replacing;
    static volatile double db = -96;
    static volatile long frames;
    static volatile long mutedSignalFrames;
    static volatile long audibleSignalFrames;
    static volatile String message = "Ready. Start a test with a video already playing.";
    static volatile String voice = "Voice engine not started";

    static String describe() {
        return (running ? (replacing ? "REPLACEMENT ON" : "ORIGINAL AUDIO / COMPARISON") : "STOPPED")
            + "\n" + message
            + "\nCaptured level: " + String.format(java.util.Locale.US, "%.0f dBFS", db)
            + "\nAudio buffers received: " + frames
            + "\nNon-silent buffers while muted: " + mutedSignalFrames
            + "\nNon-silent buffers with original sound: " + audibleSignalFrames
            + "\n" + voice;
    }
}
