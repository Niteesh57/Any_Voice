package dev.hiprobe;

/** Only explicit user actions arm conversation capture; never persisted across lock/restart. */
final class ConversationState {
    static volatile boolean requested,active,stopping,listening;
    static volatile String message="Choose a partner language, then tap Start",source="",translated="",speaker="",id="";
    static boolean busy(){return requested||active||stopping;}
    static void clear(){requested=false;active=false;stopping=false;listening=false;}
}
