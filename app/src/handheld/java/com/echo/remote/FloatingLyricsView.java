package com.echo.remote;
import cc.echomusic.android.R;

import android.content.*;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.function.Consumer;

final class FloatingLyricsView extends LinearLayout {
    interface Drag { void move(int dx,int dy,boolean finish); }
    private final FloatingText current,next;
    private final LinearLayout tools;
    private final MusicUi.Icon toggle;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Runnable hide=()->toolsVisible(false);
    private boolean locked;
    public FloatingLyricsView(Context context){this(context,(x,y,finish)->{},s->{},()->{},()->{});}
    FloatingLyricsView(Context context,Drag drag,Consumer<String> command,Runnable settings,Runnable close) {
        super(context);setOrientation(VERTICAL);setPadding(dp(12),dp(8),dp(12),dp(8));setGravity(Gravity.CENTER_HORIZONTAL);
        current=new FloatingText(context);current.setTextSize(22);current.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        current.setTextColor(MusicUi.BLUE);current.setGravity(Gravity.CENTER);current.setMaxLines(1);
        current.setEllipsize(TextUtils.TruncateAt.END);current.setText("正在同步歌词");
        current.setAutoSizeTextTypeUniformWithConfiguration(14,22,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        next=new FloatingText(context);next.setTextSize(14);next.setTextColor(0xffe1e7f4);next.setGravity(Gravity.CENTER);next.setMaxLines(1);
        next.setEllipsize(TextUtils.TruncateAt.END);next.setText("轻触展开操作 · 拖动调整位置");
        current.setShadowLayer(dp(2),0,dp(1),0xee000000);next.setShadowLayer(dp(2),0,dp(1),0xee000000);
        addView(current,new LayoutParams(-1,-2));addView(next,new LayoutParams(-1,-2));
        tools=new LinearLayout(context);tools.setGravity(Gravity.CENTER);tools.setBackground(MusicUi.shape(0xe620293b,18,context));
        LayoutParams p=new LayoutParams(-1,dp(48));p.topMargin=dp(8);addView(tools,p);
        addTool("prev","上一首",()->command.accept("prev"));
        toggle=addTool("play","播放",()->command.accept("toggle"));
        addTool("next","下一首",()->command.accept("next"));
        addTool("more","歌词样式与锁定",settings);addTool("close","关闭桌面歌词",close);
        tools.setVisibility(GONE);
        for(FloatingText text:new FloatingText[]{current,next}){
            text.setOnClickListener(v->showTools());
            text.setOnTouchListener(new OnTouchListener(){
                float x,y;boolean moved;
                public boolean onTouch(View v,MotionEvent e){
                    if(locked)return false;
                    switch(e.getAction()){
                        case MotionEvent.ACTION_DOWN:x=e.getRawX();y=e.getRawY();moved=false;ui.removeCallbacks(hide);return true;
                        case MotionEvent.ACTION_MOVE:
                            int dx=(int)(e.getRawX()-x),dy=(int)(e.getRawY()-y);
                            if(Math.hypot(dx,dy)>ViewConfiguration.get(context).getScaledTouchSlop())moved=true;
                            if(moved){drag.move(dx,dy,false);x=e.getRawX();y=e.getRawY();}return true;
                        case MotionEvent.ACTION_UP:
                            if(moved)drag.move(0,0,true);else v.performClick();ui.postDelayed(hide,3500);return true;
                        case MotionEvent.ACTION_CANCEL:if(moved)drag.move(0,0,true);ui.postDelayed(hide,3500);return true;
                        default:return false;
                    }
                }
            });
        }
    }
    private int dp(float n){return MusicUi.dp(getContext(),n);}
    private MusicUi.Icon addTool(String glyph,String description,Runnable action){
        MusicUi.Icon icon=new MusicUi.Icon(getContext(),glyph,description,()->{action.run();ui.removeCallbacks(hide);ui.postDelayed(hide,3500);});
        tools.addView(icon,new LayoutParams(0,dp(48),1));return icon;
    }
    void showTools(){if(!locked){toolsVisible(true);ui.removeCallbacks(hide);ui.postDelayed(hide,3500);}}
    private void toolsVisible(boolean visible){tools.setVisibility(visible&&!locked?VISIBLE:GONE);}
    void setLocked(boolean value){locked=value;if(value)toolsVisible(false);}
    void style(float size,int color,boolean background){
        current.setAutoSizeTextTypeUniformWithConfiguration(14,(int)size,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        current.setTextColor(color);next.setTextSize(Math.max(12,size-7));
        setBackground(MusicUi.shape(background?0x99131b29:0x00131b29,18,getContext()));
    }
    void render(JSONObject state,JSONObject lyric,double time){
        JSONObject pb=state.optJSONObject("playback");JSONArray lines=lyric.optJSONArray("lines");
        String line=pb==null?"暂无播放":pb.optString("title"),second=pb==null?"连接电脑后显示歌词":pb.optString("artist");
        int index=-1;time+=lyric.optDouble("timeOffset",0)/1000;
        if(lines!=null)for(int i=0;i<lines.length();i++){JSONObject l=lines.optJSONObject(i);if(l!=null&&l.optDouble("time")<=time)index=i;}
        if(index>=0){
            JSONObject l=lines.optJSONObject(index);line=l.optString("text");second=l.optString("translated");
            if(second.isEmpty()&&index+1<lines.length())second=lines.optJSONObject(index+1).optString("text");
        }
        if(!line.contentEquals(current.getText()))current.setText(line);
        if(!second.contentEquals(next.getText()))next.setText(second);
        boolean playing=pb!=null&&pb.optBoolean("isPlaying");toggle.glyph(playing?"pause":"play",playing?"暂停":"播放");
    }
    void offline(){current.setText("电脑已断开");next.setText("正在重连… · 从通知栏可关闭歌词");}
    void dispose(){ui.removeCallbacksAndMessages(null);}
    private static final class FloatingText extends TextView {
        public FloatingText(Context context){super(context);}
        @Override public boolean performClick(){return super.performClick();}
    }
}
