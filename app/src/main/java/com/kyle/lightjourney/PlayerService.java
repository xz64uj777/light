package com.kyle.lightjourney;

import android.app.*;
import android.content.*;
import android.os.*;
import android.media.*;
import android.media.session.*;
import android.view.*;
import android.widget.*;
import android.graphics.Color;

public class PlayerService extends Service {
    static final String PLAY="com.kyle.light.PLAY", PAUSE="com.kyle.light.PAUSE", NEXT="com.kyle.light.NEXT", PREV="com.kyle.light.PREV", STOP="com.kyle.light.STOP";
    MediaSession session;
    MediaPlayer player;

    @Override public void onCreate() {
        super.onCreate();
        session = new MediaSession(this, "MusicLight");
        session.setCallback(new MediaSession.Callback() {
            public void onPlay() { if(player!=null){player.start(); update();} }
            public void onPause() { if(player!=null){player.pause(); update();} }
            public void onStop() { stopSelf(); }
        });
        session.setActive(true);
    }

    @Override public int onStartCommand(Intent i,int flags,int id) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch=new NotificationChannel("music","Music Light",NotificationManager.IMPORTANCE_LOW);
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        }
        Intent launch=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,launch,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"music"):new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Music Light").setContentText("Music playback").setContentIntent(pi).setOngoing(true);
        startForeground(7,b.build());
        return START_STICKY;
    }
    void update(){ if(session!=null) session.setPlaybackState(new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE).setState(player!=null&&player.isPlaying()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,player==null?0:player.getCurrentPosition(),1).build()); }
    @Override public IBinder onBind(Intent i){return null;}
    @Override public void onDestroy(){if(session!=null){session.setActive(false);session.release();}if(player!=null)player.release();super.onDestroy();}
}