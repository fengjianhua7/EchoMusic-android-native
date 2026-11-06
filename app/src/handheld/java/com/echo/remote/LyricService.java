package com.echo.remote;
import cc.echomusic.android.R;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.Configuration;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.*;
import org.json.JSONObject;
import java.util.concurrent.*;

public class LyricService extends Service {
    private static volatile boolean running;
    static boolean isRunning(){return running;}
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService poll=Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService commands=Executors.newSingleThreadExecutor();
    private WindowManager windows;
    private FloatingLyricsView overlay;
    private WindowManager.LayoutParams params;
    private SharedPreferences prefs;
    private volatile RemoteApi api;
    private ScheduledFuture<?> task;
    private JSONObject state=new JSONObject(),lyrics=new JSONObject();
    private long receivedAt,lastLyricFetch;
    private int failures;
    private boolean destroyed,online,locked;
    private String trackId="";
    private AlertDialog dialog;
    @Override public void onCreate(){super.onCreate();prefs=getSharedPreferences("desktop_lyrics",MODE_PRIVATE);locked=prefs.getBoolean("locked",false);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null||"stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        if(!Settings.canDrawOverlays(this)){stopSelf();return START_NOT_STICKY;}
        if(intent.getStringExtra("address")!=null)try{api=new RemoteApi(intent.getStringExtra("address"));}catch(Exception e){stopSelf();return START_NOT_STICKY;}
        if(api==null){stopSelf();return START_NOT_STICKY;}
        if("unlock".equals(intent.getAction()))locked=false;
        if("lock".equals(intent.getAction()))locked=true;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("lyrics","桌面歌词",NotificationManager.IMPORTANCE_LOW));
        if(Build.VERSION.SDK_INT>=34)startForeground(3101,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(3101,notification());
        if(overlay==null){
            windows=getSystemService(WindowManager.class);
            params=new WindowManager.LayoutParams(width(),-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.TOP|Gravity.START;params.x=prefs.getInt("x",dp(12));
            params.y=prefs.getInt("y",(int)(getResources().getDisplayMetrics().heightPixels*.72));
            overlay=new FloatingLyricsView(this,(dx,dy,finish)->{
                params.x+=dx;params.y+=dy;clamp();updateWindow();if(finish)savePosition();
            },this::send,this::showSettings,this::stopSelf);
            overlay.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);applyStyle();
            overlay.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{if(b-t!=ob-ot){clamp();updateWindow();}});
            try{clamp();windows.addView(overlay,params);}catch(Exception e){overlay.dispose();overlay=null;stopSelf();return START_NOT_STICKY;}
            ui.post(frame);
        }else applyStyle();
        running=true;
        if(task==null)task=poll.scheduleWithFixedDelay(this::sync,0,1,TimeUnit.SECONDS);
        return START_NOT_STICKY;
    }
    private int dp(float n){return MusicUi.dp(this,n);}
    private int width(){return Math.min(dp(360),getResources().getDisplayMetrics().widthPixels-dp(24));}
    private PendingIntent action(String name,int id){return PendingIntent.getService(this,id,new Intent(this,LyricService.class).setAction(name),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,40,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"lyrics").setSmallIcon(R.drawable.er_ic_status_music).setContentTitle(getString(R.string.er_floating_title))
            .setContentText(getString(locked?R.string.er_floating_locked:R.string.er_floating_unlocked)).setOnlyAlertOnce(true)
            .setContentIntent(open).setOngoing(true)
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,R.drawable.er_ic_lyric_lock),getString(locked?R.string.er_unlock_lyrics:R.string.er_lock_lyrics),action(locked?"unlock":"lock",41)).build())
            .addAction(new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this,R.drawable.er_ic_close),getString(R.string.er_close_lyrics),action("stop",42)).build()).build();
    }
    private void clamp(){
        params.x=Math.max(0,Math.min(getResources().getDisplayMetrics().widthPixels-params.width,params.x));
        int height=overlay==null?dp(90):Math.max(dp(60),overlay.getHeight());
        params.y=Math.max(dp(24),Math.min(getResources().getDisplayMetrics().heightPixels-height-dp(30),params.y));
    }
    private void savePosition(){prefs.edit().putInt("x",params.x).putInt("y",params.y).apply();}
    private void applyStyle(){
        if(overlay==null)return;
        overlay.style(prefs.getInt("size",22),prefs.getInt("color",MusicUi.BLUE),prefs.getBoolean("background",false));
        overlay.setLocked(locked);prefs.edit().putBoolean("locked",locked).apply();
        params.flags=WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|(locked?WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE:0);
        float opacity=prefs.getFloat("opacity",.95f);
        float limit=.8f;if(Build.VERSION.SDK_INT>=31)limit=getSystemService(android.hardware.input.InputManager.class).getMaximumObscuringOpacityForTouch();
        params.alpha=locked?Math.min(opacity,Math.max(0,limit-.02f)):opacity;
        clamp();updateWindow();getSystemService(NotificationManager.class).notify(3101,notification());
    }
    private void updateWindow(){
        if(overlay==null||!overlay.isAttachedToWindow())return;
        try{windows.updateViewLayout(overlay,params);}catch(Exception e){stopSelf();}
    }
    private void showSettings(){
        showDialog(new String[]{"锁定歌词（从通知栏解锁）","字体大小","歌词颜色","透明度","背景："+(prefs.getBoolean("background",false)?"半透明":"透明"),"重置位置"},
            (d,i)->{switch(i){
                case 0:locked=true;applyStyle();break;
                case 1:showDialog(new String[]{"小 · 18","标准 · 22","大 · 26"},(x,n)->{prefs.edit().putInt("size",new int[]{18,22,26}[n]).apply();applyStyle();});break;
                case 2:showDialog(new String[]{"酷蓝","暖金","纯白"},(x,n)->{prefs.edit().putInt("color",new int[]{MusicUi.BLUE,0xffffd36d,0xfff5f7ff}[n]).apply();applyStyle();});break;
                case 3:showDialog(new String[]{"清晰 · 95%","柔和 · 75%","轻透 · 55%"},(x,n)->{prefs.edit().putFloat("opacity",new float[]{.95f,.75f,.55f}[n]).apply();applyStyle();});break;
                case 4:prefs.edit().putBoolean("background",!prefs.getBoolean("background",false)).apply();applyStyle();break;
                case 5:params.x=dp(12);params.y=(int)(getResources().getDisplayMetrics().heightPixels*.72);clamp();savePosition();updateWindow();break;
            }});
    }
    private void showDialog(String[] items,DialogInterface.OnClickListener action){
        if(dialog!=null)dialog.dismiss();
        dialog=new AlertDialog.Builder(this).setTitle("桌面歌词").setItems(items,action).setNegativeButton("取消",null).create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        try{dialog.show();}catch(Exception e){android.widget.Toast.makeText(this,"请返回应用调整权限",android.widget.Toast.LENGTH_SHORT).show();}
    }
    private void send(String action){
        RemoteApi target=api;if(target==null)return;
        commands.execute(()->{try{target.command(RemoteApi.body(action));}catch(Exception e){ui.post(()->{if(!destroyed)android.widget.Toast.makeText(this,"控制失败，请检查电脑连接",android.widget.Toast.LENGTH_SHORT).show();});}});
    }
    private void sync(){
        if(!Settings.canDrawOverlays(this)){ui.post(this::stopSelf);return;}
        RemoteApi target=api;
        try{
            JSONObject s=target.get("/api/state"),pb=s.optJSONObject("playback");String id=pb==null?"":pb.optString("trackId");
            JSONObject l=null;
            if(!id.equals(trackId)||SystemClock.elapsedRealtime()-lastLyricFetch>3000){
                try{l=target.get("/api/lyric");}catch(Exception unsupported){l=new JSONObject();}
                lastLyricFetch=SystemClock.elapsedRealtime();trackId=id;
            }
            final JSONObject lines=l;
            ui.post(()->{if(destroyed||target!=api)return;state=s;if(lines!=null)lyrics=lines;receivedAt=SystemClock.elapsedRealtime();failures=0;online=true;});
        }catch(Exception e){ui.post(()->{if(destroyed||target!=api)return;online=false;failures++;overlay.offline();if(failures>=30)stopSelf();});}
    }
    private final Runnable frame=()->{
        if(destroyed)return;
        if(overlay!=null&&online){
            JSONObject pb=state.optJSONObject("playback");double time=pb==null?0:pb.optDouble("currentTime");
            if(pb!=null&&pb.optBoolean("isPlaying"))time+=(SystemClock.elapsedRealtime()-receivedAt)/1000.0*pb.optDouble("playbackRate",1);
            if(pb!=null&&pb.optDouble("duration")>0)time=Math.min(time,pb.optDouble("duration"));
            overlay.render(state,lyrics,time);
        }ui.postDelayed(this.frameTask(),200);
    };
    private Runnable frameTask(){return frame;}
    @Override public void onConfigurationChanged(Configuration config){super.onConfigurationChanged(config);if(params!=null){params.width=width();clamp();updateWindow();}}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onDestroy(){
        running=false;destroyed=true;if(task!=null)task.cancel(true);poll.shutdownNow();commands.shutdownNow();ui.removeCallbacksAndMessages(null);
        if(dialog!=null)dialog.dismiss();
        if(overlay!=null){overlay.dispose();savePosition();try{windows.removeView(overlay);}catch(Exception ignored){}overlay=null;}
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
