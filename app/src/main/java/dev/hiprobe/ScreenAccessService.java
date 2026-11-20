package dev.hiprobe;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.*;
import android.os.Build;
import android.view.Display;
import android.view.accessibility.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.json.*;

/** Explicit screen research only. No click, text entry or gesture-dispatch capability. */
public final class ScreenAccessService extends AccessibilityService {
    static volatile ScreenAccessService connected;
    private volatile JSONObject probeResult;
    private JSONObject lastProbeAction;
    private volatile boolean probeRunning;
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args){
        out.println("Connected: "+(connected==this)+" In-app opt-in: "+ScreenTools.optedIn(this)+" Android permission: "+ScreenTools.configured(this)+" Ready: "+ScreenTools.enabled(this));
        if(BuildConfig.DEBUG&&args!=null&&(java.util.Arrays.asList(args).contains("--probe")||java.util.Arrays.asList(args).contains("--probe-scroll"))&&!probeRunning){
            boolean scrollProbe=java.util.Arrays.asList(args).contains("--probe-scroll");
            probeRunning=true;new Thread(()->{
                try{
                    String scope=java.util.UUID.randomUUID().toString().replace("-","");
                    JSONObject parameters=new JSONObject().put("_scope",scope);
                    if(scrollProbe)parameters.put("expected_package","dev.hiprobe.testsource").put("direction","down").put("previous_hash",probeResult==null?"":probeResult.optString("hash"));
                    JSONObject action=new JSONObject().put("id",scope+":0").put("name",scrollProbe?"scroll_screen":"read_screen").put("arguments",parameters);
                    if(java.util.Arrays.asList(args).contains("--probe-retry")&&lastProbeAction!=null)action=lastProbeAction;
                    lastProbeAction=action;JSONObject result=new DeviceTools(this).run(action);
                    probeResult=new JSONObject().put("status",result.optString("status")).put("package",result.optString("package_name")).put("text_chars",result.optString("text").length()).put("image_chars",result.optString("image_data").length()).put("detail",result.optString("detail")).put("hash",result.optString("content_hash"));
                    if(result.optString("package_name").equals("dev.hiprobe.testsource")){String t=result.optString("text");probeResult.put("fixture_A",t.contains("Course A")).put("fixture_B",t.contains("Course B")).put("fixture_C",t.contains("Course C"));}
                }catch(Exception e){probeResult=new JSONObject();}finally{probeRunning=false;}
            },"ScreenAccessProbe").start();
        }
        out.println("Probe running: "+probeRunning);out.println("Last probe: "+(probeResult==null?"none":probeResult));
    }

    @Override protected void onServiceConnected(){connected=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent event){} // No passive collection.
    @Override public void onInterrupt(){}
    @Override public boolean onUnbind(Intent intent){connected=null;return super.onUnbind(intent);}
    @Override public void onDestroy(){if(connected==this)connected=null;super.onDestroy();}
    private AccessibilityNodeInfo root(){
        for(AccessibilityWindowInfo window:getWindows()){
            if(window.getType()!=AccessibilityWindowInfo.TYPE_APPLICATION||!window.isActive()&&!window.isFocused())continue;
            AccessibilityNodeInfo node=window.getRoot();
            if(node!=null){node.refresh();return node;}
        }
        AccessibilityNodeInfo active=getRootInActiveWindow();if(active!=null)active.refresh();return active;
    }
    JSONObject snapshot() throws JSONException {
        JSONObject result=new JSONObject();AccessibilityNodeInfo root=root();
        if(root==null)return result.put("status","unavailable").put("detail","No accessible application window");
        try{
            String pkg=String.valueOf(root.getPackageName());StringBuilder text=new StringBuilder();boolean sensitive=false,scrollable=false;
            ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();nodes.add(AccessibilityNodeInfo.obtain(root));int visited=0;
            while(!nodes.isEmpty()&&visited++<1200&&text.length()<16000){AccessibilityNodeInfo n=nodes.removeFirst();
                if(n.isVisibleToUser()){
                    if(n.isPassword())sensitive=true;
                    else{String label=n.getText()!=null?n.getText().toString():n.getContentDescription()!=null?n.getContentDescription().toString():"";
                        if(!label.trim().isEmpty()){Rect bounds=new Rect();n.getBoundsInScreen(bounds);text.append(label.trim()).append(" [").append(bounds.left).append(',').append(bounds.top).append(',').append(bounds.right).append(',').append(bounds.bottom).append("]\n");}}
                    scrollable|=n.isScrollable();
                    for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo child=n.getChild(i);if(child!=null)nodes.add(child);}
                }
                n.recycle();
            }
            while(!nodes.isEmpty())nodes.removeFirst().recycle();
            return result.put("status",sensitive?"restricted":"observed").put("package_name",pkg).put("text",sensitive?"Password field visible; screen observation withheld":text.substring(0,Math.min(text.length(),16000)))
                .put("can_scroll",scrollable&&!sensitive).put("sensitive",sensitive).put("truncated",visited>=1200||text.length()>=16000);
        }finally{root.recycle();}
    }
    boolean scroll(boolean down,String expectedPackage){
        AccessibilityNodeInfo root=root();if(root==null)return false;
        try{
            if(!String.valueOf(root.getPackageName()).equals(expectedPackage))return false;
            ArrayDeque<AccessibilityNodeInfo> nodes=new ArrayDeque<>();ArrayList<AccessibilityNodeInfo> choices=new ArrayList<>();nodes.add(AccessibilityNodeInfo.obtain(root));int visited=0;
            while(!nodes.isEmpty()&&visited++<1200){AccessibilityNodeInfo n=nodes.removeFirst();
                if(n.isVisibleToUser()&&n.isScrollable())choices.add(AccessibilityNodeInfo.obtain(n));
                for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null)nodes.add(c);}n.recycle();
            }
            while(!nodes.isEmpty())nodes.removeFirst().recycle();
            choices.sort((a,b)->Long.compare(area(b),area(a)));boolean done=false;
            for(AccessibilityNodeInfo n:choices){if(!done)done=n.performAction(down?AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);n.recycle();}
            return done;
        }finally{root.recycle();}
    }
    private static long area(AccessibilityNodeInfo n){Rect r=new Rect();n.getBoundsInScreen(r);return (long)r.width()*r.height();}
    void screenshot(CompletableFuture<Bitmap> future){
        if(getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()){future.complete(null);return;}
        if(Build.VERSION.SDK_INT<30){future.complete(null);return;}
        takeScreenshot(Display.DEFAULT_DISPLAY,getMainExecutor(),new TakeScreenshotCallback(){
            public void onSuccess(ScreenshotResult result){
                android.hardware.HardwareBuffer buffer=result.getHardwareBuffer();Bitmap hardware=null;
                try{hardware=Bitmap.wrapHardwareBuffer(buffer,result.getColorSpace());future.complete(hardware==null?null:hardware.copy(Bitmap.Config.ARGB_8888,false));}
                catch(RuntimeException e){future.complete(null);}finally{if(hardware!=null)hardware.recycle();buffer.close();}
            }
            public void onFailure(int error){future.complete(null);}
        });
    }
}
