package dev.hiprobe;

import android.app.KeyguardManager;
import android.app.SearchManager;
import android.content.*;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** Narrow native actions only: no screen reading, tapping or arbitrary intents. */
final class DeviceTools {
    private final Context context;
    DeviceTools(Context context){this.context=context.getApplicationContext();}
    JSONObject apps(){
        JSONObject apps=new JSONObject();
        Intent launcher=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> rows=context.getPackageManager().queryIntentActivities(launcher,0);
        rows.sort(Comparator.comparing(r->r.loadLabel(context.getPackageManager()).toString(),String.CASE_INSENSITIVE_ORDER));
        for(ResolveInfo row:rows){String name=row.activityInfo.packageName;if(name.equals(context.getPackageName()))continue;try{apps.put(name,row.loadLabel(context.getPackageManager()).toString());}catch(JSONException ignored){}if(apps.length()>=512)break;}
        return apps;
    }
    JSONObject run(JSONObject action){
        if(action.optString("name").equals("read_screen"))return ScreenTools.execute(context,action);
        if(action.optString("name").equals("scroll_screen"))return scrollOnce(action);
        String id=action.optString("id");if(!id.matches("[a-f0-9]{32}:[0-9:]+"))return result("failed","Invalid action ID");
        android.content.SharedPreferences ledger=context.getSharedPreferences("device_action_results",Context.MODE_PRIVATE);
        String saved=ledger.getString(id,null);
        if(saved!=null)try{return new JSONObject(saved);}catch(JSONException ignored){}
        JSONObject unknown=result("execution_unknown","Action may already have run; it will not be repeated automatically");
        if(!ledger.edit().putString(id,unknown.toString()).commit())return result("failed","Could not record action receipt");
        FutureTask<JSONObject> task=new FutureTask<>(()->launch(action));new Handler(Looper.getMainLooper()).post(task);
        JSONObject outcome;
        try{outcome=task.get(4,TimeUnit.SECONDS);}catch(Exception e){task.cancel(false);outcome=result("execution_unknown","Could not verify action handoff");}
        android.content.SharedPreferences.Editor edit=ledger.edit().putString(id,outcome.toString());
        if(ledger.getAll().size()>128){int count=0;for(String key:ledger.getAll().keySet())if(!key.equals(id)&&count++<32)edit.remove(key);}
        edit.commit();return outcome;
    }
    private JSONObject scrollOnce(JSONObject action){
        String id=action.optString("id");if(!id.matches("[a-f0-9]{32}:[0-9:]+"))return result("failed","Invalid action ID");
        if(!ScreenTools.authorized(context))return result("unavailable","Screen assist is off in Android or Any Voice");
        if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return result("needs_unlock","Unlock before scrolling");
        android.content.SharedPreferences receipts=context.getSharedPreferences("screen_action_receipts",Context.MODE_PRIVATE);
        if(receipts.contains(id)){JSONObject cached=ScreenTools.cached(id);return cached!=null?cached:result("execution_unknown","This scroll may already have run. Inspect the screen again; it will not be repeated");}
        if(!receipts.edit().putBoolean(id,true).commit())return result("failed","Could not record scroll receipt");
        JSONObject result=ScreenTools.execute(context,action);ScreenTools.cache(id,result);
        if(receipts.getAll().size()>128){android.content.SharedPreferences.Editor edit=receipts.edit();int removed=0;for(String key:receipts.getAll().keySet())if(!key.equals(id)&&removed++<32)edit.remove(key);edit.commit();}
        return result;
    }
    private JSONObject launch(JSONObject action){
        if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return result("needs_unlock","Unlock the phone and ask again");
        JSONObject args=action.optJSONObject("arguments");if(args==null)return result("failed","Missing arguments");
        try{
            if(action.optString("name").equals("open_app")){
                String app=args.optString("app");if(!apps().has(app))return result("unavailable","The app is not available in this phone's launcher list");
                Intent intent=context.getPackageManager().getLaunchIntentForPackage(app);if(intent==null)return result("unavailable","This app has no launch activity");
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return result("launch_requested","App launch requested; screen content was not inspected");
            }
            if(action.optString("name").equals("search_youtube")){
                String query=args.optString("query").trim();if(query.isEmpty()||query.length()>200)return result("failed","Invalid search query");
                Uri uri=new Uri.Builder().scheme("https").authority("www.youtube.com").appendPath("results").appendQueryParameter("search_query",query).build();
                Intent search=new Intent(Intent.ACTION_VIEW,uri).setPackage("com.google.android.youtube").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try{context.startActivity(search);return result("launch_requested","YouTube search requested; no video was selected or played");}
                catch(ActivityNotFoundException e){
                    context.startActivity(new Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return result("browser_requested","YouTube search URL handed to Android; no playback verified");
                }
            }
            return result("failed","Unsupported phone action");
        }catch(ActivityNotFoundException e){return result("unavailable","No app can handle this action");}
        catch(RuntimeException e){return result("failed","Android did not allow this action. Open Any Voice and try again");}
    }
    private static JSONObject result(String status,String detail){JSONObject value=new JSONObject();try{value.put("status",status).put("detail",detail);}catch(JSONException ignored){}return value;}
}
