package com.kyle.lightjourney;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;

public class PandoraCaptureService extends Service {
    static final String EXTRA_RESULT_CODE = "music_light_projection_result";
    static final String EXTRA_PROJECTION_DATA = "music_light_projection_data";
    private static final String CHANNEL = "music_light_audio_link";
    private static final int NOTIFICATION_ID = 4071;
    private static final int SAMPLE_RATE = 44100;
    private static final int READ_SAMPLES = 1024;

    private volatile boolean running = false;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private AudioRecord recorder;
    private Thread worker;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL, "Music Light audio link", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when Music Light is listening for Pandora playback audio.");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT < 29) {
            MainActivity.captureStatus = "Live playback capture requires Android 10 or newer";
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent data;
        if (Build.VERSION.SDK_INT >= 33) {
            data = intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        } else {
            data = (Intent) intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
        }
        if (resultCode != android.app.Activity.RESULT_OK || data == null) {
            MainActivity.captureStatus = "Android did not provide playback-capture permission";
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, CHANNEL)
                    : new Notification.Builder(this);
            Intent launch = new Intent(this, MainActivity.class);
            launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 4071, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification notification = builder
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setContentTitle("Music Light Pandora")
                    .setContentText("Audio link is active. Return to Music Light to see the visualizer.")
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }

            MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("Android returned no MediaProjection");

            projectionCallback = new MediaProjection.Callback() {
                @Override public void onStop() {
                    MainActivity.captureStatus = "Android stopped playback capture";
                    stopSelf();
                }
            };
            projection.registerCallback(projectionCallback, new Handler(Looper.getMainLooper()));
            startRecorder();
            return START_NOT_STICKY;
        } catch (Exception e) {
            MainActivity.captureStatus = "Playback capture unavailable: " + e.getClass().getSimpleName();
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void startRecorder() throws Exception {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();

        int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = READ_SAMPLES * 2;
        recorder = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min * 2, READ_SAMPLES * 2))
                .setAudioPlaybackCaptureConfig(config)
                .build();
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioRecord initialization failed");
        }

        recorder.startRecording();
        running = true;
        MainActivity.captureActive = true;
        MainActivity.captureHasAudio = false;
        MainActivity.captureStatus = "Audio capture is active; Pandora's capture policy may limit access";
        worker = new Thread(this::readAudio, "MusicLightPandoraCapture");
        worker.start();
    }

    private void readAudio() {
        short[] data = new short[READ_SAMPLES];
        while (running && !Thread.currentThread().isInterrupted()) {
            AudioRecord r = recorder;
            if (r == null) break;
            int n;
            try {
                n = r.read(data, 0, data.length, AudioRecord.READ_BLOCKING);
            } catch (Exception e) {
                MainActivity.captureStatus = "Audio capture read failed";
                break;
            }
            if (n > 0) analyze(data, n);
            else if (n < 0) {
                MainActivity.captureStatus = "Audio capture returned error " + n;
                break;
            }
        }
    }

    private void analyze(short[] samples, int length) {
        int n = Math.min(128, length);
        if (n < 16) return;
        int stride = Math.max(1, length / n);
        double sumSq = 0;
        for (int i = 0; i < length; i++) {
            double v = samples[i] / 32768.0;
            sumSq += v * v;
        }
        float rms = (float) Math.sqrt(sumSq / length);
        MainActivity.captureRms = rms;
        MainActivity.captureHasAudio = rms > 0.0025f;

        double low = 0, mid = 0, high = 0;
        int lc = 0, mc = 0, hc = 0;
        for (int k = 1; k <= 48; k++) {
            double re = 0, im = 0;
            for (int i = 0; i < n; i++) {
                int index = Math.min(length - 1, i * stride);
                double v = (samples[index] / 32768.0) * (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / Math.max(1, n - 1)));
                double angle = 2.0 * Math.PI * k * i / n;
                re += v * Math.cos(angle);
                im -= v * Math.sin(angle);
            }
            double magnitude = Math.sqrt(re * re + im * im) * 2.0 / n;
            double level = Math.min(1.0, magnitude * 8.0);
            if (k <= 5) { low += level * level; lc++; }
            else if (k <= 17) { mid += level * level; mc++; }
            else { high += level * level; hc++; }
        }
        MainActivity.captureBass = (float) Math.min(1.0, Math.sqrt(low / Math.max(1, lc)) * 2.5);
        MainActivity.captureMid = (float) Math.min(1.0, Math.sqrt(mid / Math.max(1, mc)) * 2.5);
        MainActivity.captureHigh = (float) Math.min(1.0, Math.sqrt(high / Math.max(1, hc)) * 2.5);
    }

    @Override public void onDestroy() {
        running = false;
        if (worker != null) worker.interrupt();
        AudioRecord r = recorder;
        recorder = null;
        if (r != null) {
            try { r.stop(); } catch (Exception ignored) {}
            try { r.release(); } catch (Exception ignored) {}
        }
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try {
                if (projectionCallback != null) p.unregisterCallback(projectionCallback);
                p.stop();
            } catch (Exception ignored) {}
        }
        MainActivity.captureActive = false;
        MainActivity.captureHasAudio = false;
        MainActivity.captureBass = MainActivity.captureMid = MainActivity.captureHigh = MainActivity.captureRms = 0;
        stopForeground(true);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
