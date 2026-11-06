package com.echo.remote;
import cc.echomusic.android.R;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.media.*;
import android.widget.Toast;
import org.json.*;
import java.util.concurrent.*;

public class KtvService extends Service {
    static final KtvPlaybackClock CLOCK=new KtvPlaybackClock();
    static volatile boolean running,starting,microphone,voiceMonitor,monitor=true,monitorAudible;
    static volatile float monitorLevel=0.65f;
    static volatile String status="尚未开启 KTV";
    private static volatile KtvService instance;
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;private boolean focusPaused;
    private RemoteApi api;private KtvAudioClient audio;
    private AudioFocusRequest focus;private PowerManager.WakeLock wakeLock;private android.net.wifi.WifiManager.WifiLock wifiLock;
    private void micFailed(String message){if(destroyed)return;microphone=false;voiceMonitor=false;status=message.replace("Broken pipe","人声上传连接已断开");getSharedPreferences("ktv",0).edit().putString("lastStatus",status).apply();getSystemService(NotificationManager.class).notify(3103,notification());Toast.makeText(this,status,Toast.LENGTH_LONG).show();if(api!=null&&message.contains("Broken pipe")){String initial=status;control.execute(()->{try{String reason=api.get("/api/ktv/state").optString("micError");if(!reason.isEmpty())main.post(()->{if(destroyed||microphone||!status.equals(initial))return;status=initial+"；"+reason;getSharedPreferences("ktv",0).edit().putString("lastStatus",status).apply();Toast.makeText(this,status,Toast.LENGTH_LONG).show();});}catch(Exception ignored){}});}}
    private void sessionFailed(String message){if(destroyed)return;status=message;getSharedPreferences("ktv",0).edit().putString("lastStatus",status).apply();Toast.makeText(this,message,Toast.LENGTH_LONG).show();stopSelf();}
    @android.annotation.SuppressLint("WakelockTimeout") // Held only for the explicit foreground KTV session; released in onDestroy.
    private void holdNetwork(){if(wakeLock==null){wakeLock=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"EchoRemote:Ktv");wakeLock.setReferenceCounted(false);wakeLock.acquire();}if(wifiLock==null){android.net.wifi.WifiManager wifi=(android.net.wifi.WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);if(wifi!=null){wifiLock=wifi.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"EchoRemote:Ktv");wifiLock.setReferenceCounted(false);wifiLock.acquire();}}}
    private void focusChanged(int change){if(destroyed)return;if(change==AudioManager.AUDIOFOCUS_LOSS){sessionFailed("手机音频被其他应用占用，KTV 已停止");}else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){focusPaused=true;if(audio!=null){audio.microphone(false);audio.setMonitor(0);}status="手机音频暂被占用，电脑伴奏继续";}else if(change==AudioManager.AUDIOFOCUS_GAIN&&focusPaused){focusPaused=false;if(audio!=null){audio.setMonitor(monitor?monitorLevel:0);audio.microphone(microphone);}status="KTV 手机音频已恢复";}}
    static void voiceMonitor(boolean enabled)throws Exception{KtvService service=instance;if(service==null||service.audio==null)throw new java.io.IOException("请先开启 KTV");service.audio.setVoiceMonitor(enabled);voiceMonitor=enabled;}
    static AudioManager audioManager(){KtvService service=instance;return service==null?null:service.getSystemService(AudioManager.class);}
    static boolean active(){return running||starting;}
    static void monitor(boolean enabled,float level){monitor=enabled;monitorLevel=level;KtvService service=instance;if(service!=null&&service.audio!=null)service.audio.setMonitor(enabled?level:0);}
    @Override public void onCreate(){super.onCreate();instance=this;getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("ktv","KTV 监听与麦克风",NotificationManager.IMPORTANCE_LOW));}
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null)return START_NOT_STICKY;
        if("stop".equals(intent.getAction())){stopSelf();return START_NOT_STICKY;}
        boolean mic=intent.getBooleanExtra("microphone",false);
        if(mic&&checkSelfPermission("android.permission.RECORD_AUDIO")!=android.content.pm.PackageManager.PERMISSION_GRANTED){status="未获得麦克风权限";mic=false;}
        microphone=mic;
        try{
            if(Build.VERSION.SDK_INT>=29){int types=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;if(Build.VERSION.SDK_INT>=30&&mic)types|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;startForeground(3103,notification(),types);}else startForeground(3103,notification());
        }catch(Exception error){status="KTV 前台服务启动失败："+error.getMessage();stopSelf();return START_NOT_STICKY;}
        if(active()) {if(audio!=null&&!focusPaused)audio.microphone(mic);return START_NOT_STICKY;}
        try{api=new RemoteApi(intent.getStringExtra("address"));}catch(Exception error){status="电脑地址无效";stopSelf();return START_NOT_STICKY;}
        try{holdNetwork();}catch(Exception error){sessionFailed("KTV 后台音频初始化失败："+error.getMessage());return START_NOT_STICKY;}CLOCK.clear();starting=true;status="正在建立 KTV 音频通道…";
        control.execute(()->{
            try{
                JSONObject session=api.request("/api/ktv/start",new JSONObject());
                long deadline=SystemClock.elapsedRealtime()+90000;
                while(session.optBoolean("preparing")&&!destroyed){status="正在准备 KTV："+session.optString("phase","加载伴奏");if(SystemClock.elapsedRealtime()>deadline)throw new java.io.IOException("KTV 准备超时，可停止后重试");Thread.sleep(500);session=api.get("/api/ktv/state");}
                if(destroyed)return;
                if(!session.optBoolean("active"))throw new java.io.IOException(session.optString("error","KTV 准备失败"));
                KtvAudioClient client=new KtvAudioClient(api.base,session,error->main.post(()->sessionFailed(error)),error->main.post(()->micFailed(error)));
                main.post(()->{
                    if(destroyed){client.close();return;}
                    focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener(this::focusChanged).build();
                    if(getSystemService(AudioManager.class).requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){client.close();status="未获得手机音频焦点";stopSelf();return;}
                    audio=client;running=true;starting=false;getSharedPreferences("ktv",0).edit().putBoolean("sessionExpected",true).apply();status="KTV 已连接 · 手机监听与电脑伴奏独立";
                    client.setMonitor(monitor?monitorLevel:0);client.start();if(microphone)client.microphone(true);getSystemService(NotificationManager.class).notify(3103,notification());
                });
            }catch(Exception error){main.post(()->{status="KTV 开启失败："+error.getMessage();stopSelf();});}
        });return START_NOT_STICKY;
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,30,new Intent(this,KtvActivity.class).putExtra("address",api==null?"":api.base),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop=PendingIntent.getService(this,31,new Intent(this,KtvService.class).setAction("stop"),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,"ktv").setSmallIcon(cc.echomusic.android.R.drawable.er_ic_remote).setContentTitle(microphone?"KTV · 麦克风正在传到电脑":"KTV · 正在监听电脑伴奏")
            .setContentText("点击返回 KTV；可随时停止并恢复电脑播放").setContentIntent(open).setOngoing(true).addAction(new Notification.Action.Builder(null,"停止 KTV",stop).build()).build();
    }
    @Override public void onDestroy(){
        getSharedPreferences("ktv",0).edit().putBoolean("sessionExpected",false).putString("lastStatus",status).apply();destroyed=true;running=false;starting=false;microphone=false;voiceMonitor=false;instance=null;CLOCK.clear();if(audio!=null)audio.close();if(focus!=null)getSystemService(AudioManager.class).abandonAudioFocusRequest(focus);
        if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();
        if(api!=null){RemoteApi target=api;control.execute(()->{try{target.request("/api/ktv/stop",new JSONObject());}catch(Exception ignored){}});}control.shutdown();main.removeCallbacksAndMessages(null);super.onDestroy();
    }
}
