package dev.hiprobe;

/** In-memory diagnostics only: no audio or transcript is stored. */
final class ListeningState {
    static volatile boolean running;
    static volatile boolean manualConversation;
    static volatile boolean replacing;
    static volatile String detectedLanguage = "";
    static volatile String sourceText = "";
    static volatile String translatedText = "";
    static volatile int phrases;
    static volatile int playingChunk;
    static volatile double db = -96;
    static volatile long frames;
    static volatile long mutedSignalFrames;
    static volatile long audibleSignalFrames;
    static volatile String message = "Ready when you are";

    static String describe() {
        return (running ? (replacing ? "REPLACEMENT ON" : "ORIGINAL AUDIO") : "STOPPED")
            + "\n" + message
            + "\nCaptured level: " + String.format(java.util.Locale.US, "%.0f dBFS", db)
            + "\nAudio buffers received: " + frames
            + "\nNon-silent buffers while muted: " + mutedSignalFrames
            + "\nNon-silent buffers with original sound: " + audibleSignalFrames;
    }
}
