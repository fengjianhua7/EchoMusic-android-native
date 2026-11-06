package com.echo.remote;
import cc.echomusic.android.R;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.*;
import org.json.JSONObject;
import java.util.concurrent.*;

/** A remote media session: audio and volume always belong to the desktop. */
public class RemoteControlService extends Service {
    private static final int ID=3102;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService poll=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private volatile RemoteApi api;
    private MediaSession session;
    private VolumeProvider remoteVolume;
    private ScheduledFuture<?> task;
    private JSONObject state=new JSONObject();
    private Bitmap artwork;
    private boolean destroyed,closing,online;
    private String error="";
    private long errorUntil;
    private volatile String loadedCover="";
    private int failures;
    @Override public void onCreate(){
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
            new NotificationChannel("remote_music","电脑音乐控制",NotificationManager.IMPORTANCE_LOW));
        session=new MediaSession(this,"EchoRemote");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setSessionActivity(openIntent());
        session.setCallback(new MediaSession.Callback(){
            @Override public void onPlay(){send("play");}
            @Override public void onPause(){send("pause");}
            @Override public void onSkipToNext(){send("next");}
            @Override public void onSkipToPrevious(){send("prev");}
            @Override public void onSeekTo(long position){sendBody(RemoteApi.body("seek","time",position/1000.0),false);}
            @Override public void onStop(){closePlayback();}
            @Override public void onCustomAction(String action,Bundle extras){
                if("close".equals(action))closePlayback();
                if("disconnect".equals(action))stopSelf();
            }
        },ui);
        remoteVolume=new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE,100,50){
            @Override public void onSetVolumeTo(int value){sendBody(RemoteApi.body("volume","volume",Math.max(0,Math.min(100,value))),false);}
            @Override public void onAdjustVolume(int direction){onSetVolumeTo(getCurrentVolume()+direction*5);}
        };
        session.setPlaybackToRemote(remoteVolume); session.setActive(true);
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String action=intent.getAction();
        if(action==null){
            try{
                RemoteApi next=new RemoteApi(intent.getStringExtra("address"));
                if(api!=null&&!api.base.equals(next.base)){state=new JSONObject();artwork=null;loadedCover="";}
                api=next;
            }catch(Exception invalid){stopSelf();return START_NOT_STICKY;}
        }
        if(api==null){stopSelf();return START_NOT_STICKY;}
        Notification notification=notification();
        if(Build.VERSION.SDK_INT>=34)startForeground(ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(ID,notification);
        if("close".equals(action))closePlayback();
        else if("disconnect".equals(action))stopSelf();
        else if(action!=null)send(action);
        if(task==null)task=poll.scheduleWithFixedDelay(this::sync,0,1,TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }
    private PendingIntent openIntent(){
        return PendingIntent.getActivity(this,30,new Intent(this,MainActivity.class),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private PendingIntent actionIntent(String action,int code){
        return PendingIntent.getService(this,code,new Intent(this,RemoteControlService.class).setAction(action),
            PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification.Action action(int icon,String label,String action,int code){
        return new Notification.Action.Builder(Icon.createWithResource(this,icon),label,actionIntent(action,code)).build();
    }
    private Notification notification(){
        JSONObject pb=state.optJSONObject("playback");
        boolean playing=pb!=null&&pb.optBoolean("isPlaying");
        String title=pb==null?getString(R.string.er_remote_title):pb.optString("title");
        String detail=error.isEmpty()?(pb==null?getString(R.string.er_remote_waiting):pb.optString("artist")):error;
        Notification.Builder b=new Notification.Builder(this,"remote_music").setSmallIcon(R.drawable.er_ic_status_music)
            .setContentTitle(title).setContentText(detail).setSubText(getString(R.string.er_desktop_output))
            .setContentIntent(openIntent()).setOnlyAlertOnce(true).setOngoing(true).setVisibility(Notification.VISIBILITY_PUBLIC)
            .setCategory(Notification.CATEGORY_TRANSPORT).setColor(MusicUi.BLUE)
            .addAction(action(R.drawable.er_ic_prev,getString(R.string.er_previous),"prev",31))
            .addAction(action(playing?R.drawable.er_ic_pause:R.drawable.er_ic_play,getString(playing?R.string.er_pause:R.string.er_play),"toggle",32))
            .addAction(action(R.drawable.er_ic_next,getString(R.string.er_next),"next",33))
            .addAction(action(R.drawable.er_ic_close,getString(R.string.er_pause_and_close),"close",34))
            .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));
        if(artwork!=null)b.setLargeIcon(artwork);
        if(!online)b.addAction(action(R.drawable.er_ic_close,getString(R.string.er_disconnect),"disconnect",35));
        return b.build();
    }
    private void publish(){
        if(destroyed)return;
        JSONObject pb=state.optJSONObject("playback");
        boolean playing=online&&pb!=null&&pb.optBoolean("isPlaying");
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|
            PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP;
        PlaybackState.Builder playback=new PlaybackState.Builder()
            .setActions(online?actions:0).setState(online?(playing?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED):PlaybackState.STATE_ERROR,
                pb==null?0:(long)(pb.optDouble("currentTime")*1000),playing?(float)pb.optDouble("playbackRate",1):0,SystemClock.elapsedRealtime())
            .addCustomAction("close",getString(R.string.er_pause_and_close),R.drawable.er_ic_close);
        Bundle extras=new Bundle();
        extras.putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_PREVIOUS",true);
        extras.putBoolean("android.media.playback.ALWAYS_RESERVE_SPACE_FOR.ACTION_SKIP_TO_NEXT",true);
        playback.setExtras(extras);
        if(!online)playback.setErrorMessage(error).addCustomAction("disconnect",getString(R.string.er_disconnect),R.drawable.er_ic_close);
        session.setPlaybackState(playback.build());
        MediaMetadata.Builder metadata=new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE,pb==null?getString(R.string.er_remote_title):pb.optString("title"))
            .putString(MediaMetadata.METADATA_KEY_ARTIST,pb==null?getString(R.string.er_desktop_output):pb.optString("artist"))
            .putString(MediaMetadata.METADATA_KEY_ALBUM,pb==null?"":pb.optString("album"))
            .putLong(MediaMetadata.METADATA_KEY_DURATION,pb==null?0:(long)(pb.optDouble("duration")*1000));
        if(artwork!=null)metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,artwork);
        session.setMetadata(metadata.build());remoteVolume.setCurrentVolume(Math.max(0,Math.min(100,state.optInt("volume",50))));
        getSystemService(NotificationManager.class).notify(ID,notification());
    }
    private void sync(){
        RemoteApi target=api;if(target==null)return;
        try{
            JSONObject data=target.get("/api/state"); JSONObject pb=data.optJSONObject("playback");
            String key=pb==null?"":pb.optString("trackId");
            String coverKey=target.base+"|"+key;
            Bitmap bitmap=null;boolean changed=!coverKey.equals(loadedCover);
            if(changed){loadedCover=coverKey;if(!key.isEmpty())try{bitmap=target.cover();}catch(Exception ignored){}}
            final Bitmap image=bitmap;
            ui.post(()->{if(destroyed||api!=target)return;state=data;online=true;failures=0;
                if(changed)artwork=image;if(!closing&&SystemClock.elapsedRealtime()>errorUntil)error="";publish();
            });
        }catch(Exception failed){ui.post(()->{
            if(destroyed||api!=target)return;online=false;failures++;error=getString(R.string.er_notification_disconnected);publish();
            if(failures>=30)stopSelf();
        });}
    }
    private void send(String action){sendBody(RemoteApi.body(action),false);}
    private void closePlayback(){
        if(closing)return;closing=true;error=getString(R.string.er_closing_playback);publish();
        sendBody(RemoteApi.body("pause"),true);
    }
    private void sendBody(JSONObject body,boolean close){
        RemoteApi target=api;if(target==null)return;
        if(closing&&!close)return;
        commands.execute(()->{
            try{
                target.command(body);JSONObject fresh=null;
                if(!close)try{fresh=target.get("/api/state");}catch(Exception ignored){}
                final JSONObject data=fresh;
                ui.post(()->{if(destroyed||api!=target)return;
                    if(close){stopService(new Intent(this,LyricService.class));stopSelf();}
                    else{error="";if(data!=null){state=data;online=true;}publish();}
                });
            }catch(Exception failure){ui.post(()->{
                if(destroyed||api!=target)return;closing=false;error=getString(close?R.string.er_close_failed:R.string.er_control_failed);
                errorUntil=SystemClock.elapsedRealtime()+5000;publish();
                android.widget.Toast.makeText(this,error,android.widget.Toast.LENGTH_LONG).show();
            });}
        });
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        destroyed=true;if(task!=null)task.cancel(true);poll.shutdownNow();commands.shutdownNow();
        ui.removeCallbacksAndMessages(null);session.setActive(false);session.release();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
