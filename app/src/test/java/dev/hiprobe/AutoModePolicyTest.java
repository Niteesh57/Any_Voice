package dev.hiprobe;

/** Standalone JVM regression tests for automatic source selection. */
public final class AutoModePolicyTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        AutoModePolicy p=new AutoModePolicy();
        for(long t=0;t<1000;t+=20)p.observe(-96,t);
        check(!p.wantsMedia(1000),"Quiet must select conversation");
        p.observe(-20,1000);check(!p.microphoneAllowed(1001),"App audio must not enter microphone questions");check(p.microphoneAllowed(1300),"Brief sounds must release microphone promptly");for(long t=1020;t<1600;t+=20)p.observe(-96,t);
        check(!p.wantsMedia(1600),"One transient must not switch to media");
        for(long t=1600;t<2200;t+=20)p.observe(-30,t);
        check(p.wantsMedia(2200),"Sustained app audio must take priority");
        for(long t=2200;t<6000;t+=20)p.observe(-96,t);
        check(p.wantsMedia(6000),"Sentence gaps must not switch to microphone");
        p.ask(6000);check(p.microphoneAllowed(6001),"Explicit question allows microphone over muted media");check(!p.wantsMedia(6001),"Explicit question must override media");
        p.automatic();check(p.wantsMedia(6002),"Automatic must restore media priority");
        for(long t=6000;t<9000;t+=20)p.observe(-96,t);
        check(!p.wantsMedia(9000),"Long quiet must release media priority");
        for(long t=9000;t<9700;t+=20)p.observe(-25,t);
        p.ask(9700);check(!p.wantsMedia(9701),"Manual override starts conversation");
        for(long t=9700;t<40000;t+=20)p.observe(-25,t);
        check(p.wantsMedia(40000),"Unused override must expire");
        AutoModePolicy clicks=new AutoModePolicy();
        for(long t=0;t<12000;t+=20)clicks.observe(t%1000==0?-10:-96,t);
        check(!clicks.wantsMedia(12000),"Separated clicks must not accumulate into speech");
        p.holdConversation(true);check(!p.wantsMedia(50000),"Pending clarification must hold conversation past override timeout");
        check(p.microphoneAllowed(50000),"Pending clarification must accept the user's answer");
        p.holdConversation(false);check(p.wantsMedia(50000),"Clearing clarification must restore media priority");
        System.out.println("Automatic mode timing: all scenarios passed");
    }
}
