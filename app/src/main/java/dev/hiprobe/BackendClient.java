package dev.hiprobe;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Only the backend URL and app access token live on the phone, never provider keys. */
final class BackendClient {
    private final String base;
    private final String access;
    private final String voiceProvider;

    BackendClient(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        base = prefs.getString("url", BuildConfig.DEFAULT_BACKEND_URL).trim().replaceAll("/+$", "");
        access = prefs.getString("access", "").trim();
        voiceProvider = prefs.getString("voice_provider", "murf-direct");
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
        return request("POST", "/v1/sessions", new JSONObject().put("source_language", "en-IN").put("target_language", "te-IN").put("voice_provider", voiceProvider));
    }
    JSONObject status(String id) throws Exception { return request("GET", "/v1/sessions/" + id, null); }
    void stop(String id) throws Exception { request("POST", "/v1/sessions/" + id + "/stop", new JSONObject()); }
    JSONObject audioManifest(String id, int after) throws Exception {
        return request("GET", "/v1/sessions/" + id + "/audio?after=" + after, null);
    }
    HttpURLConnection audioStream(String id, int after) throws Exception {
        validate(base);
        HttpURLConnection c = (HttpURLConnection)new URL(base + "/v1/sessions/" + id + "/pcm-stream-v2?after=" + after).openConnection();
        c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("Authorization", "Bearer " + access);
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
