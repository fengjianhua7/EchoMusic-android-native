package com.echo.remote;
import cc.echomusic.android.R;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

/** Standalone KTV page; leaving this page keeps the explicit foreground session alive. */
public class KtvActivity extends Activity {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private RemoteApi api;private final LiveVolumeQueue liveVolume=new LiveVolumeQueue(value->api.command(RemoteApi.body("volume","volume",value)),this::notifyError);
    private TextView status,title,delayLabel,micGainLabel;private SeekBar micGain;private boolean gainDragging;
    private final LiveVolumeQueue liveMicGain=new LiveVolumeQueue(400,value->api.request("/api/ktv/config",new JSONObject().put("micGain",value/100.0)),this::notifyError);
    private Button start,micButton,voiceButton;
    private Switch listen,microphone,pcMute;
    private SeekBar delay,pcVolume;private TextView pcVolumeLabel;private boolean volumeDragging;
    private MusicUi.Icon play;
    private LyricPage lyrics;
    private boolean resumed,polling,rendering,keepAwake;
    private int lyricCounter;
    private JSONObject lastLyrics=new JSONObject();
    private String lyricTrack="",fetchedLyricTrack="";private int pcDelayMs;
    private final Runnable lyricClock=new Runnable(){public void run(){if(!resumed)return;Double time=KtvService.CLOCK.seconds(lyricTrack,SystemClock.elapsedRealtime());if(time!=null)lyrics.updateTime(Math.max(0,time-(KtvService.monitorAudible?0:pcDelayMs/1000.0))+lastLyrics.optDouble("timeOffset")/1000);ui.postDelayed(this,80);}};
    private int dp(float n){return MusicUi.dp(this,n);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);if(!KtvService.active()){android.content.SharedPreferences savedStatus=getSharedPreferences("ktv",0);if(savedStatus.getBoolean("sessionExpected",false)){KtvService.status="上次 KTV 服务被系统终止，请重新开启";savedStatus.edit().putBoolean("sessionExpected",false).putString("lastStatus",KtvService.status).apply();}else if("尚未开启 KTV".equals(KtvService.status))KtvService.status=savedStatus.getString("lastStatus",KtvService.status);}getWindow().setStatusBarColor(MusicUi.BG);getWindow().setNavigationBarColor(MusicUi.BG);
        try{String address=getIntent().getStringExtra("address");if(address==null||address.isEmpty())address=getSharedPreferences("ktv",0).getString("address","");api=new RemoteApi(address);getSharedPreferences("ktv",0).edit().putString("address",api.base).apply();}catch(Exception error){Toast.makeText(this,"请先在遥控主页连接电脑",Toast.LENGTH_LONG).show();finish();return;}
        LinearLayout shell=new LinearLayout(this);shell.setOrientation(LinearLayout.VERTICAL);shell.setBackgroundColor(MusicUi.BG);setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(0,insets.getSystemWindowInsetTop(),0,insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(12),dp(6),dp(16),dp(6));shell.addView(header);
        MusicUi.Icon back=new MusicUi.Icon(this,"back","返回遥控",this::finish);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
        TextView heading=MusicUi.text(this,"KTV · 远程演唱",22,MusicUi.TEXT);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(false);shell.addView(scroll,new LinearLayout.LayoutParams(-1,dp(180)));scroll.setVisibility(View.GONE);
        LinearLayout body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(20),dp(8),dp(20),dp(20));scroll.addView(body);
        LinearLayout summary=new LinearLayout(this);summary.setOrientation(LinearLayout.VERTICAL);summary.setPadding(dp(20),0,dp(20),dp(8));shell.addView(summary,1);
        status=MusicUi.text(this,KtvService.status,13,MusicUi.BLUE);summary.addView(status);
        title=MusicUi.text(this,"播放电脑伴奏后，开启 KTV",19,MusicUi.TEXT);title.setPadding(0,dp(12),0,dp(12));title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);summary.addView(title);
        start=MusicUi.pill(this,"开启 KTV",()->{if(KtvService.active())stopService(new Intent(this,KtvService.class));else startSession();});start.setBackgroundTintList(ColorStateList.valueOf(0xffdcefe7));LinearLayout sessionRow=new LinearLayout(this);summary.addView(sessionRow);sessionRow.addView(start,new LinearLayout.LayoutParams(0,dp(48),1));Button settings=MusicUi.pill(this,"音频设置",()->scroll.setVisibility(scroll.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));sessionRow.addView(settings,new LinearLayout.LayoutParams(dp(100),dp(48)));
        micButton=MusicUi.pill(this,"开启麦克风 · 原声直通",()->{if(KtvService.running)microphone.setChecked(!KtvService.microphone);});micButton.setBackgroundTintList(ColorStateList.valueOf(0xffdcefe7));
        micGainLabel=MusicUi.text(this,"电脑人声音量 200%",14,MusicUi.TEXT);body.addView(micGainLabel);micGain=new SeekBar(this);micGain.setMax(400);micGain.setProgress(200);micGain.setContentDescription("电脑麦克风人声音量");body.addView(micGain,new LinearLayout.LayoutParams(-1,dp(44)));body.addView(MusicUi.text(this,"只放大原声；耳返音量跟随手机媒体音量。",12,MusicUi.MUTED));micGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user){micGainLabel.setText(getString(R.string.er_ktv_mic_gain,value));liveMicGain.submit(value);}}public void onStartTrackingTouch(SeekBar b){gainDragging=true;}public void onStopTrackingTouch(SeekBar b){liveMicGain.submit(b.getProgress());gainDragging=false;}});
        listen=toggle(body,"手机监听伴奏",true);listen.setOnCheckedChangeListener((b,value)->{if(!rendering)KtvService.monitor(value,KtvService.monitorLevel);});
        TextView level=MusicUi.text(this,"手机监听音量",12,MusicUi.MUTED);body.addView(level);SeekBar local=new SeekBar(this);local.setContentDescription("手机监听音量");local.setMax(100);local.setProgress((int)(KtvService.monitorLevel*100));body.addView(local,new LinearLayout.LayoutParams(-1,dp(40)));
        local.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){if(user)KtvService.monitor(listen.isChecked(),n/100f);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        microphone=toggle(body,"麦克风持续演唱",false);microphone.setOnCheckedChangeListener((b,value)->{if(rendering)return;if(value&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){rendering=true;b.setChecked(false);rendering=false;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},41);return;}if(KtvService.active())startServiceMic(value);});
        pcMute=toggle(body,"仅静音电脑伴奏",false);pcMute.setOnCheckedChangeListener((b,value)->{if(!rendering)configure("pcMuted",value);});
        body.addView(MusicUi.pill(this,"电脑输出设备 ›",this::chooseOutput),new LinearLayout.LayoutParams(-1,dp(48)));
        TextView explanation=MusicUi.text(this,"静音电脑伴奏不会影响手机监听和电脑上的人声。",12,MusicUi.MUTED);body.addView(explanation);
        delayLabel=MusicUi.text(this,"电脑伴奏延迟  0 ms",14,MusicUi.TEXT);delayLabel.setPadding(0,dp(16),0,0);body.addView(delayLabel);
        delay=new SeekBar(this);delay.setContentDescription("电脑伴奏延迟");delay.setMax(1500);body.addView(delay,new LinearLayout.LayoutParams(-1,dp(44)));
        delay.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){if(user)delayLabel.setText(getString(R.string.er_ktv_delay,(n/20)*20));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){configure("delayMs",b.getProgress()/20*20);}});
        TextView note=MusicUi.text(this,"0–1500 ms，仅推迟电脑伴奏。开麦时无耳机会暂停手机外放；电脑扬声器仍可能回采，请戴耳机或远离扬声器。",12,MusicUi.MUTED);body.addView(note);
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER);shell.addView(controls);
        for(String action:new String[]{"prev","toggle","next"}){String label="prev".equals(action)?"上一首":"next".equals(action)?"下一首":"播放/暂停";MusicUi.Icon button=new MusicUi.Icon(this,"toggle".equals(action)?"play":action,label,()->command(action));button.setBackground(new android.graphics.drawable.InsetDrawable(MusicUi.shape(MusicUi.SURFACE,30,this),dp(4)));controls.addView(button,new LinearLayout.LayoutParams(dp(64),dp(56)));if("toggle".equals(action))play=button;}
        lyrics=new LyricPage(this,()->{},()->{},time->io.execute(()->{try{api.command(RemoteApi.body("seek","time",time));}catch(Exception error){notifyError(error);}}));
        keepAwake=getPreferences(0).getBoolean("awake",true);lyrics.embedded();lyrics.setKeepAwakeAction(this::toggleAwake,keepAwake);
        shell.addView(lyrics,shell.indexOfChild(controls),new LinearLayout.LayoutParams(-1,0,1));
        microphone.setVisibility(View.GONE);summary.removeView(sessionRow);shell.removeView(scroll);shell.removeView(controls);LinearLayout dock=new LinearLayout(this);dock.setOrientation(LinearLayout.VERTICAL);dock.setPadding(dp(16),dp(4),dp(16),dp(8));dock.setBackgroundColor(MusicUi.SURFACE);shell.addView(dock);dock.addView(controls);dock.addView(micButton,new LinearLayout.LayoutParams(-1,dp(48)));voiceButton=MusicUi.pill(this,"监听人声 · 耳机耳返",()->{try{KtvService.voiceMonitor(!KtvService.voiceMonitor);refreshControls();}catch(Exception error){notifyError(error);}});dock.addView(voiceButton,new LinearLayout.LayoutParams(-1,dp(44)));pcVolumeLabel=MusicUi.text(this,"电脑伴奏音量",12,MusicUi.MUTED);dock.addView(pcVolumeLabel);pcVolume=new SeekBar(this);pcVolume.setMax(100);pcVolume.setContentDescription("电脑音乐播放器音量");dock.addView(pcVolume,new LinearLayout.LayoutParams(-1,dp(36)));pcVolume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean user){if(user){pcVolumeLabel.setText(getString(R.string.er_ktv_pc_volume,n));liveVolume.submit(n);}}public void onStartTrackingTouch(SeekBar b){volumeDragging=true;}public void onStopTrackingTouch(SeekBar b){liveVolume.submit(b.getProgress());volumeDragging=false;}});dock.addView(sessionRow);dock.addView(scroll);
        refreshControls();
    }
    private Switch toggle(LinearLayout body,String label,boolean checked){Switch value=new Switch(this);value.setText(label);value.setTextColor(MusicUi.TEXT);value.setTextSize(15);value.setChecked(checked);value.setPadding(0,dp(8),0,dp(8));body.addView(value,new LinearLayout.LayoutParams(-1,dp(56)));return value;}
    private void chooseOutput(){io.execute(()->{try{JSONObject audio=api.get("/api/audio");JSONArray devices=audio.optJSONArray("devices");if(devices==null||devices.length()==0)throw new Exception("当前没有可用的电脑输出设备");String[] labels=new String[devices.length()],ids=new String[devices.length()];int selected=-1;for(int i=0;i<devices.length();i++){JSONObject item=devices.getJSONObject(i);labels[i]=item.optString("title",item.optString("name"));ids[i]=item.optString("id");if(ids[i].equals(audio.optString("selected")))selected=i;}final int checked=selected;ui.post(()->{if(isDestroyed())return;new AlertDialog.Builder(this).setTitle("电脑输出设备").setSingleChoiceItems(labels,checked,(dialog,which)->{dialog.dismiss();io.execute(()->{try{api.command(RemoteApi.body("outputDevice","deviceId",ids[which]));ui.post(()->Toast.makeText(this,"已切换到："+labels[which],Toast.LENGTH_SHORT).show());}catch(Exception error){notifyError(error);}});}).setNegativeButton("取消",null).show();});}catch(Exception error){notifyError(error);}});}
    private void startSession(){
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);return;}
        startServiceMic(microphone.isChecked());
    }
    private void startServiceMic(boolean enabled){startForegroundService(new Intent(this,KtvService.class).putExtra("address",api.base).putExtra("microphone",enabled));}
    private void command(String action){io.execute(()->{try{api.command(RemoteApi.body(action));}catch(Exception error){notifyError(error);}});}
    private void configure(String key,Object value){if(!KtvService.running)return;io.execute(()->{try{api.request("/api/ktv/config",new JSONObject().put(key,value));}catch(Exception error){notifyError(error);}});}
    private void notifyError(Exception error){ui.post(()->{if(!isDestroyed())Toast.makeText(this,error.getMessage(),Toast.LENGTH_LONG).show();});}
    private void refreshControls(){if(start==null)return;rendering=true;start.setText(KtvService.active()?"停止 KTV":"开启 KTV");status.setText(KtvService.status);listen.setChecked(KtvService.monitor);micButton.setEnabled(KtvService.running);micButton.setText(KtvService.microphone?"关闭麦克风 · 原声演唱中":"开启麦克风 · 持续演唱");voiceButton.setEnabled(KtvService.running&&KtvService.microphone);voiceButton.setText(KtvService.voiceMonitor?"人声监听已开启 · 点击关闭":"监听人声 · 耳机耳返");voiceButton.setBackgroundTintList(ColorStateList.valueOf(KtvService.voiceMonitor?0xff28614e:0xff28364a));microphone.setChecked(KtvService.microphone);micGain.setEnabled(KtvService.running);pcMute.setEnabled(KtvService.running);delay.setEnabled(KtvService.running);rendering=false;}
    private final Runnable loop=new Runnable(){public void run(){if(!resumed||api==null)return;refreshControls();if(!polling){polling=true;io.execute(()->{
        try{JSONObject playback=api.get("/api/state");JSONObject l=null,s=null;JSONObject nowTrack=playback.optJSONObject("playback");String currentTrack=nowTrack==null?"":nowTrack.optString("trackId");boolean changed=!currentTrack.equals(fetchedLyricTrack);if(changed||lyricCounter++%3==0)try{l=api.get("/api/lyric");fetchedLyricTrack=currentTrack;}catch(Exception ignored){if(changed)l=new JSONObject();}if(KtvService.running)s=api.get("/api/ktv/state");final JSONObject lyric=l,state=s;
            ui.post(()->{if(isDestroyed())return;if(!volumeDragging){int level=playback.optInt("volume",50);pcVolume.setProgress(level);pcVolumeLabel.setText(getString(R.string.er_ktv_pc_volume,level));}JSONObject pb=playback.optJSONObject("playback");if(pb!=null){lyricTrack=pb.optString("trackId");Double synced=KtvService.CLOCK.seconds(lyricTrack,SystemClock.elapsedRealtime());if(synced!=null)try{pb.put("currentTime",Math.max(0,synced-(KtvService.monitorAudible?0:pcDelayMs/1000.0)));}catch(JSONException ignored){}}title.setText(pb==null?"等待电脑播放":pb.optString("title"));boolean playing=pb!=null&&pb.optBoolean("isPlaying");play.glyph(playing?"pause":"play",playing?"暂停":"播放");if(lyric!=null)lastLyrics=lyric;lyrics.update(playback,lastLyrics);if(state!=null){if(!state.optBoolean("active")){KtvService.status=state.optString("error").isEmpty()?"电脑 KTV 会话已结束，请重新开启":state.optString("error");stopService(new Intent(KtvActivity.this,KtvService.class));}else{rendering=true;if(!gainDragging){int gain=(int)Math.round(state.optDouble("micGain",2)*100);micGain.setProgress(gain);micGainLabel.setText(getString(R.string.er_ktv_mic_gain,gain));}pcDelayMs=state.optInt("delayMs");pcMute.setChecked(state.optBoolean("pcMuted"));if(!delay.isPressed()){delay.setProgress(state.optInt("delayMs"));delayLabel.setText(getString(R.string.er_ktv_delay,state.optInt("delayMs")));}rendering=false;}}});
        }catch(Exception ignored){}finally{ui.post(()->polling=false);}
    });}ui.postDelayed(this,1000);}};
    private void toggleAwake(){keepAwake=!keepAwake;getPreferences(0).edit().putBoolean("awake",keepAwake).apply();lyrics.setKeepAwakeAction(this::toggleAwake,keepAwake);if(keepAwake&&resumed)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    @Override protected void onResume(){super.onResume();resumed=true;if(keepAwake)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui.post(loop);ui.post(lyricClock);}
    @Override protected void onPause(){resumed=false;getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);ui.removeCallbacks(loop);ui.removeCallbacks(lyricClock);super.onPause();}
    @Override protected void onDestroy(){liveMicGain.close();liveVolume.close();ui.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){super.onRequestPermissionsResult(code,permissions,results);boolean granted=results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED;if(code==41&&granted){microphone.setChecked(true);}else if(code==42&&granted)startSession();else if(code==43&&granted)Toast.makeText(this,"已授权，请点击开启麦克风",Toast.LENGTH_SHORT).show();else Toast.makeText(this,"未授权，可在系统设置中允许后重试",Toast.LENGTH_LONG).show();}
}














