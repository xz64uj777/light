package com.kyle.lightjourney;

import android.app.*;
import android.Manifest;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.media.MediaPlayer;
import android.media.projection.MediaProjectionManager;
import android.media.audiofx.Visualizer;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.provider.Settings;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.content.ComponentName;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {
    static final int PICK = 42;
    static final int REQ_PANDORA_CAPTURE = 7102;
    static final int REQ_RECORD_AUDIO = 7103;
    public static volatile boolean captureActive = false, captureHasAudio = false;
    public static volatile float captureBass = 0, captureMid = 0, captureHigh = 0, captureRms = 0;
    public static volatile String captureStatus = "Audio link inactive";
    final Handler h = new Handler(Looper.getMainLooper());
    final ArrayList<Uri> queue = new ArrayList<>();
    MediaPlayer mp;
    Visualizer av;
    VizView viz;
    LinearLayout panel;
    TextView song, time, queueInfo;
    SeekBar seek;
    Button play, shuffle, repeat, preset, pandora, stopLink;
    boolean pandoraMode = false, externalPlaying = false;
    long lastSessionCheck = 0;
    int current = -1;
    boolean shuffleOn = false, repeatOn = false;
    int presetId = 0;

    final Runnable clock = new Runnable() {
        public void run() {
            if (SystemClock.uptimeMillis() - lastSessionCheck > 1500) refreshPandora();
            if (mp != null) {
                seek.setMax(Math.max(1, mp.getDuration()));
                seek.setProgress(mp.getCurrentPosition());
                time.setText(fmt(mp.getCurrentPosition()) + " / " + fmt(mp.getDuration()));
            }
            h.postDelayed(this, 250);
        }
    };

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pandoraMode = getPreferences(MODE_PRIVATE).getBoolean("pandora_mode", false);
        build();
        h.post(clock);
    }

    void build() {
        FrameLayout root = new FrameLayout(this);
        viz = new VizView(this);
        root.addView(viz, new FrameLayout.LayoutParams(-1, -1));

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(d(12), d(10), d(12), d(10));
        panel.setBackgroundColor(0xD9000008);

        song = t("MUSIC LIGHT", 18, Color.WHITE);
        time = t("0:00 / 0:00", 12, 0xffbbbbc8);
        queueInfo = t("Queue: empty", 11, 0xffaaaabd);
        panel.addView(song);
        panel.addView(queueInfo);
        panel.addView(time);

        seek = new SeekBar(this);
        panel.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                if (u && mp != null) mp.seekTo(p);
            }
        });

        LinearLayout row1 = new LinearLayout(this);
        row1.setGravity(Gravity.CENTER);
        Button open = btn("ADD MUSIC");
        Button prev = btn("PREV");
        play = btn("PLAY");
        Button next = btn("NEXT");
        row1.addView(open, lp()); row1.addView(prev, lp()); row1.addView(play, lp()); row1.addView(next, lp());
        panel.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setGravity(Gravity.CENTER);
        shuffle = btn("SHUFFLE OFF");
        repeat = btn("REPEAT OFF");
        preset = btn("SCENE 1");
        Button full = btn("VISUAL ONLY");
        row2.addView(shuffle, lp()); row2.addView(repeat, lp()); row2.addView(preset, lp()); row2.addView(full, lp());
        panel.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setGravity(Gravity.CENTER);
        pandora = btn("CONNECT PANDORA");
        stopLink = btn("STOP AUDIO LINK");
        row3.addView(pandora, lp());
        row3.addView(stopLink, lp());
        panel.addView(row3);
        stopLink.setEnabled(captureActive);
        pandora.setOnClickListener(v -> connectPandora());
        stopLink.setOnClickListener(v -> {
            stopService(new Intent(this, PandoraCaptureService.class));
            captureActive = false;
            captureHasAudio = false;
            captureStatus = "Audio link stopped";
            externalPlaying = false;
            pandoraMode = false;
            getPreferences(MODE_PRIVATE).edit().putBoolean("pandora_mode", false).apply();
            viz.r.setExternalPlayback(false);
            song.setText("Pandora audio link stopped");
            pandora.setText("CONNECT PANDORA");
            stopLink.setEnabled(false);
        });

        open.setOnClickListener(v -> pick());
        prev.setOnClickListener(v -> previous());
        next.setOnClickListener(v -> next(false));
        play.setOnClickListener(v -> toggle());
        shuffle.setOnClickListener(v -> { shuffleOn = !shuffleOn; shuffle.setText(shuffleOn ? "SHUFFLE ON" : "SHUFFLE OFF"); });
        repeat.setOnClickListener(v -> { repeatOn = !repeatOn; repeat.setText(repeatOn ? "REPEAT ON" : "REPEAT OFF"); });
        preset.setOnClickListener(v -> {
            presetId = (presetId + 1) % 4;
            preset.setText("SCENE " + (presetId + 1));
            viz.r.setPreset(presetId);
        });
        full.setOnClickListener(v -> panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        FrameLayout.LayoutParams pp = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        pp.setMargins(d(6), 0, d(6), d(6));
        root.addView(panel, pp);

        TextView tag = t("LIVE • 3D AUDIO REACTIVE", 11, 0xffeeeeff);
        tag.setGravity(Gravity.CENTER);
        tag.setBackgroundColor(0x66000000);
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(d(210), d(32), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = d(12);
        root.addView(tag, tp);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (viz != null) viz.onResume();
        if (pandoraMode) refreshPandora();
    }

    @Override protected void onPause() {
        if (viz != null) viz.onPause();
        super.onPause();
    }

    void connectPandora() {
        pandoraMode = true;
        getPreferences(MODE_PRIVATE).edit().putBoolean("pandora_mode", true).apply();
        if (!notificationAccessEnabled()) {
            song.setText("Step 1: allow Music Light Pandora in Notification Access, then return and tap CONNECT PANDORA again.");
            pandora.setText("ENABLE MEDIA ACCESS");
            try { startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
            catch (Exception e) { song.setText("Open Android Settings → Notification access and enable Music Light Pandora."); }
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !captureActive) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                song.setText("Allow microphone/audio permission so Android can offer music playback capture. No audio is uploaded.");
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD_AUDIO);
            } else {
                requestAudioCaptureConsent();
            }
            return;
        }
        openPandora();
    }

    void requestAudioCaptureConsent() {
        try {
            MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            song.setText("Approve Android's capture prompt to let Music Light react to Pandora audio. Android may block capture for some protected streams.");
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PANDORA_CAPTURE);
        } catch (Exception e) {
            captureStatus = "Android audio capture is unavailable";
            song.setText("Audio capture is unavailable. Opening Pandora in status-only mode.");
            openPandora();
        }
    }

    void openPandora() {
        Intent launch = getPackageManager().getLaunchIntentForPackage("com.pandora.android");
        if (launch == null) {
            song.setText("Pandora app was not found. Install/open Pandora first, then tap CONNECT PANDORA again.");
            pandora.setText("PANDORA APP NOT FOUND");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(launch);
            pandora.setText(captureActive ? "AUDIO LINK ACTIVE" : "PANDORA OPENED • RETURN HERE");
            song.setText(captureActive ? "Pandora opened. Start a station, then return here; audio link: " + captureStatus
                    : "Pandora opened. Start a station, then return to Music Light.");
        } catch (Exception e) {
            song.setText("Could not open Pandora. Launch Pandora manually, start a station, then return here.");
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestAudioCaptureConsent();
            } else {
                captureStatus = "Audio capture permission declined";
                song.setText("Audio permission declined. Connecting in status-only mode; Pandora may not drive the lightshow.");
                openPandora();
            }
        }
    }

    boolean notificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName());
    }

    void refreshPandora() {
        lastSessionCheck = SystemClock.uptimeMillis();
        if (stopLink != null) stopLink.setEnabled(captureActive);
        if (!pandoraMode) {
            if (pandora != null) pandora.setText(captureActive ? "AUDIO LINK ACTIVE" : "CONNECT PANDORA");
            return;
        }
        if (!notificationAccessEnabled()) {
            if (pandora != null) pandora.setText("ENABLE MEDIA ACCESS");
            externalPlaying = false;
            viz.r.setExternalPlayback(false);
            return;
        }
        try {
            MediaSessionManager manager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> sessions = manager.getActiveSessions(new ComponentName(this, PandoraListener.class));
            boolean found = false;
            for (MediaController mc : sessions) {
                String pkg = mc.getPackageName() == null ? "" : mc.getPackageName().toLowerCase(Locale.US);
                if (pkg.contains("pandora")) {
                    found = true;
                    android.media.MediaMetadata md = mc.getMetadata();
                    String title = md == null ? null : md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
                    String artist = md == null ? null : md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
                    PlaybackState state = mc.getPlaybackState();
                    externalPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                    viz.r.setExternalPlayback(externalPlaying);
                    pandora.setText(externalPlaying ? (captureActive ? "PANDORA PLAYING • AUDIO LINK" : "PANDORA PLAYING") : "PANDORA CONNECTED");
                    if (mp == null || !mp.isPlaying()) {
                        if (title != null && !title.isEmpty()) {
                            song.setText("♫ " + title + (artist == null || artist.isEmpty() ? "" : " — " + artist));
                        } else {
                            song.setText(externalPlaying ? "Pandora is playing" : "Pandora is paused");
                        }
                    }
                    break;
                }
            }
            if (!found) {
                externalPlaying = false;
                viz.r.setExternalPlayback(false);
                pandora.setText(captureActive ? "AUDIO LINK ON • OPEN PANDORA" : "OPEN PANDORA • NO SESSION");
                if (mp == null) song.setText(captureActive ? "Audio link ready. Start a Pandora station, then return here. " + captureStatus : "No Pandora media session found. Tap CONNECT PANDORA to open Pandora.");
            }
        } catch (SecurityException e) {
            externalPlaying = false;
            viz.r.setExternalPlayback(false);
            pandora.setText("GRANT NOTIFICATION ACCESS");
        } catch (Exception e) {
            externalPlaying = false;
            viz.r.setExternalPlayback(false);
            pandora.setText("PANDORA NOT DETECTED");
        }
    }

    void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK);
    }

    @Override protected void onActivityResult(int r, int c, Intent x) {
        super.onActivityResult(r, c, x);
        if (r == REQ_PANDORA_CAPTURE) {
            if (c == RESULT_OK && x != null) {
                Intent service = new Intent(this, PandoraCaptureService.class);
                service.putExtra(PandoraCaptureService.EXTRA_RESULT_CODE, c);
                service.putExtra(PandoraCaptureService.EXTRA_PROJECTION_DATA, x);
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
                    else startService(service);
                    captureStatus = "Starting Android playback capture";
                    pandora.setText("AUDIO LINK STARTING");
                    song.setText("Audio link starting. Pandora will open next.");
                    openPandora();
                } catch (Exception e) {
                    captureStatus = "Could not start audio capture";
                    song.setText("Android could not start audio capture. Opening Pandora in status-only mode.");
                    openPandora();
                }
            } else {
                captureStatus = "Playback capture was declined";
                song.setText("Capture declined. Opening Pandora in status-only mode; animations may not follow the beat.");
                openPandora();
            }
            return;
        }
        if (r != PICK || c != RESULT_OK || x == null) return;
        ArrayList<Uri> added = new ArrayList<>();
        if (x.getClipData() != null) {
            for (int i = 0; i < x.getClipData().getItemCount(); i++) added.add(x.getClipData().getItemAt(i).getUri());
        } else if (x.getData() != null) {
            added.add(x.getData());
        }
        if (added.isEmpty()) return;
        boolean wasEmpty = queue.isEmpty();
        queue.addAll(added);
        if (wasEmpty) current = 0;
        updateQueueInfo();
        if (mp == null) loadCurrent(true);
    }

    void loadCurrent(boolean autoplay) {
        if (current < 0 || current >= queue.size()) return;
        Uri u = queue.get(current);
        try {
            releasePlayer();
            mp = new MediaPlayer();
            mp.setDataSource(this, u);
            mp.setOnPreparedListener(p -> {
                song.setText("♪ " + name(u));
                play.setText(autoplay ? "PAUSE" : "PLAY");
                attach(p.getAudioSessionId());
                if (autoplay) p.start();
                viz.r.reset();
            });
            mp.setOnCompletionListener(p -> {
                if (repeatOn) loadCurrent(true);
                else next(true);
            });
            mp.setOnErrorListener((p, what, extra) -> {
                song.setText("Could not play track");
                play.setText("PLAY");
                return true;
            });
            song.setText("LOADING • " + name(u));
            play.setText("LOADING");
            mp.prepareAsync();
        } catch (Exception e) {
            song.setText("Could not open track");
            play.setText("PLAY");
        }
    }

    void next(boolean automatic) {
        if (queue.isEmpty()) { pick(); return; }
        if (shuffleOn && queue.size() > 1) {
            int old = current;
            do { current = new Random().nextInt(queue.size()); } while (current == old);
        } else {
            current++;
            if (current >= queue.size()) {
                if (automatic) { current = 0; } else current = 0;
            }
        }
        loadCurrent(true);
        updateQueueInfo();
    }

    void previous() {
        if (queue.isEmpty()) { pick(); return; }
        if (mp != null && mp.getCurrentPosition() > 3000) { mp.seekTo(0); return; }
        current = (current - 1 + queue.size()) % queue.size();
        loadCurrent(true);
        updateQueueInfo();
    }

    void toggle() {
        if (mp == null) { if (!queue.isEmpty()) loadCurrent(true); else pick(); return; }
        if (mp.isPlaying()) { mp.pause(); play.setText("PLAY"); }
        else { mp.start(); play.setText("PAUSE"); }
    }

    void attach(int id) {
        try {
            if (av != null) av.release();
            av = new Visualizer(id);
            av.setCaptureSize(Visualizer.getCaptureSizeRange()[1]);
            av.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                public void onWaveFormDataCapture(Visualizer v, byte[] w, int rate) { viz.r.setWave(w); }
                public void onFftDataCapture(Visualizer v, byte[] f, int rate) { viz.r.setFft(f); }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            av.setEnabled(true);
        } catch (Exception e) {
            viz.r.setAudioAvailable(false);
        }
    }

    void releasePlayer() {
        if (av != null) { try { av.release(); } catch (Exception e) {} av = null; }
        if (mp != null) { try { mp.release(); } catch (Exception e) {} mp = null; }
    }

    void updateQueueInfo() {
        queueInfo.setText(queue.isEmpty() ? "Queue: empty" : "Queue: " + (current + 1) + " / " + queue.size());
    }

    @Override protected void onDestroy() {
        h.removeCallbacks(clock);
        releasePlayer();
        super.onDestroy();
    }

    TextView t(String s, int z, int c) { TextView v = new TextView(this); v.setText(s); v.setTextSize(z); v.setTextColor(c); return v; }
    Button btn(String s) { Button b = new Button(this); b.setText(s); b.setTextSize(9); return b; }
    LinearLayout.LayoutParams lp() { return new LinearLayout.LayoutParams(0, d(44), 1); }
    int d(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    String fmt(int m) { return String.format(Locale.US, "%d:%02d", Math.max(0,m)/60000, (Math.max(0,m)/1000)%60); }

    String name(Uri u) {
        Cursor c = null;
        try {
            c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception e) {}
        finally { if (c != null) c.close(); }
        String s = u.getLastPathSegment();
        return s == null ? "Track" : s;
    }

    static class VizView extends android.opengl.GLSurfaceView {
        final RenderEngine r;
        VizView(Context c) {
            super(c);
            setEGLContextClientVersion(2);
            r = new RenderEngine();
            setRenderer(r);
            setRenderMode(RENDERMODE_CONTINUOUSLY);
        }
    }

    static class RenderEngine implements android.opengl.GLSurfaceView.Renderer {
        float bass, mid, high, energy; long start; int prog, pos, col, preset = 0;
        boolean externalPlayback = false;
        boolean audioAvailable = true;
        final Random random = new Random(7);

        void setPreset(int p) { preset = p; }
        void setExternalPlayback(boolean playing) { externalPlayback = playing; }
        void setAudioAvailable(boolean ok) { audioAvailable = ok; }
        void reset() { bass = mid = high = energy = 0; }

        void setFft(byte[] b) {
            if (b == null) return;
            float[] a = new float[64];
            for (int i = 0; i < 64; i++) {
                int k = Math.min(b.length - 2, 2 + i * 2);
                float re = b[k], im = b[k + 1];
                a[i] = Math.min(1, (float)Math.hypot(re, im) / 110);
            }
            bass = sm(a,0,8); mid = sm(a,8,28); high = sm(a,28,64);
            energy = Math.min(1, bass*1.8f + mid*.7f + high*.35f);
        }
        void setWave(byte[] w) {
            if (w == null || w.length == 0) return;
            float s = 0; for (byte x : w) s += Math.abs(x);
            energy = Math.max(energy, Math.min(1, s / w.length / 60));
        }
        float sm(float[] a, int x, int y) { float s=0; for(int i=x;i<y;i++) s+=a[i]; return s/(y-x); }

        public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl, javax.microedition.khronos.egl.EGLConfig c) {
            start = System.nanoTime();
            String vs = "attribute vec3 p;attribute vec3 c;varying vec3 v;void main(){float z=max(.10,1.-p.z*.52);gl_Position=vec4(p.x/z,p.y/z,0.,1.);gl_PointSize=2.2+7.*(1.-p.z);v=c;}";
            String fs = "precision mediump float;varying vec3 v;void main(){float d=length(gl_PointCoord-.5);float a=1.-smoothstep(.05,.5,d);gl_FragColor=vec4(v,a);}";
            int v=sh(35633,vs), f=sh(35632,fs);
            prog=android.opengl.GLES20.glCreateProgram();
            android.opengl.GLES20.glAttachShader(prog,v); android.opengl.GLES20.glAttachShader(prog,f);
            android.opengl.GLES20.glLinkProgram(prog);
            pos=android.opengl.GLES20.glGetAttribLocation(prog,"p");
            col=android.opengl.GLES20.glGetAttribLocation(prog,"c");
        }
        int sh(int type,String s){int x=android.opengl.GLES20.glCreateShader(type);android.opengl.GLES20.glShaderSource(x,s);android.opengl.GLES20.glCompileShader(x);return x;}
        public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 g,int w,int h){android.opengl.GLES20.glViewport(0,0,w,h);}
        public void onDrawFrame(javax.microedition.khronos.opengles.GL10 g){
            float t=(System.nanoTime()-start)/1e9f;
            if (captureActive && captureHasAudio) {
                bass = captureBass; mid = captureMid; high = captureHigh;
            } else if (externalPlayback || captureActive) {
                bass = .16f + .24f * ((float)Math.sin(t * 4.4f) + 1f) * .5f;
                mid = .12f + .20f * ((float)Math.sin(t * 2.7f + 1.2f) + 1f) * .5f;
                high = .10f + .28f * ((float)Math.sin(t * 7.1f + .6f) + 1f) * .5f;
            }
            float beat=Math.min(1,bass*2.8f);
            android.opengl.GLES20.glClearColor(.003f+.028f*high,.002f+.02f*mid,.012f+.055f*bass,1);
            android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT);
            android.opengl.GLES20.glUseProgram(prog);
            android.opengl.GLES20.glEnable(android.opengl.GLES20.GL_BLEND);
            android.opengl.GLES20.glBlendFunc(android.opengl.GLES20.GL_SRC_ALPHA,android.opengl.GLES20.GL_ONE);
            if (preset == 0) tunnel(t,beat);
            else if (preset == 1) galaxy(t,beat);
            else if (preset == 2) kaleido(t,beat);
            else bars(t,beat);
        }

        void tunnel(float t,float beat){
            int n=240; float[] a=new float[n*6];
            for(int i=0;i<n;i++){float z=(i%40)/40f, ang=i*2.399f+t*(.18f+high*.6f), rad=.13f+z*.9f+beat*.1f;
                put(a,i,(float)Math.cos(ang)*rad,(float)Math.sin(ang)*rad*.7f,z,.2f+.7f*(1-z),.2f+.5f*high,1);}
            draw(a,android.opengl.GLES20.GL_POINTS,n);
            rings(t,beat,7);
            equalizer(t);
            orb(t,beat);
        }

        void galaxy(float t,float beat){
            int n=420; float[] a=new float[n*6];
            for(int i=0;i<n;i++){float q=i/(float)n, ang=i*.63f+t*(.12f+q*.5f), r=.05f+q*.95f+beat*.1f;
                float twist=ang+q*4.5f; put(a,i,(float)Math.cos(twist)*r,(float)Math.sin(twist)*r*.55f,(float)Math.sin(q*12+t)*.18f,.3f+.6f*q,.2f+.7f*high,1);}
            draw(a,android.opengl.GLES20.GL_POINTS,n);
            rings(t,beat,4); orb(t,beat);
        }

        void kaleido(float t,float beat){
            for(int k=0;k<12;k++){int n=64;float[] a=new float[n*6];float base=(float)(k*Math.PI/6+t*.25);
                for(int i=0;i<n;i++){float q=i/(float)n, r=.08f+q*.95f*(1+beat*.25f), ang=base+q*2.8f+t*.4f;
                    put(a,i,(float)Math.cos(ang)*r,(float)Math.sin(ang)*r*.72f,.2f+q*.5f,.3f+.6f*q,.15f+.75f*high,1);}
                draw(a,android.opengl.GLES20.GL_LINE_STRIP,n);
            }
            equalizer(t); orb(t,beat);
        }

        void bars(float t,float beat){
            int n=56;float[] a=new float[n*12];
            for(int i=0;i<n;i++){float x=-.96f+i*.035f;float band=i<19?bass:(i<39?mid:high);float hh=.035f+Math.min(1,band*2.2f)*(0.15f+.65f*((float)Math.sin(t*2+i*.35f)+1)/2);
                int q=i*12; a[q]=x;a[q+1]=-.9f;a[q+2]=0;a[q+3]=x;a[q+4]=-.9f+hh;a[q+5]=0;a[q+6]=.15f+.7f*high;a[q+7]=.2f+.7f*bass;a[q+8]=1;a[q+9]=a[q+6];a[q+10]=a[q+7];a[q+11]=1;}
            draw(a,android.opengl.GLES20.GL_LINES,n*2); rings(t,beat,5); orb(t,beat);
        }

        void rings(float t,float beat,int count){
            for(int k=0;k<count;k++){int n=72;float[] a=new float[n*6];float rad=.12f+k*.14f+beat*.045f;
                for(int i=0;i<n;i++){float ang=i*(float)Math.PI*2/n+t*(.2f+k*.04f);put(a,i,(float)Math.cos(ang)*rad,(float)Math.sin(ang)*rad*.7f,.12f+k*.11f,.25f+.1f*k,.2f+.1f*high,1);}
                draw(a,android.opengl.GLES20.GL_LINE_LOOP,n);
            }
        }

        void equalizer(float t){
            int n=36;float[] a=new float[n*12];
            for(int i=0;i<n;i++){float x=-.92f+i*.053f;float hh=.04f+Math.min(1,(i<12?bass:(i<25?mid:high))*2)*(0.12f+.5f*((float)Math.sin(t*2+i)+1)/2);int q=i*12;
                a[q]=x;a[q+1]=-.88f;a[q+2]=0;a[q+3]=x;a[q+4]=-.88f+hh;a[q+5]=0;a[q+6]=.3f+.5f*high;a[q+7]=.2f+.6f*bass;a[q+8]=1;a[q+9]=a[q+6];a[q+10]=a[q+7];a[q+11]=1;}
            draw(a,android.opengl.GLES20.GL_LINES,n*2);
        }

        void orb(float t,float beat){
            int n=96;float[] a=new float[n*6];float rr=.13f+.09f*beat;
            for(int i=0;i<n;i++){float ang=i*(float)Math.PI*2/n,wob=1+.18f*(float)Math.sin(i*3+t*3);put(a,i,(float)Math.cos(ang)*rr*wob,(float)Math.sin(ang)*rr*wob,.58f,.85f,.25f+.7f*high,1);}
            draw(a,android.opengl.GLES20.GL_LINE_LOOP,n);
        }

        void put(float[] a,int i,float x,float y,float z,float r,float g,float b){int q=i*6;a[q]=x;a[q+1]=y;a[q+2]=z;a[q+3]=r;a[q+4]=g;a[q+5]=b;}

        void draw(float[] x,int mode,int count){
            java.nio.FloatBuffer b=java.nio.ByteBuffer.allocateDirect(x.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            b.put(x).position(0);
            android.opengl.GLES20.glEnableVertexAttribArray(pos);
            android.opengl.GLES20.glVertexAttribPointer(pos,3,android.opengl.GLES20.GL_FLOAT,false,24,b);
            b.position(3);
            android.opengl.GLES20.glEnableVertexAttribArray(col);
            android.opengl.GLES20.glVertexAttribPointer(col,3,android.opengl.GLES20.GL_FLOAT,false,24,b);
            android.opengl.GLES20.glDrawArrays(mode,0,count);
            android.opengl.GLES20.glDisableVertexAttribArray(pos);
            android.opengl.GLES20.glDisableVertexAttribArray(col);
        }
    }
}
