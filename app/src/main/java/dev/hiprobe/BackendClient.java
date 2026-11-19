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

    BackendClient(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("connection", Context.MODE_PRIVATE);
        base = prefs.getString("url", BuildConfig.DEFAULT_BACKEND_URL).trim().replaceAll("/+$", "");
        access = prefs.getString("access", "").trim();
    }
    static void save(Context context, String url, String access) throws Exception {
        validate(url);
        if (access.trim().length() < 6) throw new IllegalArgumentException("Use the app access token configured on your backend (at least 6 characters).");
        context.getSharedPreferences("connection", Context.MODE_PRIVATE).edit()
            .putString("url", url.trim().replaceAll("/+$", "")).putString("access", access.trim()).apply();
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
    boolean configured() { return !base.isEmpty() && !access.isEmpty(); }
    JSONObject capabilities() throws Exception { return request("GET", "/v1/capabilities", null); }
    JSONObject start() throws Exception {
        return request("POST", "/v1/sessions", new JSONObject().put("source_language", "en-IN").put("target_language", "te-IN"));
    }
    JSONObject status(String id) throws Exception { return request("GET", "/v1/sessions/" + id, null); }
    void stop(String id) throws Exception { request("POST", "/v1/sessions/" + id + "/stop", new JSONObject()); }

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
