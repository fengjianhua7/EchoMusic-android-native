package com.echo.remote;
import cc.echomusic.android.R;

import android.content.Context;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.function.DoubleConsumer;

/** Full lyric page with timed highlighting, manual browsing and line seeking. */
final class LyricPage extends LinearLayout {
    private final LyricScroll scroll;
    private final LinearLayout rows;
    private final TextView heading,hint;
    private final MusicUi.Icon desktop;
    private final Button awake;
    private final List<TextView> primary=new ArrayList<>(),secondary=new ArrayList<>();
    private final List<Double> times=new ArrayList<>();
    private final DoubleConsumer seek;
    private String fingerprint="";
    private int active=-1;
    private boolean follow=true;
    private long suppressTouchUntil;
    public LyricPage(Context context){this(context,()->{},()->{},t->{});}
    LyricPage(Context context,Runnable back,Runnable overlay,DoubleConsumer seek) {
        super(context);this.seek=seek;setOrientation(VERTICAL);setPadding(dp(20),0,dp(20),0);
        LinearLayout bar=new LinearLayout(context);bar.setGravity(Gravity.CENTER_VERTICAL);addView(bar);
        MusicUi.Icon arrow=new MusicUi.Icon(context,"back","返回封面",back);bar.addView(arrow,new LayoutParams(dp(48),dp(48)));
        heading=MusicUi.text(context,"歌词",16,MusicUi.TEXT);heading.setMaxLines(1);heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(heading,new LayoutParams(0,dp(52),1));heading.setGravity(Gravity.CENTER);
        desktop=new MusicUi.Icon(context,"lyric","开启桌面歌词",overlay);bar.addView(desktop,new LayoutParams(dp(48),dp(48)));
        scroll=new LyricScroll(context,()->{if(SystemClock.elapsedRealtime()>suppressTouchUntil)follow=false;});scroll.setClipToPadding(false);scroll.setVerticalScrollBarEnabled(false);
        addView(scroll,new LayoutParams(-1,0,1));rows=new LinearLayout(context);rows.setOrientation(VERTICAL);
        rows.setPadding(dp(6),dp(100),dp(6),dp(180));scroll.addView(rows);
        LinearLayout footer=new LinearLayout(context);footer.setGravity(Gravity.CENTER_VERTICAL);addView(footer);
        hint=MusicUi.text(context,"点击歌词跳转播放",12,MusicUi.MUTED);footer.addView(hint,new LayoutParams(0,dp(52),1));
        awake=MusicUi.pill(context,"常亮",()->{});footer.addView(awake,new LayoutParams(-2,dp(44)));
        Button locate=MusicUi.pill(context,"回到当前",()->{follow=true;center();});footer.addView(locate,new LayoutParams(-2,dp(44)));
    }
    void setKeepAwakeAction(Runnable action,boolean enabled){
        awake.setOnClickListener(v->action.run());awake.setText(enabled?"常亮已开":"常亮");
        awake.setTextColor(enabled?MusicUi.BLUE:MusicUi.TEXT);awake.setContentDescription(enabled?"关闭屏幕常亮":"开启屏幕常亮");
    }
    void desktopEnabled(boolean enabled){desktop.color(enabled?MusicUi.BLUE:MusicUi.TEXT);desktop.setContentDescription(enabled?"关闭桌面歌词":"开启桌面歌词");}
    void embedded(){LinearLayout bar=(LinearLayout)getChildAt(0);bar.getChildAt(0).setVisibility(GONE);desktop.setVisibility(GONE);}
    private int dp(float n){return MusicUi.dp(getContext(),n);}
    void update(JSONObject state,JSONObject lyric) {
        JSONObject pb=state.optJSONObject("playback");String id=pb==null?"":pb.optString("trackId");
        heading.setText(pb==null?"歌词":pb.optString("title"));
        JSONArray lines=lyric.optJSONArray("lines");
        String key=id+"|"+(lines==null?"":lines.toString());
        if(!key.equals(fingerprint)){
            fingerprint=key;rows.removeAllViews();primary.clear();secondary.clear();times.clear();active=-1;follow=true;scroll.scrollTo(0,0);
            if(lines==null||lines.length()==0){
                TextView empty=MusicUi.text(getContext(),"歌词正在加载，或此歌曲暂无歌词",17,MusicUi.MUTED);empty.setGravity(Gravity.CENTER);rows.addView(empty);
            }else for(int i=0;i<lines.length();i++){
                JSONObject line=lines.optJSONObject(i);if(line==null)continue;double time=line.optDouble("time",0);
                LinearLayout block=new LinearLayout(getContext());block.setOrientation(VERTICAL);block.setPadding(0,dp(14),0,dp(14));rows.addView(block);
                TextView text=MusicUi.text(getContext(),line.optString("text"),19,MusicUi.MUTED);
                text.setGravity(Gravity.CENTER);text.setLineSpacing(dp(3),1);block.addView(text);
                TextView translated=MusicUi.text(getContext(),line.optString("translated"),13,MusicUi.MUTED);
                translated.setGravity(Gravity.CENTER);translated.setVisibility(translated.getText().length()==0?GONE:VISIBLE);block.addView(translated);
                block.setMinimumHeight(dp(56));block.setOnClickListener(v->{suppressTouchUntil=SystemClock.elapsedRealtime()+500;follow=true;seek.accept(time);});
                primary.add(text);secondary.add(translated);times.add(time);
            }
        }
        double time=pb==null?0:pb.optDouble("currentTime",0);time+=lyric.optDouble("timeOffset",0)/1000;
        updateTime(time);
    }
    void updateTime(double time){
        int next=-1;for(int i=0;i<times.size();i++)if(times.get(i)<=time)next=i;
        if(next!=active){
            if(active>=0 && active<primary.size())style(active,false);
            active=next;if(active>=0)style(active,true);
            if(follow)post(this::center);
        }
        hint.setText(follow?"点击歌词跳转播放":"浏览歌词中 · 点右侧恢复跟随");
    }
    private void style(int index,boolean current){
        TextView p=primary.get(index);p.setTextColor(current?MusicUi.BLUE:MusicUi.MUTED);
        p.setTypeface(Typeface.DEFAULT,current?Typeface.BOLD:Typeface.NORMAL);
        secondary.get(index).setTextColor(current?MusicUi.BLUE:MusicUi.MUTED);
    }
    private void center(){
        if(active<0||active>=primary.size())return;
        View block=(View)primary.get(active).getParent();
        scroll.smoothScrollTo(0,Math.max(0,block.getTop()-(scroll.getHeight()-block.getHeight())/2));
    }
    private static final class LyricScroll extends ScrollView {
        private final Runnable browse;
        public LyricScroll(Context context){this(context,()->{});}
        LyricScroll(Context context,Runnable browse){super(context);this.browse=browse;}
        @Override public boolean onInterceptTouchEvent(MotionEvent event){
            if(event.getAction()==MotionEvent.ACTION_DOWN)browse.run();
            return super.onInterceptTouchEvent(event);
        }
        @Override public boolean performClick(){return super.performClick();}
    }
}
