package dev.hiprobe;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(25, 46, 44), MUTED = Color.rgb(91, 111, 106);
    private static final int GREEN = Color.rgb(20, 113, 89), PAPER = Color.rgb(245, 247, 241);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private Button start, setup, overlay, check;
    private TextView status, source, translated, connection;
    private boolean checking, nextLive = true;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean running = ProbeState.running;
            start.setText(running ? "Stop listening" : checking ? "Checking connection…" : "Start listening");
            start.setEnabled(!checking && !LiveTranslation.isStopping());
            setup.setEnabled(!running && !checking);
            check.setEnabled(!running && !checking);
            status.setText(running ? ProbeState.message : ProbeState.message.startsWith("Ready.") ? "Ready when you are" : ProbeState.message);
            source.setText(ProbeState.sourceText.isEmpty() ? "Your English audio will appear here." : ProbeState.sourceText);
            translated.setText(ProbeState.translatedText.isEmpty() ? "తెలుగులో వినండి.\nYour Telugu translation will appear here." : ProbeState.translatedText);
            connection.setText(new BackendClient(MainActivity.this).configured() ? "Backend saved · English → Telugu" : "Connect your backend to begin");
            overlay.setText(Settings.canDrawOverlays(MainActivity.this) ? "Floating controls enabled ✓" : "Enable floating controls");
            handler.postDelayed(this, 250);
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ProbeState.running) {
            try { new VolumeGuard(this).restore(); }
            catch (RuntimeException e) { ProbeState.message = "Raise media volume manually if it remains silent."; }
        }
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(PAPER);
        LinearLayout body = column(); body.setPadding(dp(24), dp(30), dp(24), dp(24)); scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            body.setPadding(dp(24), dp(18) + insets.getSystemWindowInsetTop(), dp(24), dp(20) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView mark = text(body, "a v  /  ANY VOICE", 13, GREEN); mark.setLetterSpacing(0.15f); bold(mark);
        TextView title = text(body, "A new language.\nThe same meaning.", 31, INK); bold(title);
        text(body, "Listen to English in natural Telugu.", 16, MUTED);
        LinearLayout language = card(body);
        text(language, "FROM                         TO", 11, MUTED);
        TextView pair = text(language, "English     →     తెలుగు", 25, INK); bold(pair);
        text(language, "English audio · Telugu voice", 13, MUTED);
        status = text(body, "Ready when you are", 15, GREEN);
        start = button(body, "Start listening", true, v -> {
            if (ProbeState.running) command(ProbeService.STOP);
            else { nextLive = true; preflight(); }
        });
        text(body, "Play an English video, tap Start, then return to it. Only translated speech plays once connected. Keep alarm volume audible.", 13, MUTED);
        LinearLayout captions = card(body);
        text(captions, "ENGLISH", 11, MUTED);
        source = text(captions, "Your English audio will appear here.", 16, INK);
        text(captions, "తెలుగు  /  TELUGU", 11, GREEN);
        translated = text(captions, "తెలుగులో వినండి.", 20, INK);
        connection = text(body, "Connect your backend to begin", 12, MUTED);
        setup = button(body, "Connection settings", false, v -> settings());
        overlay = button(body, "Enable floating controls", false, v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        check = button(body, "Local audio check · says hi", false, v -> { nextLive = false; requestStart(); });
        text(body, "Live mode sends permitted app audio to Agora and voice services. Your microphone is not used. Some apps block capture; calls are not supported.", 12, MUTED);
        text(body, "ANY VOICE  ·  PHASE 01", 11, GREEN);
        setContentView(scroll);
    }
    private void preflight() {
        BackendClient client = new BackendClient(this);
        if (!client.configured()) { settings(); return; }
        checking = true; refreshControls();
        network.execute(() -> {
            try {
                JSONObject result = client.capabilities();
                if (!result.optBoolean("configured")) {
                    String missing = result.optJSONArray("missing") == null ? "" : result.getJSONArray("missing").join(", ");
                    throw new IllegalStateException(missing.isEmpty() ? result.optString("message") : "Backend needs: " + missing);
                }
                handler.post(() -> { checking = false; if (!isFinishing() && !isDestroyed()) requestStart(); });
            } catch (Exception e) {
                handler.post(() -> { checking = false; ProbeState.message = e.getMessage(); refreshControls(); });
            }
        });
    }
    private void settings() {
        BackendClient saved = new BackendClient(this);
        LinearLayout fields = column(); fields.setPadding(dp(24), dp(8), dp(24), 0);
        text(fields, "Provider keys stay on your server. Enter only its address and app access token.", 14, MUTED);
        EditText url = new EditText(this); url.setSingleLine(true); url.setText(saved.url());
        url.setHint("https://your-backend.example"); url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); fields.addView(url);
        EditText token = new EditText(this); token.setSingleLine(true); token.setText(saved.access());
        token.setHint("App access token"); token.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); fields.addView(token);
        text(fields, "On the same Wi-Fi, use your computer's IP with port 8000 (debug build). USB testing also supports http://127.0.0.1:8000.", 12, MUTED);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Connect Any Voice").setView(fields)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
            try { BackendClient.save(this, url.getText().toString(), token.getText().toString()); ProbeState.message = "Connection saved. Ready to start."; dialog.dismiss(); }
            catch (Exception e) { url.setError(e.getMessage()); }
        }));
        dialog.show();
    }
    private void requestStart() {
        if (ProbeState.running) return;
        if (LiveTranslation.isStopping()) { ProbeState.message = "Finishing the previous session…"; return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10); return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11); return;
        }
        requestCapture();
    }
    private void requestCapture() { startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 20); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestStart();
        else if (request == 11) requestCapture();
        else ProbeState.message = "Allow audio capture to continue. No audio has been muted.";
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20) return;
        if (result != RESULT_OK || data == null) { ProbeState.message = "Capture cancelled. Original audio unchanged."; return; }
        startForegroundService(new Intent(this, ProbeService.class).setAction(ProbeService.START)
            .putExtra("result", result).putExtra("projection", data).putExtra("live", nextLive));
    }
    private void command(String action) { startService(new Intent(this, ProbeService.class).setAction(action)); }
    private void refreshControls() { handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onResume() { super.onResume(); refreshControls(); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() { network.shutdown(); super.onDestroy(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout view = column(); view.setPadding(dp(18), dp(12), dp(18), dp(14));
        GradientDrawable background = new GradientDrawable(); background.setColor(Color.WHITE); background.setCornerRadius(dp(18));
        background.setStroke(dp(1), Color.rgb(226, 232, 222)); view.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(15), 0, dp(8)); parent.addView(view, params); return view;
    }
    private TextView text(LinearLayout parent, String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setPadding(0, dp(7), 0, dp(7)); view.setLineSpacing(dp(2), 1); parent.addView(view); return view;
    }
    private void bold(TextView view) { view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); }
    private Button button(LinearLayout parent, String value, boolean primary, View.OnClickListener listener) {
        Button view = new Button(this); view.setText(value); view.setAllCaps(false); view.setTextSize(primary ? 18 : 14);
        view.setTextColor(primary ? Color.WHITE : GREEN); view.setMinHeight(dp(52));
        GradientDrawable shape = new GradientDrawable(); shape.setColor(primary ? GREEN : Color.TRANSPARENT); shape.setCornerRadius(dp(14));
        view.setBackground(shape); view.setBackgroundTintList(ColorStateList.valueOf(primary ? GREEN : Color.TRANSPARENT));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(primary ? 58 : 52)); params.setMargins(0, dp(5), 0, dp(5));
        view.setOnClickListener(listener); parent.addView(view, params); return view;
    }
}
