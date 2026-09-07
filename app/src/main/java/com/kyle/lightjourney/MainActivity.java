package com.kyle.lightjourney;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private VisualView visualView;
    private FrameLayout root;
    private LinearLayout controlsPanel;
    private TextView timerView;
    private Button stopButton;
    private AmbientEngine ambientEngine;
    private boolean sessionRunning = false;
    private long sessionEndMs = 0L;
    private int selectedMinutes = 5;
    private float selectedIntensity = 0.45f;
    private float selectedRateHz = 0.4f;
    private boolean ambientEnabled = true;

    private final Runnable timerTick = new Runnable() {
        @Override
        public void run() {
            if (!sessionRunning) return;
            long remaining = sessionEndMs - System.currentTimeMillis();
            if (remaining <= 0) {
                stopSession();
                return;
            }
            long seconds = remaining / 1000;
            timerView.setText(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        showConsentScreen();
    }

    private void showConsentScreen() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        ScrollView scroll = new ScrollView(this);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(28), dp(42), dp(28), dp(42));
        panel.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = text("Light Journey", 30, Color.WHITE);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, matchWrap());

        TextView subtitle = text("Eyes-closed light + sound experiment", 17, 0xFFBDBDBD);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = matchWrap();
        subLp.topMargin = dp(8);
        panel.addView(subtitle, subLp);

        TextView warning = text(
                "Safety first\n\nThis app uses smooth brightness modulation and color movement. It does not use the camera flash and the pulse rate is capped at 1 Hz. Even so, bright or rhythmic light can cause discomfort, migraine, dizziness, nausea, or seizures in susceptible people.\n\nDo not use while driving, walking, bathing, standing, or near stairs. Stop immediately if you feel unwell. If you have a seizure disorder, photosensitivity, unexplained blackouts, or a clinician has told you to avoid flashing light, do not use this app.",
                16, 0xFFE0E0E0);
        LinearLayout.LayoutParams warnLp = matchWrap();
        warnLp.topMargin = dp(28);
        panel.addView(warning, warnLp);

        CheckBox agree = new CheckBox(this);
        agree.setText("I understand the risks and I am in a safe place to try this.");
        agree.setTextColor(Color.WHITE);
        agree.setTextSize(16);
        LinearLayout.LayoutParams agreeLp = matchWrap();
        agreeLp.topMargin = dp(26);
        panel.addView(agree, agreeLp);

        Button enter = new Button(this);
        enter.setText("ENTER CONTROLS");
        enter.setEnabled(false);
        LinearLayout.LayoutParams enterLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        enterLp.topMargin = dp(18);
        panel.addView(enter, enterLp);

        agree.setOnCheckedChangeListener((buttonView, isChecked) -> enter.setEnabled(isChecked));
        enter.setOnClickListener(v -> buildMainScreen());

        scroll.addView(panel);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    private void buildMainScreen() {
        root = new FrameLayout(this);
        visualView = new VisualView();
        root.addView(visualView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        controlsPanel = new LinearLayout(this);
        controlsPanel.setOrientation(LinearLayout.VERTICAL);
        controlsPanel.setPadding(dp(24), dp(30), dp(24), dp(30));
        controlsPanel.setBackgroundColor(0xD9000000);

        TextView title = text("Light Journey", 28, Color.WHITE);
        controlsPanel.addView(title, matchWrap());

        TextView note = text("Smooth-pulse mode • no camera flash • maximum 1.0 Hz", 14, 0xFFBDBDBD);
        LinearLayout.LayoutParams noteLp = matchWrap();
        noteLp.topMargin = dp(4);
        controlsPanel.addView(note, noteLp);

        TextView intensityLabel = text("Intensity: 45%", 17, Color.WHITE);
        LinearLayout.LayoutParams labelLp = matchWrap();
        labelLp.topMargin = dp(28);
        controlsPanel.addView(intensityLabel, labelLp);

        SeekBar intensityBar = new SeekBar(this);
        intensityBar.setMax(50);
        intensityBar.setProgress(25);
        controlsPanel.addView(intensityBar, matchWrap());
        intensityBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedIntensity = 0.20f + (progress / 100f);
                intensityLabel.setText(String.format(Locale.US, "Intensity: %d%%", Math.round(selectedIntensity * 100)));
            }
        });

        TextView rateLabel = text("Pulse rate: 0.4 Hz", 17, Color.WHITE);
        LinearLayout.LayoutParams rateLp = matchWrap();
        rateLp.topMargin = dp(22);
        controlsPanel.addView(rateLabel, rateLp);

        SeekBar rateBar = new SeekBar(this);
        rateBar.setMax(9);
        rateBar.setProgress(3);
        controlsPanel.addView(rateBar, matchWrap());
        rateBar.setOnSeekBarChangeListener(new SimpleSeekListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                selectedRateHz = 0.1f + (progress * 0.1f);
                rateLabel.setText(String.format(Locale.US, "Pulse rate: %.1f Hz", selectedRateHz));
            }
        });

        TextView durationLabel = text("Session length", 17, Color.WHITE);
        LinearLayout.LayoutParams durLp = matchWrap();
        durLp.topMargin = dp(22);
        controlsPanel.addView(durationLabel, durLp);

        RadioGroup durations = new RadioGroup(this);
        durations.setOrientation(RadioGroup.HORIZONTAL);
        int[] mins = {3, 5, 10};
        for (int m : mins) {
            RadioButton rb = new RadioButton(this);
            rb.setText(m + " min");
            rb.setTextColor(Color.WHITE);
            rb.setTag(m);
            if (m == 5) rb.setChecked(true);
            durations.addView(rb);
        }
        durations.setOnCheckedChangeListener((group, checkedId) -> {
            RadioButton selected = group.findViewById(checkedId);
            if (selected != null && selected.getTag() instanceof Integer) {
                selectedMinutes = (Integer) selected.getTag();
            }
        });
        controlsPanel.addView(durations, matchWrap());

        CheckBox ambient = new CheckBox(this);
        ambient.setText("Soft ambient drone");
        ambient.setTextColor(Color.WHITE);
        ambient.setTextSize(16);
        ambient.setChecked(true);
        LinearLayout.LayoutParams ambientLp = matchWrap();
        ambientLp.topMargin = dp(16);
        controlsPanel.addView(ambient, ambientLp);
        ambient.setOnCheckedChangeListener((buttonView, isChecked) -> ambientEnabled = isChecked);

        TextView tips = text(
                "For the intended effect: lie or sit somewhere safe, keep the phone at a comfortable distance, close your eyes, and lower intensity if the light feels harsh. You can stop at any time.",
                14, 0xFFCCCCCC);
        LinearLayout.LayoutParams tipsLp = matchWrap();
        tipsLp.topMargin = dp(18);
        controlsPanel.addView(tips, tipsLp);

        Button start = new Button(this);
        start.setText("START SESSION");
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(64));
        startLp.topMargin = dp(24);
        controlsPanel.addView(start, startLp);
        start.setOnClickListener(v -> startSession());

        scroll.addView(controlsPanel);
        root.addView(scroll, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        timerView = text("", 24, Color.WHITE);
        timerView.setGravity(Gravity.CENTER);
        timerView.setVisibility(View.GONE);
        FrameLayout.LayoutParams timerLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(64), Gravity.TOP);
        timerLp.topMargin = dp(16);
        root.addView(timerView, timerLp);

        stopButton = new Button(this);
        stopButton.setText("STOP");
        stopButton.setTextSize(20);
        stopButton.setTextColor(Color.WHITE);
        stopButton.setBackgroundColor(0xFFB71C1C);
        stopButton.setVisibility(View.GONE);
        stopButton.setOnClickListener(v -> stopSession());
        FrameLayout.LayoutParams stopLp = new FrameLayout.LayoutParams(dp(180), dp(72), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        stopLp.bottomMargin = dp(30);
        root.addView(stopButton, stopLp);

        setContentView(root);
    }

    private void startSession() {
        if (sessionRunning) return;
        sessionRunning = true;
        controlsPanel.setVisibility(View.GONE);
        timerView.setVisibility(View.VISIBLE);
        stopButton.setVisibility(View.VISIBLE);
        sessionEndMs = System.currentTimeMillis() + (selectedMinutes * 60_000L);
        visualView.start(selectedRateHz, selectedIntensity);

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.min(0.72f, 0.25f + selectedIntensity * 0.65f);
        getWindow().setAttributes(lp);

        if (ambientEnabled) {
            ambientEngine = new AmbientEngine();
            ambientEngine.start();
        }
        handler.post(timerTick);
    }

    private void stopSession() {
        sessionRunning = false;
        handler.removeCallbacks(timerTick);
        if (visualView != null) visualView.stop();
        if (ambientEngine != null) {
            ambientEngine.stop();
            ambientEngine = null;
        }
        if (controlsPanel != null) controlsPanel.setVisibility(View.VISIBLE);
        if (timerView != null) timerView.setVisibility(View.GONE);
        if (stopButton != null) stopButton.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    @Override
    protected void onPause() {
        if (sessionRunning) stopSession();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (sessionRunning) {
            stopSession();
        } else {
            super.onBackPressed();
        }
    }

    private TextView text(String value, int sp, int color) {
        TextView tv = new TextView(this);
        tv.setText(value);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        tv.setLineSpacing(0f, 1.15f);
        return tv;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private abstract static class SimpleSeekListener implements SeekBar.OnSeekBarChangeListener {
        @Override public void onStartTrackingTouch(SeekBar seekBar) {}
        @Override public void onStopTrackingTouch(SeekBar seekBar) {}
    }

    private class VisualView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running = false;
        private float rateHz = 0.4f;
        private float intensity = 0.45f;
        private long startNanos;

        VisualView() {
            super(MainActivity.this);
            setBackgroundColor(Color.BLACK);
        }

        void start(float rate, float amount) {
            rateHz = Math.max(0.1f, Math.min(1.0f, rate));
            intensity = Math.max(0.2f, Math.min(0.7f, amount));
            startNanos = System.nanoTime();
            running = true;
            invalidate();
        }

        void stop() {
            running = false;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!running) {
                canvas.drawColor(Color.BLACK);
                return;
            }

            float t = (System.nanoTime() - startNanos) / 1_000_000_000f;
            float pulse = 0.5f + 0.5f * (float) Math.sin(2.0 * Math.PI * rateHz * t - Math.PI / 2.0);
            float hue = (t * 8f) % 360f;
            float value = Math.min(0.92f, 0.08f + intensity * (0.30f + 0.55f * pulse));
            int base = Color.HSVToColor(new float[]{hue, 0.72f, value});
            canvas.drawColor(base);

            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float maxR = (float) Math.hypot(cx, cy);
            for (int i = 9; i >= 1; i--) {
                float phase = 0.5f + 0.5f * (float) Math.sin((t * 0.65f) + i * 0.55f);
                float ringValue = Math.min(1f, 0.18f + value * (0.50f + phase * 0.35f));
                int ringColor = Color.HSVToColor(
                        Math.round(35 + 45 * phase),
                        new float[]{(hue + i * 13f) % 360f, 0.65f, ringValue});
                paint.setColor(ringColor);
                canvas.drawCircle(cx, cy, maxR * (i / 10f), paint);
            }

            postInvalidateOnAnimation();
        }
    }

    private static class AmbientEngine {
        private volatile boolean running = false;
        private Thread thread;

        void start() {
            if (running) return;
            running = true;
            thread = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
                final int sampleRate = 22050;
                final int minBuffer = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                AudioTrack track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuffer, 4096))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();

                short[] buffer = new short[2048];
                long sampleIndex = 0;
                try {
                    track.play();
                    while (running) {
                        for (int i = 0; i < buffer.length; i++, sampleIndex++) {
                            double time = sampleIndex / (double) sampleRate;
                            double slow = 0.65 + 0.35 * Math.sin(2 * Math.PI * 0.07 * time);
                            double sample =
                                    Math.sin(2 * Math.PI * 110.0 * time) * 0.50 +
                                    Math.sin(2 * Math.PI * 165.0 * time) * 0.30 +
                                    Math.sin(2 * Math.PI * 220.0 * time) * 0.20;
                            buffer[i] = (short) (sample * slow * 2200);
                        }
                        track.write(buffer, 0, buffer.length);
                    }
                } finally {
                    try { track.stop(); } catch (Exception ignored) {}
                    track.release();
                }
            }, "LightJourneyAudio");
            thread.start();
        }

        void stop() {
            running = false;
        }
    }
}
