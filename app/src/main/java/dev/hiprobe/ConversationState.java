package dev.hiprobe;

/** Only explicit user actions arm conversation capture; never persisted across lock/restart. */
final class ConversationState {
    static volatile boolean requested,active,stopping,listening,readyToListen,turnPending;
    static volatile String message="Choose a partner language, then tap Start",source="",translated="",speaker="",id="";
    static volatile String turn="auto",turnError="";
    static boolean busy(){return requested||active||stopping;}
    static void clear(){requested=false;active=false;stopping=false;listening=false;readyToListen=false;turnPending=false;turn="auto";turnError="";}
    static String status(boolean preparing){
        if(stopping)return "Finishing…";
        if(turnPending)return "Switching turn…";
        if(active)return readyToListen?"Listening · speak now":listening?"Connecting microphone…":"Translating…";
        if(preparing||requested)return "Connecting translator…";
        return message.startsWith("Choose")||message.startsWith("Stopped")?"Ready":message;
    }
}
