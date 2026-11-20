package dev.hiprobe;

import android.app.KeyguardManager;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.util.Base64;
import org.json.*;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.*;

/** On-demand observations stay in memory; scrolling is the only screen mutation. */
final class ScreenTools {
    private static final java.util.concurrent.ConcurrentHashMap<String,Cached> observations=new java.util.concurrent.ConcurrentHashMap<>();
    private static final class Cached{final long at=SystemClock.elapsedRealtime();final JSONObject value;Cached(JSONObject value){this.value=value;}}
    static JSONObject cached(String id){Cached c=observations.get(id);return c!=null&&SystemClock.elapsedRealtime()-c.at<120000?c.value:null;}
    static void cache(String id,JSONObject value){if(observations.size()>=8)observations.clear();observations.put(id,new Cached(value));}
    static void clearResults(){observations.clear();}
    private static volatile String currentScope="",cancelledScope="";
    static boolean optedIn(Context c){return c.getSharedPreferences("screen_assist",Context.MODE_PRIVATE).getBoolean("enabled",false);}
    static boolean configured(Context c){
        String list=android.provider.Settings.Secure.getString(c.getContentResolver(),android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if(list==null)return false;android.content.ComponentName own=new android.content.ComponentName(c,ScreenAccessService.class);
        for(String item:list.split(":"))if(own.equals(android.content.ComponentName.unflattenFromString(item)))return true;
        return false;
    }
    static boolean authorized(Context c){return optedIn(c)&&configured(c);}
    static boolean enabled(Context c){return authorized(c)&&ScreenAccessService.connected!=null;}
    static String state(Context c){if(!optedIn(c))return "Screen assist off in Any Voice";if(!configured(c))return "Screen access off in Android \u00b7 open Screen assist settings";if(ScreenAccessService.connected==null)return "Screen assist enabled \u00b7 waiting for service connection";return "Screen assist ready \u00b7 reads only when you ask";}
    static void setEnabled(Context c,boolean enabled){if(!enabled)cancel();c.getSharedPreferences("screen_assist",Context.MODE_PRIVATE).edit().putBoolean("enabled",enabled).apply();}
    static void cancel(){cancelledScope=currentScope;clearResults();}
    private static <T>T onMain(Callable<T> call)throws Exception{FutureTask<T> task=new FutureTask<>(call);new Handler(Looper.getMainLooper()).post(task);try{return task.get(3,TimeUnit.SECONDS);}catch(Exception e){task.cancel(false);throw e;}}
    static JSONObject execute(Context context,JSONObject action){
        JSONObject args=action.optJSONObject("arguments");String scope=args==null?"":args.optString("_scope");currentScope=scope;
        try{
            if(scope.isEmpty()||scope.equals(cancelledScope))return status("cancelled","Screen research cancelled");
            if(!authorized(context))return status("unavailable","Enable Screen assist in Any Voice and Android accessibility settings");
            long reconnectDeadline=SystemClock.elapsedRealtime()+2200;
            while(ScreenAccessService.connected==null&&authorized(context)&&!scope.equals(cancelledScope)&&SystemClock.elapsedRealtime()<reconnectDeadline)Thread.sleep(100);
            if(ScreenAccessService.connected==null)return status("unavailable","Android screen permission is ON, but its service is reconnecting. Retry in a moment");
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return status("needs_unlock","Unlock before reading the screen");
            ScreenAccessService service=ScreenAccessService.connected;
            JSONObject before=onMain(service::snapshot);
            if(!before.optString("status").equals("observed"))return before;
            if(action.optString("name").equals("scroll_screen")){
                if(!before.optString("package_name").equals(args.optString("expected_package")))return status("screen_changed","The foreground app changed; inspect again before scrolling");
                boolean moved=onMain(()->service.scroll(!args.optString("direction","down").equals("up"),args.optString("expected_package")));
                if(!moved)return before.put("status","end_reached").put("content_hash",args.optString("previous_hash")).put("detail","No accessible scroll action was available");
                Thread.sleep(750);
            }
            if(scope.equals(cancelledScope))return status("cancelled","Screen research cancelled");
            onMain(()->{ListeningService.screenOverlay(false);return null;});Thread.sleep(180);
            JSONObject value=onMain(service::snapshot);
            if(!value.optString("status").equals("observed"))return value;
            CompletableFuture<Bitmap> future=new CompletableFuture<>();new Handler(Looper.getMainLooper()).post(()->service.screenshot(future));
            Bitmap bitmap=null;try{bitmap=future.get(2500,TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){future.thenAccept(late->{if(late!=null)late.recycle();});}
            if(bitmap==null&&Build.VERSION.SDK_INT>=30)return status("restricted","Android did not allow this screenshot; screen contents withheld. Try again on an accessible page");
            String encoded="";
            if(bitmap!=null){
                int side=Math.max(bitmap.getWidth(),bitmap.getHeight());float scale=Math.min(1,1280f/side);
                Bitmap small=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true);
                ByteArrayOutputStream output=new ByteArrayOutputStream();small.compress(Bitmap.CompressFormat.JPEG,60,output);
                if(output.size()<280000)encoded=Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP);
                if(small!=bitmap)small.recycle();bitmap.recycle();
            }
            if(scope.equals(cancelledScope))return status("cancelled","Screen research cancelled");
            if(!authorized(context))return status("unavailable","Screen assist was disabled");
            if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return status("needs_unlock","Screen locked during capture");
            JSONObject verified=onMain(service::snapshot);
            if(verified.optBoolean("sensitive"))return status("restricted","Sensitive field appeared during capture");
            if(!value.optString("package_name").equals(verified.optString("package_name")))return status("screen_changed","Foreground app changed during capture; inspect again");
            String evidence=value.optString("package_name")+"\n"+value.optString("text")+encoded;
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(evidence.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format(java.util.Locale.ROOT,"%02x",b));
            value.put("content_hash",hash.toString()).put("image_data",encoded).put("captured_at",System.currentTimeMillis());
            if(action.optString("name").equals("scroll_screen"))value.put("status",hash.toString().equals(args.optString("previous_hash"))?"unchanged":"scrolled");
            return value;
        }catch(Exception e){return status("failed","Screen observation unavailable. Try again with the page visible");}
        finally{new Handler(Looper.getMainLooper()).post(()->ListeningService.screenOverlay(true));}
    }
    private static JSONObject status(String state,String detail){JSONObject value=new JSONObject();try{value.put("status",state).put("detail",detail);}catch(JSONException ignored){}return value;}
}
