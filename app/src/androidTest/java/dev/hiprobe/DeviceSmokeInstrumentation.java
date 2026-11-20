package dev.hiprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;

/** Opt-in on-device checks. Uses a separate test identity, never the user's memory. */
public final class DeviceSmokeInstrumentation extends Instrumentation {
    private String requestedApp,requestedMode;
    @Override public void onCreate(Bundle args){super.onCreate(args);requestedApp=args==null?null:args.getString("app");requestedMode=args==null?null:args.getString("mode");start();}
    @Override public void onStart(){
        Bundle results=new Bundle();
        try{
            Context real=getTargetContext();
            if("apps".equals(requestedMode)){
                DeviceTools tools=new DeviceTools(real);JSONObject apps=tools.apps();
                if(!apps.has("com.google.android.youtube"))throw new AssertionError("YouTube missing from native capabilities");
                results.putInt("app_count",apps.length());results.putString("youtube_label",apps.getString("com.google.android.youtube"));
                JSONObject search=tools.run(new JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-","")+":0").put("name","search_youtube").put("arguments",new JSONObject().put("query","C++ tutorial for beginners")));
                if(!search.optString("status").equals("launch_requested"))throw new AssertionError(search.toString());
                results.putString("youtube_search",search.toString());finish(-1,results);return;
            }
            if("screen".equals(requestedMode)){
                long deadline=android.os.SystemClock.elapsedRealtime()+120000;
                while(ScreenAccessService.connected==null&&android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(200);
                if(!ScreenTools.enabled(real))throw new AssertionError("User must enable Screen assist first");
                real.startActivity(new android.content.Intent().setComponent(new android.content.ComponentName("dev.hiprobe.testsource","dev.hiprobe.testsource.ResearchFixtureActivity")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK|android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK));
                Thread.sleep(2000);
                String scope=java.util.UUID.randomUUID().toString().replace("-","");org.json.JSONArray pages=new org.json.JSONArray();String hash="";
                for(int i=0;i<3;i++){
                    JSONObject args=new JSONObject().put("_scope",scope).put("expected_package","dev.hiprobe.testsource").put("previous_hash",hash).put("direction","down");
                    JSONObject page=new DeviceTools(real).run(new JSONObject().put("id",scope+":"+i).put("name",i==0?"read_screen":"scroll_screen").put("arguments",args));
                    if(i>0&&page.optString("status").equals("end_reached"))break;
                    pages.put(page);
                    try(java.io.FileOutputStream out=real.openFileOutput("screen-fixture-observations.json",Context.MODE_PRIVATE)){out.write(pages.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                    if(!page.optString("status").equals(i==0?"observed":"scrolled"))throw new AssertionError("Screen operation at page "+i+": "+page.optString("status")+" "+page.optString("detail"));
                    if(page.optString("image_data").isEmpty())throw new AssertionError("No screenshot available");
                    if(!page.optString("package_name").equals("dev.hiprobe.testsource"))throw new AssertionError("Wrong foreground app");
                    hash=page.optString("content_hash");
                }
                try(java.io.FileOutputStream out=real.openFileOutput("screen-fixture-observations.json",Context.MODE_PRIVATE)){out.write(pages.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
                String collected=pages.toString();if(!collected.contains("Course A")||!collected.contains("Course B")||!collected.contains("Course C"))throw new AssertionError("Fixture coverage incomplete");
                ScreenTools.cancel();JSONObject cancelled=ScreenTools.execute(real,new JSONObject().put("name","read_screen").put("arguments",new JSONObject().put("_scope",scope)));
                if(!cancelled.optString("status").equals("cancelled"))throw new AssertionError("Screen cancel failed");
                results.putString("screen_read_scroll_screenshot_cancel","PASS: "+pages.length()+" fixture viewports");finish(-1,results);return;
            }
            if(requestedApp!=null){
                JSONObject launch=new DeviceTools(real).run(new JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-","")+":0").put("name","open_app").put("arguments",new JSONObject().put("app",requestedApp)));
                results.putString("open_app",launch.toString());finish(-1,results);return;
            }
            String suffix=java.util.UUID.randomUUID().toString();
            Context isolated=new ContextWrapper(real){@Override public SharedPreferences getSharedPreferences(String name,int mode){return super.getSharedPreferences(name.equals("bubble_identity")?"smoke_identity_"+suffix:name,mode);}};
            BackendClient client=new BackendClient(isolated);
            String check="Phone verification "+suffix;
            JSONObject remembered=client.tool("memory_save",new JSONObject().put("key","verification").put("text",check));
            if(!client.overview().getJSONArray("memories").getJSONObject(0).getString("value").equals(check))throw new AssertionError("Memory read failed");
            if(new BackendClient(real).overview().toString().contains(check))throw new AssertionError("Device identities overlapped");
            results.putString("memory_and_isolation","PASS");
            JSONObject task=client.tool("task_create",new JSONObject().put("title",check));String id=task.getJSONObject("task").getString("id");
            client.tool("task_complete",new JSONObject().put("task_id",id));
            if(!client.overview().getJSONArray("tasks").getJSONObject(0).getString("status").equals("done"))throw new AssertionError("Task completion failed");
            client.tool("task_reopen",new JSONObject().put("task_id",id));
            if(!new BackendClient(isolated).overview().getJSONArray("tasks").getJSONObject(0).getString("status").equals("open"))throw new AssertionError("Task reopen failed");
            client.tool("task_complete",new JSONObject().put("task_id",id));
            client.tool("memory_forget",new JSONObject().put("memory_id",remembered.getJSONObject("memory").getString("id")));
            if(client.overview().getJSONArray("memories").length()!=0)throw new AssertionError("Forget failed");
            results.putString("task_tools_and_forget","PASS");
            DeviceTools tools=new DeviceTools(real);if(!tools.apps().has("com.google.android.youtube"))throw new AssertionError("YouTube not in native app capabilities");
            String app=tools.apps().has("com.whatsapp")?"com.whatsapp":"com.google.android.youtube";
            JSONObject launch=tools.run(new JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-","")+":0").put("name","open_app").put("arguments",new JSONObject().put("app",app)));
            results.putString("open_app",launch.toString());sendStatus(1,results);Thread.sleep(3500);
            JSONObject search=tools.run(new JSONObject().put("id",java.util.UUID.randomUUID().toString().replace("-","")+":1").put("name","search_youtube").put("arguments",new JSONObject().put("query","relaxing Telugu music")));
            results.putString("youtube_search",search.toString());Thread.sleep(3000);
            finish(-1,results);
        }catch(Throwable e){results.putString("failure",e.getClass().getSimpleName()+": "+e.getMessage());finish(0,results);}
    }
}
