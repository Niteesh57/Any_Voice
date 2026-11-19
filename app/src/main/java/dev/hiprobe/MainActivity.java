package dev.hiprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView diagnostics;
    private Button start;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            diagnostics.setText(ProbeState.describe());
            start.setEnabled(!ProbeState.running);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!ProbeState.running) {
            try { new VolumeGuard(this).restore(); }
            catch (RuntimeException e) { ProbeState.message = "Use the phone volume buttons to restore media volume: " + e.getMessage(); }
        }
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(32), dp(24), dp(24));
        body.setBackgroundColor(Color.rgb(244, 247, 249));
        scroll.addView(body);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            body.setPadding(dp(24), dp(20) + insets.getSystemWindowInsetTop(), dp(24), dp(24) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        label(body, "AUDIO EXPERIMENT / 01", 13);
        label(body, "Any Voice", 34);
        label(body, "OnePlus 7 feasibility probe", 19);
        label(body, "Plays a repeated hi when permitted internal audio is detected. Media volume is set to zero; hi uses the alarm audio stream. Your microphone is never opened. No translation, recording files, or network access.", 16);
        label(body, "1. Start a spoken YouTube video.\n2. Return here and start the probe. Approve Android's capture prompt.\n3. Return to the video. Listen for hi and no original sound.\n4. Use the floating panel or notification to stop.\n\nKeep alarm volume audible. If prompted to share one app or the entire screen, choose the entire screen for switching between apps. No screen images are collected.", 16);
        button(body, "Enable floating controls (optional)", v -> startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        start = button(body, "Start capture + mute + hi", v -> requestStart());
        button(body, "Original / replacement comparison", v -> command(ProbeService.TOGGLE));
        button(body, "Test hi output only", v -> command(ProbeService.TEST_VOICE));
        button(body, "Stop and restore media volume", v -> {
            if (ProbeState.running) command(ProbeService.STOP);
            else {
                try { new VolumeGuard(this).restore(); }
                catch (RuntimeException e) { ProbeState.message = e.toString(); }
            }
        });
        diagnostics = label(body, "", 15);
        label(body, "Reading the result", 22);
        label(body, "Non-silent buffers while muted must increase while you hear only hi. That is the useful result for the next stage.\n\nIf buffers increase only with original sound restored, capture is affected by muting. If both stay at zero, the source may block capture, be paused, or be silent. The hi-output test alone does not prove capture works.\n\nThis first probe detects sound energy, not speech. Music may trigger hi too. Calls, DRM content, Bluetooth, and universal audio interception are not validated.", 16);
        setContentView(scroll);
    }

    private void requestStart() {
        if (ProbeState.running) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 10);
            return;
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
            return;
        }
        requestCapture();
    }
    private void requestCapture() {
        startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(), 20);
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 10 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestStart();
        else if (request == 11) requestCapture();
        else ProbeState.message = "Audio capture permission is required. Nothing has been muted.";
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 20) return;
        if (result != RESULT_OK || data == null) {
            ProbeState.message = "Capture cancelled. Nothing has been muted.";
            return;
        }
        Intent service = new Intent(this, ProbeService.class).setAction(ProbeService.START)
            .putExtra("result", result).putExtra("projection", data);
        startForegroundService(service);
    }
    private void command(String action) {
        if (!ProbeState.running) { ProbeState.message = "Start the probe first."; return; }
        startService(new Intent(this, ProbeService.class).setAction(action));
    }
    @Override protected void onResume() { super.onResume(); handler.post(refresh); }
    @Override protected void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this);
        view.setText(text); view.setTextSize(size); view.setTextColor(Color.rgb(20, 42, 54));
        view.setPadding(0, dp(8), 0, dp(12)); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String text, View.OnClickListener action) {
        Button view = new Button(this); view.setText(text); view.setAllCaps(false);
        view.setMinHeight(dp(52)); view.setOnClickListener(action); parent.addView(view); return view;
    }
}
