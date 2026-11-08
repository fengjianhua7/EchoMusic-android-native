package cc.echomusic.android;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.util.concurrent.*;

/** A single, permission-gated top lyric window owned by the existing media service. */
@android.annotation.TargetApi(23)
final class StatusLyrics {
    private final PlaybackService service;
    private final MusicRepository repo;
    private final SharedPreferences prefs;
    private final WindowManager windows;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private TextView label;
    private Lyrics.Document document;
    private String key="",painted="";
    private long revision=-1;
    private WindowManager.LayoutParams placement;
    private int screenWidth,screenHeight;
    private float downX,downY;private int startX,startY;private boolean dragging;
    private int serial;
    private boolean closed;
    StatusLyrics(PlaybackService service,MusicRepository repo){this.service=service;this.repo=repo;prefs=service.getSharedPreferences("status_lyrics",0);windows=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);if(!BuildConfig.TV)main.post(tick);}
    private int dp(float v){return Math.round(v*service.getResources().getDisplayMetrics().density);}
    private final Runnable tick=new Runnable(){public void run(){if(closed)return;try{update();}catch(RuntimeException ignored){remove();}main.postDelayed(this,350);}};
    private void update(){
        Song song=service.current();
        boolean preview=prefs.getLong("preview_until",0)>System.currentTimeMillis();
        if(!prefs.getBoolean("enabled",false)||!Settings.canDrawOverlays(service)||(!preview&&(song==null||(!service.playing()&&!prefs.getBoolean("paused",false))))){remove();return;}
        if(song!=null&&!song.key().equals(key)){key=song.key();document=null;final String wanted=key;final int ticket=++serial;final int duration=service.duration();io.execute(()->{Lyrics.Document loaded=null;try{String raw=repo.cachedLyrics(song);if(raw.isEmpty()&&!song.local())raw=repo.fetchLyrics(song,null,duration);loaded=Lyrics.document(raw);}catch(Exception ignored){}final Lyrics.Document result=loaded;main.post(()->{if(!closed&&ticket==serial&&wanted.equals(key))document=result;});});}
        if(label==null){label=new TextView(service);label.setSingleLine(true);label.setEllipsize(TextUtils.TruncateAt.END);label.setPadding(dp(8),0,dp(8),0);label.setIncludeFontPadding(false);label.setContentDescription("顶部歌词");label.setOnClickListener(v->{if(prefs.getBoolean("tap",false))service.toggle();});revision=-1;}
        
        String text=song==null?"歌词位置预览":HandheldHome.name(song.title);if(song!=null&&!prefs.getBoolean("title",false)&&document!=null){int line=Lyrics.current(document.lines,service.position()-repo.lyricOffset(song));if(line>=0)text=document.lines.get(line).text;}
        if(!text.equals(painted)||!text.contentEquals(label.getText())){painted=text;label.setText(text);revision=-1;}
        long next=prefs.getLong("revision",0);if(!dragging&&(next!=revision||screenWidth!=service.getResources().getDisplayMetrics().widthPixels||screenHeight!=service.getResources().getDisplayMetrics().heightPixels)){revision=next;applyWindow();}
    }
    private void applyWindow(){
        int width=service.getResources().getDisplayMetrics().widthPixels,height=service.getResources().getDisplayMetrics().heightPixels;
        screenWidth=width;screenHeight=height;
        int top=0; // Screen origin, not the content area below the status bar.
        label.setTextSize(prefs.getInt("size",15));
        int maxWidth=Math.min(width-dp(24),Math.max(dp(40),width*prefs.getInt("width",65)/100));
        int textWidth=(int)Math.ceil(label.getPaint().measureText(label.getText().toString()))+label.getPaddingLeft()+label.getPaddingRight();
        int panel=Math.min(maxWidth,Math.max(dp(32),textWidth));
        if(!prefs.getBoolean("auto_width_v1",false)){int oldWidth=Math.min(width-dp(24),Math.max(dp(120),width*prefs.getInt("width",65)/100));double oldLeft=(width-oldWidth)*prefs.getInt("x",50)/100d;double center=oldLeft+(prefs.getBoolean("center",true)?oldWidth:panel)/2d;int adjusted=(int)Math.round((center-panel/2d)*100/Math.max(1,width-panel));prefs.edit().putInt("x",Math.max(0,Math.min(100,adjusted))).putBoolean("auto_width_v1",true).apply();}
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(panel,dp(Math.max(20,prefs.getInt("size",15)+4)),Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
        if(Build.VERSION.SDK_INT>=30){params.setFitInsetsTypes(0);params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;}else if(Build.VERSION.SDK_INT>=28)params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;params.setTitle("EchoMusic 状态栏歌词");
        if(!prefs.getBoolean("tap",false)&&!prefs.getBoolean("drag",false))params.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=Math.max(0,(width-panel)*prefs.getInt("x",50)/100);params.y=top+Math.max(0,(height-top-params.height)*prefs.getInt("y",0)/100);params.alpha=Math.min(.8f,Math.max(.1f,prefs.getInt("opacity",80)/100f));
        int color=prefs.getInt("color",0xff008a65);float brightness=prefs.getInt("brightness",100)/100f;
        label.setTextColor(Color.rgb(Math.round(Color.red(color)*brightness),Math.round(Color.green(color)*brightness),Math.round(Color.blue(color)*brightness)));label.setTextSize(prefs.getInt("size",15));label.setGravity(Gravity.CENTER_VERTICAL|(prefs.getBoolean("center",true)?Gravity.CENTER_HORIZONTAL:Gravity.LEFT));label.setShadowLayer(dp(1),0,dp(1),0x66ffffff);label.setBackgroundColor(0x08ffffff);placement=params;
        label.setOnTouchListener((v,event)->{if(!prefs.getBoolean("drag",false))return false;switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:downX=event.getRawX();downY=event.getRawY();startX=placement.x;startY=placement.y;dragging=true;return true;case MotionEvent.ACTION_MOVE:placement.x=Math.max(0,Math.min(screenWidth-placement.width,startX+Math.round(event.getRawX()-downX)));placement.y=Math.max(top,Math.min(screenHeight-placement.height,startY+Math.round(event.getRawY()-downY)));windows.updateViewLayout(label,placement);return true;case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:dragging=false;int x=Math.round(placement.x*100f/Math.max(1,screenWidth-placement.width));int y=Math.round((placement.y-top)*100f/Math.max(1,screenHeight-top-placement.height));prefs.edit().putInt("x",x).putInt("y",y).apply();changed(prefs);if(Math.abs(event.getRawX()-downX)<dp(8)&&Math.abs(event.getRawY()-downY)<dp(8))v.performClick();return true;default:return true;}});
        if(label.getParent()==null)windows.addView(label,params);else windows.updateViewLayout(label,params);
    }
    private void remove(){if(label!=null){if(label.getParent()!=null)try{windows.removeView(label);}catch(RuntimeException ignored){}label=null;revision=-1;}}
    void close(){closed=true;++serial;main.removeCallbacksAndMessages(null);remove();io.shutdownNow();}
    private static void changed(SharedPreferences p){p.edit().putLong("revision",System.nanoTime()).apply();}
    static void settings(MainActivity a){
        SharedPreferences p=a.getSharedPreferences("status_lyrics",0);p.edit().putLong("preview_until",System.currentTimeMillis()+300000).apply();changed(p);ScrollView scroll=new ScrollView(a);LinearLayout form=a.col();a.pad(form,20);scroll.addView(form);TextView preview=a.text("调整时暂停也会显示，便于定位",15,p.getInt("color",0xff008a65));preview.setGravity(Gravity.CENTER);form.addView(preview,a.lp(-1,52));
        Switch enabled=new Switch(a);enabled.setText("开启状态栏歌词");enabled.setTextColor(0xff18252b);enabled.setChecked(p.getBoolean("enabled",false));form.addView(enabled,a.lp(-1,52));enabled.setOnCheckedChangeListener((v,on)->{p.edit().putBoolean("enabled",on).apply();changed(p);if(on&&!Settings.canDrawOverlays(a)){a.toast("请允许 EchoMusic 显示在其他应用上层");a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+a.getPackageName())));}});
        toggle(a,form,p,"点击歌词播放 / 暂停","tap",false);toggle(a,form,p,"允许直接拖动歌词","drag",false);toggle(a,form,p,"暂停时仍显示","paused",false);toggle(a,form,p,"显示歌名（关闭则显示歌词）","title",false);toggle(a,form,p,"居中对齐","center",true);
        slider(a,form,p,"左右位置","x",0,100,50,null);slider(a,form,p,"上下位置","y",0,100,0,null);slider(a,form,p,"最大宽度（短歌词自动收缩）","width",10,95,65,null);slider(a,form,p,"亮度","brightness",30,100,100,null);slider(a,form,p,"不透明度","opacity",10,80,80,null);slider(a,form,p,"字号","size",12,28,15,()->preview.setTextSize(p.getInt("size",15)));
        form.addView(a.text("颜色",16,0xff18252b));LinearLayout colors=a.row();int[] palette={0xff008a65,0xffe65379,0xff267db6,0xff7552b7,0xffad761f,0xff18252b,0xffffffff};for(int color:palette){TextView chip=a.text("●",28,color);chip.setGravity(Gravity.CENTER);chip.setContentDescription("歌词颜色 "+Integer.toHexString(color));chip.setOnClickListener(v->{p.edit().putInt("color",color).apply();changed(p);preview.setTextColor(color);});colors.addView(chip,new LinearLayout.LayoutParams(0,a.dp(48),1));}form.addView(colors);a.gap(form,12);form.addView(a.text("短歌词仅占文字实际宽度；左右位置可沿屏幕调整。上下位置设为 0 可移动到状态栏区域。可调节字号、宽度和左右位置避开时间、电量及摄像头；首次使用需允许悬浮窗。",13,0xff718087));
        AlertDialog dialog=new AlertDialog.Builder(a).setTitle("状态栏歌词").setView(scroll).setNeutralButton("重置位置",null).setPositiveButton("完成",null).create();dialog.setOnDismissListener(d->{p.edit().putLong("preview_until",0).apply();changed(p);});dialog.setOnShowListener(d->dialog.getButton(-3).setOnClickListener(v->{p.edit().putInt("x",50).putInt("y",0).putInt("width",65).apply();changed(p);dialog.dismiss();settings(a);}));dialog.show();
    }
    private static void toggle(MainActivity a,LinearLayout form,SharedPreferences p,String title,String key,boolean def){Switch view=new Switch(a);view.setText(title);view.setTextColor(0xff18252b);view.setChecked(p.getBoolean(key,def));form.addView(view,a.lp(-1,48));view.setOnCheckedChangeListener((v,on)->{p.edit().putBoolean(key,on).apply();changed(p);});}
    private static void slider(MainActivity a,LinearLayout form,SharedPreferences p,String title,String key,int min,int max,int def,Runnable update){TextView text=a.text(title+" · "+p.getInt(key,def),14,0xff718087);form.addView(text);SeekBar bar=new SeekBar(a);bar.setMax(max-min);bar.setProgress(p.getInt(key,def)-min);form.addView(bar,a.lp(-1,38));SharedPreferences.OnSharedPreferenceChangeListener sync=(store,changedKey)->{if(key.equals(changedKey)){int value=Math.max(min,Math.min(max,store.getInt(key,def)));text.setText(title+" · "+value);if(bar.getProgress()!=value-min)bar.setProgress(value-min);}};bar.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View v){p.registerOnSharedPreferenceChangeListener(sync);sync.onSharedPreferenceChanged(p,key);}public void onViewDetachedFromWindow(View v){p.unregisterOnSharedPreferenceChangeListener(sync);}});bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(user){p.edit().putInt(key,value+min).apply();changed(p);text.setText(title+" · "+(value+min));if(update!=null)update.run();}}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});}
}
