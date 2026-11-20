package dev.hiprobe;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Bundle;
import org.json.JSONObject;

/** Opt-in on-device checks. Uses a separate test identity, never the user's memory. */
public final class DeviceSmokeInstrumentation extends Instrumentation {
    private String requestedApp;
    @Override public void onCreate(Bundle args){super.onCreate(args);requestedApp=args==null?null:args.getString("app");start();}
    @Override public void onStart(){
        Bundle results=new Bundle();
        try{
            Context real=getTargetContext();
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
