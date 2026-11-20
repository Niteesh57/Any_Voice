package dev.hiprobe;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Only the backend URL and app access token live on the phone, never provider keys. */
final class BackendClient {
    private final String deviceId,deviceKey;
    private final String base;
    private final String access;
    private final String voiceProvider;
    private final UserProfile profile;
    private final Context context;

    BackendClient(Context context) {
        this.context=context.getApplicationContext();
        profile = new UserProfile(context);
        String[] identity=identity(context);deviceId=identity[0];deviceKey=identity[1];
        SharedPreferences prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        base = prefs.getString("url", BuildConfig.DEFAULT_BACKEND_URL).trim().replaceAll("/+$", "");
        access = prefs.getString("access", "").trim();
        voiceProvider = prefs.getString("voice_provider", "murf-direct");
    }
    private static synchronized String[] identity(Context context){
        SharedPreferences prefs=context.getSharedPreferences("bubble_identity",Context.MODE_PRIVATE);
        String id=prefs.getString("id",""),key=prefs.getString("key","");
        if(id.isEmpty()||key.isEmpty()){
            id=java.util.UUID.randomUUID().toString().replace("-","");key=java.util.UUID.randomUUID().toString().replace("-","")+java.util.UUID.randomUUID().toString().replace("-","");
            if(!prefs.edit().putString("id",id).putString("key",key).commit())throw new IllegalStateException("Could not save device identity");
        }
        return new String[]{id,key};
    }
    static void save(Context context, String url, String access, String voiceProvider) throws Exception {
        validate(url);
        if (!voiceProvider.equals("murf-direct") && !voiceProvider.equals("agora-engine")) throw new IllegalArgumentException("Select a voice mode");
        if (access.trim().length() < 6) throw new IllegalArgumentException("Use the app access token configured on your backend (at least 6 characters).");
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("url", url.trim().replaceAll("/+$", "")).putString("access", access.trim()).putString("voice_provider", voiceProvider).apply();
    }
    private static void validate(String value) throws Exception {
        URI uri = new URI(value.trim());
        String host = uri.getHost();
        boolean loopback = "127.0.0.1".equals(host) || "localhost".equals(host) || "10.0.2.2".equals(host);
        boolean privateLan = host != null && host.matches("(?:10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(?:1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3})");
        if (host == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
            || !("https".equals(uri.getScheme()) || ((loopback || (BuildConfig.DEBUG && privateLan)) && "http".equals(uri.getScheme())))) {
            throw new IllegalArgumentException("Use HTTPS, or a private Wi-Fi IP in a debug build, or USB localhost.");
        }
    }
    String url() { return base; }
    String access() { return access; }
    String voiceProvider() { return voiceProvider; }
    boolean configured() { return !base.isEmpty() && !access.isEmpty(); }
    JSONObject capabilities() throws Exception { return request("GET", "/v1/capabilities", null); }
    JSONObject start() throws Exception {
        return request("POST", "/v1/sessions", new JSONObject().put("source_language", "auto").put("target_language", profile.language()).put("voice_id", profile.voice()).put("voice_provider", voiceProvider));
    }
    JSONObject startBubble() throws Exception {
        String context=ListeningState.sourceText+"\n"+ListeningState.translatedText;
        if(context.length()>8000)context=context.substring(context.length()-8000);
        return request("POST", "/v1/sessions",new JSONObject().put("mode","bubble").put("source_language","auto")
            .put("target_language",profile.language()).put("voice_id",profile.voice()).put("voice_provider","agora-engine").put("context",context).put("timezone",java.util.TimeZone.getDefault().getID()).put("device_apps",new DeviceTools(this.context).apps()));
    }
    JSONObject overview() throws Exception{return request("GET","/v1/me/overview",null);}
    JSONObject tool(String name,JSONObject args) throws Exception{return request("POST","/v1/me/tools",new JSONObject().put("request_id",java.util.UUID.randomUUID().toString()).put("name",name).put("arguments",args));}
    void deviceResult(String id,JSONObject result) throws Exception{request("POST","/v1/me/actions/"+id+"/result",result);}
    JSONObject voices() throws Exception { return request("GET", "/v1/voices", null); }
    byte[] preview(String voice, String language) throws Exception {
        validate(base);
        String query="?voice_id="+URLEncoder.encode(voice,"UTF-8")+"&language="+URLEncoder.encode(language,"UTF-8");
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/v1/voice-preview"+query).openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(10000);c.setReadTimeout(35000);
        c.setRequestProperty("Authorization","Bearer "+access);
        try {
            if(c.getResponseCode()!=200) throw new IOException("Preview unavailable. Stop listening and try again.");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[8192];int n;
                while((n=in.read(buffer))!=-1) { if(out.size()+n>700000)throw new IOException("Preview too large");out.write(buffer,0,n); }
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }
    JSONObject status(String id) throws Exception { return request("GET", "/v1/sessions/" + id, null); }
    void stop(String id) throws Exception { request("POST", "/v1/sessions/" + id + "/stop", new JSONObject()); }
    JSONObject audioManifest(String id, int after) throws Exception {
        return request("GET", "/v1/sessions/" + id + "/audio?after=" + after, null);
    }
    HttpURLConnection audioStream(String id, int after) throws Exception {
        validate(base);
        HttpURLConnection c = (HttpURLConnection)new URL(base + "/v1/sessions/" + id + "/pcm-stream-v3?after=" + after).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + access);
        c.setRequestProperty("X-AnyVoice-Device",deviceId);c.setRequestProperty("X-AnyVoice-Device-Key",deviceKey);
        if (c.getResponseCode() != 200) { c.disconnect(); throw new IOException("Audio stream unavailable"); }
        return c;
    }
    void audioPlayed(String id, int sequence) throws Exception {
        request("POST", "/v1/sessions/" + id + "/audio/" + sequence + "/played", new JSONObject());
    }
    byte[] audioClip(String id, int sequence, int expectedBytes) throws Exception {
        validate(base);
        HttpURLConnection c = (HttpURLConnection)new URL(base + "/v1/sessions/" + id + "/audio/" + sequence).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setRequestProperty("Authorization", "Bearer " + access);
        c.setRequestProperty("X-AnyVoice-Device",deviceId);c.setRequestProperty("X-AnyVoice-Device-Key",deviceKey);
        try {
            if (c.getResponseCode() != 200) throw new IOException("Audio download failed (" + c.getResponseCode() + ")");
            if (expectedBytes < 2 || expectedBytes > 16000 * 2 * 90 || expectedBytes % 2 != 0)
                throw new IOException("Invalid prepared audio length");
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (out.size() + n > expectedBytes) throw new IOException("Audio download exceeded expected length");
                    out.write(buffer, 0, n);
                }
                if (out.size() != expectedBytes) throw new IOException("Audio download incomplete");
                return out.toByteArray();
            }
        } finally { c.disconnect(); }
    }

    private JSONObject request(String method, String path, JSONObject body) throws Exception {
        validate(base);
        HttpURLConnection connection = (HttpURLConnection) new URL(base + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(path.equals("/v1/sessions") ? 80000 : path.endsWith("/stop") ? 45000 : 10000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Authorization", "Bearer " + access);
        connection.setRequestProperty("X-AnyVoice-Device",deviceId);connection.setRequestProperty("X-AnyVoice-Device-Key",deviceKey);
        connection.setRequestProperty("Accept", "application/json");
        try {
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream input = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[2048]; int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (bytes.size() + n > 65536) throw new IOException("Backend response is too large");
                        bytes.write(buffer, 0, n);
                    }
                    text = bytes.toString(StandardCharsets.UTF_8.name());
                }
            }
            JSONObject result;
            try { result = new JSONObject(text); }
            catch (Exception e) { throw new IOException("Backend returned an unreadable response (HTTP " + status + ")"); }
            if (status < 200 || status >= 300) {
                String detail = result.optString("detail", "Backend request failed");
                throw new IOException(detail.substring(0, Math.min(220, detail.length())));
            }
            return result;
        } finally { connection.disconnect(); }
    }
}
