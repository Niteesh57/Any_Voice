package dev.hiprobe.testsource;

import android.app.Activity;
import android.os.Bundle;
import android.media.*;
import android.widget.TextView;

/** Developer-only, separate-UID known capturable source. Not distributed with the probe. */
public final class SourceActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Known test source: continuous 440 Hz media tone.\nKeeps playing when another activity is shown. Force-stop this test package to end it.");
        label.setTextSize(24); setContentView(label);
        startForegroundService(new android.content.Intent(this, SourceService.class));
    }
    public static final class SourceService extends android.app.Service {
        private AudioTrack track;
        @Override public int onStartCommand(android.content.Intent intent, int flags, int id) {
        if (track != null) return START_NOT_STICKY;
        getSystemService(android.app.NotificationManager.class).createNotificationChannel(new android.app.NotificationChannel("source", "Known audio test source", android.app.NotificationManager.IMPORTANCE_LOW));
        startForeground(1, new android.app.Notification.Builder(this, "source").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Known capturable media tone").build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        short[] pcm = new short[16000];
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short)(8000 * Math.sin(2 * Math.PI * 440 * i / 16000));
        track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_ALL).build())
            .setAudioFormat(new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
        track.write(pcm, 0, pcm.length); track.setLoopPoints(0, pcm.length, -1); track.play();
        return START_NOT_STICKY;
        }
        @Override public android.os.IBinder onBind(android.content.Intent intent) { return null; }
        @Override public void onDestroy() { if (track != null) { track.stop(); track.release(); } super.onDestroy(); }
    }
}
