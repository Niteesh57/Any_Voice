package dev.hiprobe;

/** Microphone assistant state is separate from media translation state. */
final class BubbleState {
    static volatile boolean enabled,active,inputAllowed,translationPending;
    static volatile String phase="disabled",message="Enable Any Voice for automatic media and conversation",heard="",reply="";
    static volatile long wakeCount;
}

