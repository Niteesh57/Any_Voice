package dev.hiprobe;

/** Only explicit user actions arm conversation capture; never persisted across lock/restart. */
final class ConversationState {
    static volatile boolean requested,active,stopping,listening,readyToListen;
    static volatile String message="Choose a partner language, then tap Start",source="",translated="",speaker="",id="";
    static boolean busy(){return requested||active||stopping;}
    static void clear(){requested=false;active=false;stopping=false;listening=false;readyToListen=false;}
    static String status(boolean preparing){
        if(stopping)return "Finishing…";
        if(active)return readyToListen?"Listening · speak now":listening?"Connecting microphone…":"Translating…";
        if(preparing||requested)return "Connecting translator…";
        return message.startsWith("Choose")||message.startsWith("Stopped")?"Ready":message;
    }
}
