package cc.echomusic.android;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.text.style.ReplacementSpan;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Persistent, resizable lyric page with real KRC word timing and manual scroll grace. */
public final class LyricsPanel {
    private final MainActivity activity;private final MusicRepository repo;
    private final Handler main=new Handler(Looper.getMainLooper());private final ExecutorService io=Executors.newFixedThreadPool(2);
    private boolean tablet;private TabletBackdrop tabletBackdrop;private LinearLayout tabletStage;private int tabletWidth,tabletHeight;private boolean restoreTouchMode;
    private FrameLayout touchPages;private View albumPage,fullLyricPage;private TextView recordPreview,fullTitle,fullArtist,coverTab,lyricTab;private boolean touchLyricsMode;private TvLyricsView tvLyrics;private boolean wide;private TextView artistView,headerLike;private ImageView backdrop;private TextView coverFallback;private LinearLayout songArea,lyricsArea;private SeekBar phoneProgress;private TextView phoneTime;
    private final LyricWindowState windowState=new LyricWindowState();
    public void dismiss(){windowState.hide();if(dialog!=null)dialog.dismiss();}
    public boolean isOpen(){return dialog!=null&&dialog.isShowing();}
    private Song song;private Lyrics.Document document;private Dialog dialog;private ScrollView scroll;
    private TextView title,statusView;private LinearLayout rows;private final List<TextView> primary=new ArrayList<>();
    private int generation=0,active=-2,paintedIndex=-1,delay=0,font=28;private boolean foreground=true,closed=false,secondary=true;
    private int lastPaintPosition=Integer.MIN_VALUE;private long manualUntil=0;private String message="选择歌曲后显示歌词";
    public LyricsPanel(MainActivity activity,MusicRepository repo){this.activity=activity;this.repo=repo;secondary=activity.getPreferences(0).getBoolean("lyrics_secondary",true);font=activity.getPreferences(0).getInt("lyrics_font",BuildConfig.TV?40:18);main.post(tick);}
    public void foreground(boolean value){foreground=value;if(value&&windowState.requested()&&dialog==null&&!closed)main.post(this::reopenRequested);}
    private void reopenRequested(){if(!closed&&foreground&&windowState.requested()&&dialog==null)open();}
    public void track(Song next){if(closed)return;if(next==null){if(song!=null){++generation;song=null;document=null;message="选择歌曲后显示歌词";renderDocument();}return;}if(song!=null&&song.key().equals(next.key()))return;song=next;document=null;lastTvLyricPosition=0;delay=repo.lyricOffset(next);if(BuildConfig.MGV2000)lyricSpeed=repo.lyricSpeed(next);active=-2;manualUntil=0;load(false,null);}
    public String preview(){if(document==null||song==null)return message;int index=Lyrics.current(document.lines,activity.currentPosition()-delay);return index>=0?document.lines.get(index).text:document.lines.isEmpty()?"点击查看歌词":"♪ 等待旋律响起";}
    @android.annotation.TargetApi(21)
    public void open(){if(closed)return;windowState.requestOpen();track(activity.currentSong());if(song==null){activity.toast("先选择一首歌");return;}if(dialog!=null){if(dialog.isShowing())resize();return;}
        if(!BuildConfig.TV){openTouch();return;}if(BuildConfig.TV){openTv();return;}wide=activity.getResources().getConfiguration().screenWidthDp>=840;
        dialog=new Dialog(activity,R.style.PlayerTheme);final Dialog owner=dialog;final long windowToken=windowState.opened();FrameLayout frame=new FrameLayout(activity);frame.setBackgroundColor(0xff213e52);
        final LyricBackdrop colorLayer=new LyricBackdrop(activity,wide);backdrop=new ImageView(activity){@Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);if(bitmap!=null){int color=bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2);float[] hsv=new float[3];Color.colorToHSV(color,hsv);hsv[1]=Math.min(.55f,hsv[1]);hsv[2]=Math.max(.16f,Math.min(.42f,hsv[2]));int tint=Color.HSVToColor(hsv);colorLayer.tint(tint);frame.setBackgroundColor(tint);}}};final ImageView windowBackdrop=backdrop;final boolean windowWide=wide;backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);FrameLayout coverLayer=new FrameLayout(activity){@Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);android.view.ViewGroup.LayoutParams p=windowBackdrop.getLayoutParams();int width=windowWide?Math.round(w*.64f):w;if(p.width!=width){p.width=width;windowBackdrop.setLayoutParams(p);}}};coverLayer.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));coverFallback=activity.text("♫",96,0xffa5f2f3);coverFallback.setGravity(Gravity.CENTER);FrameLayout.LayoutParams fallbackParams=new FrameLayout.LayoutParams(activity.dp(wide?320:180),-1,Gravity.START);coverLayer.addView(coverFallback,fallbackParams);frame.addView(coverLayer,new FrameLayout.LayoutParams(-1,-1));frame.addView(colorLayer,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout screen=activity.col();screen.setPadding(activity.dp(wide?28:16),activity.dp(8),activity.dp(wide?28:16),activity.dp(10));frame.addView(screen,new FrameLayout.LayoutParams(-1,-1));screen.setOnApplyWindowInsetsListener((v,insets)->{int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();if(Build.VERSION.SDK_INT>=30){android.graphics.Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());top=safe.top;bottom=safe.bottom;}screen.setPadding(activity.dp(wide?28:16),activity.dp(8)+top,activity.dp(wide?28:16),activity.dp(10)+bottom);return insets;});screen.requestApplyInsets();
        LinearLayout top=activity.row();top.addView(icon("⌄","收起播放页",this::dismiss),activity.lp(48,48));View spacer=new View(activity);top.addView(spacer,new LinearLayout.LayoutParams(0,1,1));top.addView(icon("⋮","歌词与播放设置",this::tools),activity.lp(48,48));screen.addView(top);
        LinearLayout stage=activity.row();screen.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        if(wide){View coverTap=new View(activity);coverTap.setContentDescription("点击封面返回主界面");coverTap.setOnClickListener(v->dismiss());stage.addView(coverTap,new LinearLayout.LayoutParams(0,-1,.47f));}
        LinearLayout panel=activity.col();panel.setPadding(activity.dp(wide?24:8),activity.dp(wide?18:8),activity.dp(wide?30:8),0);stage.addView(panel,new LinearLayout.LayoutParams(0,-1,wide?.53f:1));
        LinearLayout heading=activity.row();LinearLayout names=activity.col();title=activity.text(HandheldHome.name(song.title),wide?30:24,0xffffffff);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);names.addView(title);artistView=activity.text(song.artist,wide?18:15,0xffe1f2f5);names.addView(artistView);heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));headerLike=icon("♡","收藏当前歌曲",()->{if(song!=null)activity.toggleSongFavorite(song);});heading.addView(headerLike,activity.lp(52,52));panel.addView(heading);activity.gap(panel,10);
        statusView=activity.text(message,12,0xffc8dce7);panel.addView(statusView);statusView.setVisibility(View.GONE);
        scroll=new ScrollView(activity){@Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE)manualUntil=SystemClock.elapsedRealtime()+5000;return super.dispatchTouchEvent(event);}};scroll.setVerticalScrollBarEnabled(false);scroll.setFadingEdgeLength(activity.dp(40));scroll.setVerticalFadingEdgeEnabled(true);rows=activity.col();scroll.addView(rows);panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        activity.bindTheaterTitle(title,artistView);LinearLayout transport=activity.col();activity.buildTheaterControls(transport);screen.addView(transport);
        dialog.setContentView(frame);dialog.setOnDismissListener(d->{if(!windowState.current(windowToken)||dialog!=owner)return;boolean reopen=windowState.dismissed(windowToken);dialog=null;rows=null;scroll=null;backdrop=null;coverFallback=null;headerLike=null;phoneProgress=null;phoneTime=null;primary.clear();if(!closed){if(reopen)main.post(this::reopenRequested);else activity.render();}});dialog.show();SystemBars.applyPlayer(dialog.getWindow());if(BuildConfig.TV)TvFocus.install(dialog.getWindow().getDecorView());resize();renderDocument();activity.updatePlayer();
    }
    private int pageAnimation;
    private void showTouchPage(boolean lyrics){if(tablet){showTabletPage(lyrics);return;}if(albumPage==null||lyrics==touchLyricsMode)return;View incoming=lyrics?fullLyricPage:albumPage,outgoing=lyrics?albumPage:fullLyricPage;touchLyricsMode=lyrics;activity.fullLyricsControls(lyrics);restoreTouchMode=lyrics;updateTabs();int serial=++pageAnimation;incoming.animate().cancel();outgoing.animate().cancel();float width=Math.max(1,touchPages.getWidth()),direction=lyrics?1:-1;incoming.setVisibility(View.VISIBLE);incoming.setTranslationX(direction*width);incoming.setAlpha(.65f);outgoing.setTranslationX(0);outgoing.setAlpha(1);incoming.animate().translationX(0).alpha(1).setDuration(230).setInterpolator(new android.view.animation.DecelerateInterpolator(1.3f)).start();outgoing.animate().translationX(-direction*width).alpha(.65f).setDuration(230).withEndAction(()->{if(serial==pageAnimation){outgoing.setVisibility(View.GONE);outgoing.setTranslationX(0);outgoing.setAlpha(1);}}).start();active=-2;update();}
    private TextView lightIcon(String label,String description,Runnable action){TextView v=new MusicIconView(activity,22);v.setText(label);v.setTextColor(0xff18252b);v.setGravity(Gravity.CENTER);v.setContentDescription(description);v.setOnClickListener(w->action.run());return v;}
    @android.annotation.TargetApi(21)
    private void openTouch(){
        tablet=activity.getResources().getConfiguration().smallestScreenWidthDp>=600;if(tablet){openTablet();return;}
        wide=touchWide(activity.getResources().getConfiguration());touchLyricsMode=false;
        dialog=new Dialog(activity,R.style.PlayerTheme);final Dialog owner=dialog;final long token=windowState.opened();LinearLayout screen=new PlayerGestureLayout(activity,()->touchLyricsMode,this::dismiss);screen.setBackgroundColor(0xffedf0f3);
        android.content.res.Configuration size=activity.getResources().getConfiguration();
        boolean compact=!wide&&(size.screenWidthDp<=390||size.screenHeightDp<720);
        int side=activity.dp(wide?32:compact?22:20), topSpace=activity.dp(8), bottomSpace=activity.dp(compact?18:12);
        // Some Android skins fit a full-screen dialog to the bars before dispatching insets.
        screen.setPadding(side,topSpace,side,bottomSpace);
        screen.setOnApplyWindowInsetsListener((view,insets)->{
            int left=insets.getSystemWindowInsetLeft(),top=insets.getSystemWindowInsetTop();
            int right=insets.getSystemWindowInsetRight(),bottom=insets.getSystemWindowInsetBottom();
            if(Build.VERSION.SDK_INT>=30){
                Insets safe=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
                left=safe.left;top=safe.top;right=safe.right;bottom=safe.bottom;
            }
            view.setPadding(side+left,topSpace+top,side+right,bottomSpace+bottom);
            return insets;
        });
        LinearLayout top=activity.row();top.addView(new CollapseHandle(activity,this::dismiss),activity.lp(40,40));top.addView(playerTabs(),new LinearLayout.LayoutParams(0,activity.dp(48),1));top.addView(lightIcon("⋮","歌词与播放设置",this::tools),activity.lp(40,40));screen.addView(top);
        SwipePlayerBar pager=new SwipePlayerBar(activity,()->showTouchPage(false),()->showTouchPage(true));touchPages=new FrameLayout(activity);pager.addView(touchPages,new LinearLayout.LayoutParams(-1,-1));screen.addView(pager,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout album=wide?activity.row():activity.col();albumPage=album;FrameLayout recordStage=new FrameLayout(activity);RecordArtworkView record=new RecordArtworkView(activity,compact?Math.min(284,size.screenWidthDp-72):460);backdrop=record.image;coverFallback=record.placeholder;recordStage.addView(record,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));if(wide)album.addView(recordStage,new LinearLayout.LayoutParams(0,-1,.55f));else album.addView(recordStage,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout meta=activity.col();meta.setPadding(activity.dp(wide?24:4),activity.dp(wide?8:14),activity.dp(4),activity.dp(wide?8:12));LinearLayout heading=activity.row();LinearLayout names=activity.col();title=activity.text(HandheldHome.name(song.title),compact?21:24,0xff18252b);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(android.text.TextUtils.TruncateAt.END);names.addView(title);artistView=activity.text(song.artist,14,0xff718087);names.addView(artistView);heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));headerLike=lightIcon("♡","收藏当前歌曲",()->{if(song!=null)activity.toggleSongFavorite(song);});heading.addView(headerLike,activity.lp(44,48));meta.addView(heading);activity.gap(meta,8);recordPreview=activity.text(preview(),14,0xff59666f);recordPreview.setMaxLines(2);recordPreview.setMinHeight(activity.dp(44));recordPreview.setOnClickListener(v->showTouchPage(true));recordPreview.setContentDescription("歌词预览，点击查看全屏歌词");meta.addView(recordPreview);if(wide)album.addView(meta,new LinearLayout.LayoutParams(0,-2,.45f));else album.addView(meta,new LinearLayout.LayoutParams(-1,-2));touchPages.addView(album,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout lyrics=activity.col();fullLyricPage=lyrics;lyrics.setPadding(activity.dp(6),activity.dp(wide?6:12),activity.dp(6),0);fullTitle=activity.text(HandheldHome.name(song.title),compact?21:23,0xff18252b);fullTitle.setTypeface(null,Typeface.BOLD);fullTitle.setMaxLines(2);lyrics.addView(fullTitle);fullArtist=activity.text(song.artist,13,0xff718087);lyrics.addView(fullArtist);activity.gap(lyrics,18);statusView=activity.text(message,12,0xff718087);statusView.setVisibility(View.GONE);lyrics.addView(statusView);
        scroll=new ScrollView(activity){@Override public boolean dispatchTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE)manualUntil=SystemClock.elapsedRealtime()+5000;return super.dispatchTouchEvent(event);}};scroll.setVerticalScrollBarEnabled(false);scroll.setVerticalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(activity.dp(36));rows=activity.col();scroll.addView(rows);lyrics.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));touchPages.addView(lyrics,new FrameLayout.LayoutParams(-1,-1));lyrics.setVisibility(View.GONE);
        activity.bindTheaterTitle(title,artistView);LinearLayout transport=activity.col();activity.buildLightTransport(transport);screen.addView(transport);dialog.setContentView(screen);
        dialog.setOnDismissListener(d->{if(!windowState.current(token)||dialog!=owner)return;boolean reopen=windowState.dismissed(token);dialog=null;rows=null;scroll=null;backdrop=null;coverFallback=null;headerLike=null;recordPreview=null;fullTitle=null;fullArtist=null;touchPages=null;albumPage=null;fullLyricPage=null;coverTab=null;lyricTab=null;primary.clear();if(!closed){if(reopen)main.post(this::reopenRequested);else activity.render();}});
        dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP&&touchLyricsMode){showTouchPage(false);return true;}return false;});dialog.show();dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xffedf0f3));dialog.getWindow().setLayout(-1,-1);SystemBars.apply(dialog.getWindow());screen.requestApplyInsets();renderDocument();activity.updatePlayer();if(restoreTouchMode)showTouchPage(true);
    }
    private LinearLayout playerTabs(){
        LinearLayout tabs=activity.row();
        tabs.setGravity(Gravity.CENTER);
        coverTab=activity.text("封面",14,Color.WHITE);
        lyricTab=activity.text("歌词",14,Color.WHITE);
        coverTab.setGravity(Gravity.CENTER);
        lyricTab.setGravity(Gravity.CENTER);
        coverTab.setOnClickListener(v->showTouchPage(false));
        lyricTab.setOnClickListener(v->showTouchPage(true));
        tabs.addView(coverTab,activity.lp(60,44));
        tabs.addView(lyricTab,activity.lp(60,44));
        updateTabs();
        return tabs;
    }
    private void updateTabs(){
        coverTab.setSelected(!touchLyricsMode);lyricTab.setSelected(touchLyricsMode);
        for(TextView tab:new TextView[]{coverTab,lyricTab}){
            boolean selected=tab.isSelected();
            tab.setTypeface(null,selected?Typeface.BOLD:Typeface.NORMAL);
            tab.setTextColor(tablet?Color.WHITE:selected?0xff17252b:0xff59676f);
            tab.setBackground(selected?activity.shape(tablet?0x28ffffff:0xffdce6e8,22):null);
        }
    }
    private static boolean touchWide(android.content.res.Configuration config){
        return config.screenWidthDp>=600&&config.screenWidthDp>config.screenHeightDp;
    }
    private int lyricMuted(){return tablet?0xffc7ced0:0xff7c858b;}
    private int lyricActive(){return tablet?Color.WHITE:0xff10191e;}
    private int lyricFont(){return tablet?Math.max(24,Math.min(38,font+10)):font;}
    private void showTabletPage(boolean full){if(tabletBackdrop==null)return;touchLyricsMode=full;restoreTouchMode=full;tabletBackdrop.lyrics(full);updateTabs();if(wide){albumPage.setVisibility(full?View.GONE:View.VISIBLE);fullLyricPage.setVisibility(View.VISIBLE);}else{albumPage.setVisibility(full?View.GONE:View.VISIBLE);fullLyricPage.setVisibility(full?View.VISIBLE:View.GONE);}View incoming=full?fullLyricPage:albumPage;incoming.setAlpha(.35f);incoming.setTranslationX(activity.dp(full?24:-24));incoming.animate().alpha(1).translationX(0).setDuration(220).start();active=-2;activity.fullLyricsControls(false);update();}
    @android.annotation.TargetApi(21)
    private void openTablet(){
        android.content.res.Configuration config=activity.getResources().getConfiguration();tabletWidth=config.screenWidthDp;tabletHeight=config.screenHeightDp;wide=tabletWidth>tabletHeight;touchLyricsMode=false;
        dialog=new Dialog(activity,R.style.PlayerTheme);final Dialog owner=dialog;final long token=windowState.opened();FrameLayout frame=new FrameLayout(activity);frame.setBackgroundColor(0xff354c58);tabletBackdrop=new TabletBackdrop(activity,wide);tabletBackdrop.tintChanged=color->{if(dialog==owner&&owner.getWindow()!=null)SystemBars.applyAlbum(owner.getWindow(),color);};backdrop=tabletBackdrop;frame.addView(backdrop,new FrameLayout.LayoutParams(-1,-1));coverFallback=activity.text("",40,Color.WHITE);frame.addView(coverFallback);
        LinearLayout screen=new PlayerGestureLayout(activity,()->wide||touchLyricsMode,this::dismiss);frame.addView(screen,new FrameLayout.LayoutParams(-1,-1));int side=wide?32:36;screen.setPadding(activity.dp(side),activity.dp(12),activity.dp(side),activity.dp(22));frame.setOnApplyWindowInsetsListener((v,insets)->{int top=insets.getSystemWindowInsetTop(),bottom=insets.getSystemWindowInsetBottom();screen.setPadding(activity.dp(side),top+activity.dp(10),activity.dp(side),bottom+activity.dp(22));return insets;});
        LinearLayout top=activity.row();View close=new CollapseHandle(activity,Color.WHITE,this::dismiss);top.addView(close,activity.lp(48,48));top.addView(playerTabs(),new LinearLayout.LayoutParams(0,activity.dp(48),1));top.addView(icon("⋮","歌词与播放设置",this::tools),activity.lp(48,48));screen.addView(top);
        SwipePlayerBar pager=new SwipePlayerBar(activity,()->showTabletPage(false),()->showTabletPage(true));screen.addView(pager,new LinearLayout.LayoutParams(-1,0,1));tabletStage=wide?activity.row():activity.col();pager.addView(tabletStage,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout album=activity.col();albumPage=album;title=activity.text(HandheldHome.name(song.title),32,Color.WHITE);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);artistView=activity.text(song.artist,20,0xffe4e9e9);recordPreview=activity.text(preview(),23,0xffe4e9e9);recordPreview.setMaxLines(2);recordPreview.setMinHeight(activity.dp(70));recordPreview.setContentDescription("歌词预览，点击查看全屏歌词");recordPreview.setOnClickListener(v->showTabletPage(true));
        FrameLayout coverStage=new FrameLayout(activity);
        RecordArtworkView coverCard=tabletCover=new RecordArtworkView(activity);
        coverStage.addView(coverCard,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        album.addView(coverStage,new LinearLayout.LayoutParams(-1,0,1));
        if(wide){tabletStage.addView(album,new LinearLayout.LayoutParams(0,-1,.48f));title.setVisibility(View.GONE);artistView.setVisibility(View.GONE);recordPreview.setVisibility(View.GONE);album.addView(title);album.addView(artistView);album.addView(recordPreview);}else{tabletStage.addView(album,new LinearLayout.LayoutParams(-1,-1));LinearLayout heading=activity.row();heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));headerLike=icon("♡","收藏当前歌曲",()->activity.toggleSongFavorite(song));heading.addView(headerLike,activity.lp(52,52));album.addView(heading);activity.gap(album,12);album.addView(artistView);activity.gap(album,24);album.addView(recordPreview);activity.gap(album,22);}
        LinearLayout lyrics=activity.col();fullLyricPage=lyrics;lyrics.setPadding(activity.dp(wide?24:4),activity.dp(20),activity.dp(8),0);LinearLayout heading=activity.row();LinearLayout names=activity.col();fullTitle=activity.text(HandheldHome.name(song.title),32,Color.WHITE);fullTitle.setTypeface(null,Typeface.BOLD);fullTitle.setMaxLines(2);names.addView(fullTitle);fullArtist=activity.text(song.artist,20,0xffe4e9e9);names.addView(fullArtist);heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));TextView favorite=icon("♡","收藏当前歌曲",()->activity.toggleSongFavorite(song));if(wide)headerLike=favorite;heading.addView(favorite,activity.lp(52,52));favorite.setTag("tablet_favorite");lyrics.addView(heading);activity.gap(lyrics,28);statusView=activity.text(message,13,0xffe4e9e9);statusView.setVisibility(View.GONE);lyrics.addView(statusView);scroll=new ScrollView(activity){@Override public boolean dispatchTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE)manualUntil=SystemClock.elapsedRealtime()+5000;return super.dispatchTouchEvent(e);}};scroll.setVerticalScrollBarEnabled(false);scroll.setVerticalFadingEdgeEnabled(true);scroll.setFadingEdgeLength(activity.dp(48));rows=activity.col();scroll.addView(rows);lyrics.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));if(wide)tabletStage.addView(lyrics,new LinearLayout.LayoutParams(0,-1,.52f));else{tabletStage.addView(lyrics,new LinearLayout.LayoutParams(-1,-1));lyrics.setVisibility(View.GONE);}
        activity.bindTheaterTitle(title,artistView);LinearLayout transport=activity.col();activity.buildLightTransport(transport);activity.tintTabletTransport(transport);screen.addView(transport);dialog.setContentView(frame);
        dialog.setOnDismissListener(d->{if(!windowState.current(token)||dialog!=owner)return;boolean reopen=windowState.dismissed(token);dialog=null;rows=null;scroll=null;backdrop=null;coverFallback=null;tabletBackdrop=null;tabletCover=null;headerLike=null;recordPreview=null;fullTitle=null;fullArtist=null;albumPage=null;fullLyricPage=null;coverTab=null;lyricTab=null;primary.clear();if(!closed){if(reopen)main.post(this::reopenRequested);else{restoreTouchMode=false;activity.render();}}});
        dialog.setOnKeyListener((d,key,event)->{if(key==KeyEvent.KEYCODE_BACK&&event.getAction()==KeyEvent.ACTION_UP&&touchLyricsMode){showTabletPage(false);return true;}return false;});dialog.show();dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0xff354c58));dialog.getWindow().setLayout(-1,-1);SystemBars.applyPlayer(dialog.getWindow());frame.requestApplyInsets();renderDocument();activity.updatePlayer();if(restoreTouchMode)showTabletPage(true);
    }
    private void openTv(){
        wide=activity.getResources().getConfiguration().screenWidthDp>=840;
        dialog=new Dialog(activity,R.style.PlayerTheme);final Dialog owner=dialog;final long token=windowState.opened();
        LinearLayout screen=activity.col();screen.setBackgroundColor(0xff132c3e);screen.setPadding(activity.dp(30),activity.dp(12),activity.dp(30),activity.dp(12));
        LinearLayout heading=activity.row();heading.addView(icon("返回","返回音乐库",this::dismiss),activity.lp(80,48));
        LinearLayout names=activity.col();title=activity.text(song.title,28,Color.WHITE);title.setGravity(Gravity.CENTER);title.setSingleLine();title.setEllipsize(TextUtils.TruncateAt.END);names.addView(title);artistView=activity.text(song.artist,18,0xffb8ccd9);artistView.setGravity(Gravity.CENTER);names.addView(artistView);heading.addView(names,new LinearLayout.LayoutParams(0,-2,1));heading.addView(icon("设置","歌词设置",this::tools),activity.lp(80,48));screen.addView(heading);
        statusView=activity.text(message,18,0xffb8ccd9);statusView.setVisibility(View.GONE);screen.addView(statusView);
        rows=activity.col();tvLyrics=new TvLyricsView(activity);screen.addView(tvLyrics,new LinearLayout.LayoutParams(-1,0,1));
        activity.bindTheaterTitle(title,artistView);LinearLayout controls=activity.col();activity.buildTheaterControls(controls);screen.addView(controls);
        dialog.setContentView(screen);dialog.setOnDismissListener(d->{if(!windowState.current(token)||dialog!=owner)return;boolean reopen=windowState.dismissed(token);dialog=null;tvLyrics=null;rows=null;primary.clear();if(!closed){if(reopen)main.post(this::reopenRequested);else activity.render();}});
        if(BuildConfig.MGV2000)dialog.setOnKeyListener((d,key,event)->activity.handleTvSeekKey(event));
        dialog.show();dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);SystemBars.applyPlayer(dialog.getWindow());dialog.getWindow().setLayout(-1,-1);TvFocus.install(screen);renderDocument();activity.updatePlayer();
    }
    private TextView icon(String label,String description,Runnable action){TextView view=activity.text(label,28,0xffffffff);view.setGravity(Gravity.CENTER);view.setContentDescription(description);view.setOnClickListener(v->action.run());return view;}
    private RecordArtworkView tabletCover;
    private void tools(){
        if(!BuildConfig.TV){touchOptions();return;}
        String[] labels=BuildConfig.TV?new String[]{"选择歌词版本","时间校准 / 字号 / 译文","跟随当前播放","重新获取歌词","歌曲操作"}:new String[]{"选择歌词版本","时间校准 / 字号 / 译文","导入本地歌词","跟随当前播放","重新获取歌词","歌曲操作","状态栏歌词"};new AlertDialog.Builder(activity).setTitle("歌词与播放").setItems(labels,(d,which)->{int choice=BuildConfig.TV&&which>=2?which+1:which;switch(choice){case 0:choose();break;case 1:settings();break;case 2:if(BuildConfig.TV)activity.toast("电视版使用在线歌词");else activity.importLyrics();break;case 3:manualUntil=0;active=-2;update();break;case 4:load(true,null);break;case 6:StatusLyrics.settings(activity);break;default:if(song!=null)activity.songMenu(song);}}).setNegativeButton("取消",null).show();}
    private void touchOptions(){
        Dialog menu=new Dialog(activity);
        LinearLayout surface=activity.col();
        surface.setBackground(activity.shape(0xfff7f9fa,26));
        activity.pad(surface,18);
        LinearLayout heading=activity.row();
        heading.addView(activity.title("歌词与播放",22),new LinearLayout.LayoutParams(0,-2,1));
        heading.addView(lightIcon("⌄","关闭歌词与播放设置",menu::dismiss),activity.lp(48,48));
        surface.addView(heading);
        activity.gap(surface,6);
        ScrollView scroll=new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        boolean columns=activity.getResources().getConfiguration().screenWidthDp>=700;
        LinearLayout groups=columns?activity.row():activity.col();
        if(columns)groups.setGravity(Gravity.TOP);
        scroll.addView(groups);
        LinearLayout playback=activity.col(),lyric=activity.col();
        if(columns){
            groups.addView(playback,new LinearLayout.LayoutParams(0,-2,1));
            LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,-2,1);
            right.leftMargin=activity.dp(14);
            groups.addView(lyric,right);
        }else{
            groups.addView(playback);
            activity.gap(groups,14);
            groups.addView(lyric);
        }
        optionGroup(playback,"播放");
        option(menu,playback,"播放音质","标准、高音质与无损",activity::playbackQuality);
        option(menu,playback,"定时关闭","设定停止播放的时间",activity::sleepTimer);
        if(!BuildConfig.TV)option(menu,playback,"后台持续播放",PlaybackPreferences.background(activity)?"已开启 · 抖音等应用不再打断":"已关闭 · 其他应用发声即暂停",activity::toggleBackgroundPlayback);
        if(song!=null&&!song.local())option(menu,playback,"下载到本地","离线播放",activity::downloadCurrent);
        option(menu,playback,"歌曲操作","收藏、加入歌单与队列",()->{if(song!=null)activity.songMenu(song);});

        optionGroup(lyric,"歌词");
        option(menu,lyric,"歌词显示","调整字号、时间与译文",this::settings);
        option(menu,lyric,"跟随当前播放","回到正在播放的歌词",this::followCurrent);
        if(song!=null&&!song.local()){
            option(menu,lyric,"歌词版本","选择或重新获取在线歌词",this::choose);
            option(menu,lyric,"重新获取歌词","刷新当前歌曲的歌词",()->load(true,null));
        }
        option(menu,lyric,"导入歌词","选择 LRC 或 KRC 文件",activity::importLyrics);
        option(menu,lyric,"状态栏歌词","设置显示位置和样式",()->StatusLyrics.settings(activity));
        final int cap=Math.round(activity.getResources().getDisplayMetrics().heightPixels*.65f);
        scroll.measure(View.MeasureSpec.makeMeasureSpec(Math.min(activity.getResources().getDisplayMetrics().widthPixels-activity.dp(60),activity.dp(592)),View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(cap,View.MeasureSpec.AT_MOST));
        surface.addView(scroll,new LinearLayout.LayoutParams(-1,Math.min(cap,scroll.getMeasuredHeight())));
        menu.setContentView(surface);
        menu.show();
        Window window=menu.getWindow();
        window.setBackgroundDrawableResource(android.R.color.transparent);
        window.setGravity(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);
        window.setLayout(Math.min(activity.dp(640),activity.getResources().getDisplayMetrics().widthPixels-activity.dp(24)),-2);
    }
    private void optionGroup(LinearLayout group,String name){
        activity.pad(group,10);
        group.setBackground(activity.shape(Color.WHITE,18));
        TextView title=activity.text(name,13,0xff37665c);
        title.setTypeface(null,Typeface.BOLD);
        title.setPadding(activity.dp(8),activity.dp(6),0,activity.dp(3));
        group.addView(title);
    }
    private void option(Dialog menu,LinearLayout group,String title,String detail,Runnable action){
        LinearLayout item=activity.row();activity.pad(item,8);
        item.setMinimumHeight(activity.dp(52));
        LinearLayout labels=activity.col();
        labels.addView(activity.text(title,15,0xff18252b));
        labels.addView(activity.text(detail,12,0xff58666e));
        item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        item.addView(activity.text("›",20,0xff64737a),activity.lp(16,-2));
        android.util.TypedValue background=new android.util.TypedValue();
        if(activity.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,background,true))item.setBackgroundResource(background.resourceId);
        item.setOnClickListener(v->{menu.dismiss();action.run();});
        group.addView(item);
    }
    private void followCurrent(){manualUntil=0;followPending=true;update();}
    public void resize(){if(dialog!=null&&dialog.isShowing()){android.content.res.Configuration c=activity.getResources().getConfiguration();boolean nowWide=BuildConfig.TV?c.screenWidthDp>=840:touchWide(c);if(tablet?(tabletWidth!=c.screenWidthDp||tabletHeight!=c.screenHeightDp):nowWide!=wide){restoreTouchMode=touchLyricsMode;if(windowState.rebuild())dialog.dismiss();return;}dialog.getWindow().setLayout(-1,-1);}}
    private boolean valid(int serial,Song requested){return !closed&&serial==generation&&song!=null&&song.key().equals(requested.key());}
    private void load(boolean force,LyricChoice selected){if(song==null)return;Song requested=song;final int playingDuration=activity.currentDuration();final int duration=BuildConfig.MGV2000&&playingDuration>0?playingDuration:requested.durationMs>0?requested.durationMs:playingDuration;int serial=++generation;message="正在获取歌词…";if(statusView!=null)statusView.setText(message);
        if(BuildConfig.MGV2000&&tvLyrics!=null)tvLyrics.emptyMessage(message);
        io.execute(()->{try{
            if(!force&&selected==null){String cached=repo.cachedLyrics(requested);if(!cached.isEmpty()){deliver(serial,requested,cached,"已缓存");return;}}
            if(requested.local()&&selected==null){main.post(()->{if(valid(serial,requested)){message="本地歌曲可导入 LRC / KRC 歌词";renderDocument();}});return;}
            String raw=repo.fetchLyrics(requested,selected,duration);deliver(serial,requested,raw,"");
        }catch(Exception error){main.post(()->{if(valid(serial,requested)){message=error.getMessage()==null?"歌词获取失败，可重试或导入":error.getMessage();renderDocument();}});}});
    }
    private void deliver(int serial,Song requested,String raw,String note){Lyrics.Document parsed=Lyrics.document(raw);main.post(()->{if(!valid(serial,requested))return;document=parsed;message=(note.isEmpty()?"":note+" · ")+(parsed.karaoke?"逐字歌词":parsed.lines.isEmpty()?"纯文本歌词":"同步歌词");active=-2;renderDocument();});}
    private void renderDocument(){if(dialog==null||rows==null)return;title.setText(song==null?"歌词":BuildConfig.TV?song.title:HandheldHome.name(song.title));if(artistView!=null)artistView.setText(song==null?"":song.artist);statusView.setText(activity.getString(R.string.lyric_status,message,offsetLabel()));rows.removeAllViews();primary.clear();active=-2;paintedIndex=-1;lastPaintPosition=Integer.MIN_VALUE;if(BuildConfig.TV){if(tvLyrics!=null){if(BuildConfig.MGV2000)tvLyrics.emptyMessage(message);tvLyrics.display(document,tvLyricPosition());}return;}if(document==null){
            TextView note=activity.text(message,15,lyricMuted());
            activity.pad(note,12);rows.addView(note);return;
        }
        if(document.lines.isEmpty()){TextView text=activity.text(document.plain.isEmpty()?"暂无可显示歌词":document.plain,lyricFont(),lyricMuted());text.setTextIsSelectable(true);activity.pad(text,14);rows.addView(text);return;}
        activity.gap(rows,20);for(Lyrics.Line line:document.lines){LinearLayout item=activity.col();item.setPadding(0,activity.dp(10),0,activity.dp(10));TextView original=activity.text(line.text.isEmpty()?"♪":line.text,lyricFont(),BuildConfig.TV?0xffb8ccd9:lyricMuted());original.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);original.setLineSpacing(activity.dp(4),1);item.addView(original);primary.add(original);
            if(secondary){String extra=line.translation+(line.translation.isEmpty()||line.romanization.isEmpty()?"":"\n")+line.romanization;if(!extra.isEmpty()){TextView sub=activity.text(extra,Math.max(13,font-6),BuildConfig.TV?0xffb8ccd9:lyricMuted());activity.gap(item,6);sub.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);item.addView(sub);}}
            item.setOnClickListener(v->{if(song!=null&&activity.currentSong()!=null&&song.key().equals(activity.currentSong().key())){manualUntil=0;activity.seekCurrent(Math.max(0,line.millis+delay));active=-2;update();}});rows.addView(item);
        }activity.gap(rows,120);update();}
    private int lastTvLyricPosition;
    private int tvLyricPosition(){
        if(activity.playbackSeeking())return lastTvLyricPosition;
        int time=activity.currentPosition();
        lastTvLyricPosition=(BuildConfig.MGV2000&&song!=null?Math.round(time*lyricSpeed/100f):time)-delay;
        return lastTvLyricPosition;
    }
    private int lyricSpeed=100;
    private String offsetLabel(){return delay==0?"":" · 延后 "+delay+"ms";}
    private boolean followPending;
    private final Runnable tick=new Runnable(){public void run(){if(closed)return;if(foreground&&isOpen()){track(activity.currentSong());update();}main.postDelayed(this,!foreground||!isOpen()?1000:BuildConfig.TV?250:100);}};
    private void update(){if(recordPreview!=null){String line=preview();if(!line.contentEquals(recordPreview.getText()))recordPreview.setText(line);if(song!=null&&fullTitle!=null){String clean=HandheldHome.name(song.title);if(!clean.contentEquals(fullTitle.getText()))fullTitle.setText(clean);if(!song.artist.contentEquals(fullArtist.getText()))fullArtist.setText(song.artist);}}if(BuildConfig.TV){if(tvLyrics!=null)tvLyrics.display(document,tvLyricPosition());return;}if(isOpen()&&song!=null){activity.bindLyricArtwork(backdrop,coverFallback,song);
        if(tablet&&tabletCover!=null)activity.bindLyricArtwork(tabletCover.image,tabletCover.placeholder,song);
        if(headerLike!=null){String heart=repo.favorite(song)?"♥":repo.favoriteKnown(song)?"♡":"♡·";if(!heart.contentEquals(headerLike.getText()))headerLike.setText(heart);}if(!BuildConfig.TV&&headerLike!=null)headerLike.setTextColor(repo.favorite(song)?0xfff15c72:tablet?Color.WHITE:0xff18252b);}if(tablet&&fullLyricPage!=null){TextView favorite=fullLyricPage.findViewWithTag("tablet_favorite");if(favorite!=null){favorite.setText(repo.favorite(song)?"♥":"♡");favorite.setTextColor(repo.favorite(song)?0xfff15c72:Color.WHITE);}}if(dialog!=null&&phoneProgress!=null){if(!phoneProgress.isPressed()){phoneProgress.setMax(Math.max(1,activity.currentDuration()));phoneProgress.setProgress(activity.currentPosition());}phoneTime.setText(activity.getString(R.string.player_time,MainActivity.time(activity.currentPosition()),MainActivity.time(activity.currentDuration())));}if(dialog==null||document==null||song==null||primary.isEmpty())return;int position=activity.currentPosition()-delay;int index=Lyrics.current(document.lines,position);
        if(index!=active){if(paintedIndex>=0&&paintedIndex<primary.size()){TextView old=primary.get(paintedIndex);old.setText(document.lines.get(paintedIndex).text);old.setTextColor(BuildConfig.TV?0xffb8ccd9:lyricMuted());old.setTypeface(null,Typeface.NORMAL);}active=index;paintedIndex=index;if(index>=0&&index<primary.size()){TextView selected=primary.get(index);selected.setTextColor(BuildConfig.TV?0xffffffff:lyricActive());selected.setTypeface(null,Typeface.BOLD);followPending=true;}}
        boolean showingLyrics=tablet?(wide||touchLyricsMode):touchLyricsMode;
        if(!showingLyrics)return;
        if(followPending&&index>=0&&index<primary.size()&&SystemClock.elapsedRealtime()>=manualUntil){
            followPending=false;
            ScrollView target=scroll;
            TextView selected=primary.get(index);
            target.post(()->{if(scroll==target&&active==index&&selected.getParent() instanceof View){
                View row=(View)selected.getParent();
                target.smoothScrollTo(0,Math.max(0,row.getTop()+row.getHeight()/2-target.getHeight()/3));
            }});
        }
        if(index>=0&&index<primary.size()&&position!=lastPaintPosition){lastPaintPosition=position;Lyrics.Line line=document.lines.get(index);if(!line.words.isEmpty()){SpannableString text=new SpannableString(line.text);for(Lyrics.Word word:line.words)if(word.to>word.from&&word.to<=text.length())text.setSpan(new KaraokeSpan(word.progress(position),tablet),word.from,word.to,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);primary.get(index).setText(text);}}
    }
    private static final class KaraokeSpan extends ReplacementSpan{
        private final float progress;private final boolean tablet;KaraokeSpan(float progress,boolean tablet){this.progress=progress;this.tablet=tablet;}
        @Override public int getSize(Paint paint,CharSequence text,int start,int end,Paint.FontMetricsInt metrics){if(metrics!=null)paint.getFontMetricsInt(metrics);return (int)Math.ceil(paint.measureText(text,start,end));}
        @Override public void draw(Canvas canvas,CharSequence text,int start,int end,float x,int top,int baseline,int bottom,Paint paint){int color=paint.getColor();float width=paint.measureText(text,start,end);paint.setColor(BuildConfig.TV?0xffc1d2de:tablet?0xffffffff:0xff10191e);canvas.drawText(text,start,end,x,baseline,paint);int saved=canvas.save();canvas.clipRect(x,top,x+width*progress,bottom);paint.setColor(BuildConfig.TV?0xffffffff:tablet?0xff9dffce:0xff00b775);canvas.drawText(text,start,end,x,baseline,paint);canvas.restoreToCount(saved);paint.setColor(color);}
    }
    private void choose(){if(song==null)return;if(song.local()){activity.toast("本地歌曲请使用导入歌词");return;}Song requested=song;final int duration=activity.currentDuration();int serial=++generation;message="正在查找歌词候选…";if(statusView!=null)statusView.setText(message);io.execute(()->{try{List<LyricChoice> choices=repo.lyricChoices(requested,duration);main.post(()->{if(!valid(serial,requested))return;if(choices.isEmpty()){message="没有找到歌词候选，可导入歌词";renderDocument();return;}String[] names=new String[choices.size()];for(int i=0;i<choices.size();i++)names[i]=choices.get(i).label();new AlertDialog.Builder(activity).setTitle("选择歌词版本").setItems(names,(d,which)->{if(valid(serial,requested))load(true,choices.get(which));else activity.toast("歌曲已变化，请重新选择歌词");}).setNegativeButton("取消",null).show();});}catch(Exception error){main.post(()->{if(valid(serial,requested)){message=error.getMessage();renderDocument();}});}});}
    private void settings(){if(song==null)return;Song configured=song;final int base=BuildConfig.TV?28:16;LinearLayout form=activity.col();activity.pad(form,18);TextView label=activity.text("时间校准："+delay+"ms（正值延后）",14,0xff102e35);form.addView(label);SeekBar offset=new SeekBar(activity);offset.setMax(20000);offset.setProgress(delay+10000);form.addView(offset);CheckBox extra=new CheckBox(activity);extra.setText("显示译文和音译");extra.setChecked(secondary);form.addView(extra);TextView size=activity.text("字号："+font,14,0xff102e35);form.addView(size);SeekBar fontBar=new SeekBar(activity);fontBar.setMax(BuildConfig.TV?32:18);fontBar.setProgress(font-base);form.addView(fontBar);
        SeekBar speedBar=new SeekBar(activity);
        if(BuildConfig.MGV2000){
            TextView speedLabel=activity.text("歌词速度 "+lyricSpeed+"%（歌词落后时调高）",14,0xff102e35);
            form.addView(speedLabel);
            speedBar.setMax(40);speedBar.setProgress(lyricSpeed-80);form.addView(speedBar);
            speedBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar bar,int value,boolean user){speedLabel.setText("歌词速度 "+(80+value)+"%（歌词落后时调高）");}
                public void onStartTrackingTouch(SeekBar bar){}
                public void onStopTrackingTouch(SeekBar bar){}
            });
        }
        offset.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){label.setText(activity.getString(R.string.lyric_offset,p-10000));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        fontBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){size.setText(activity.getString(R.string.lyric_font,p+base));}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});
        new AlertDialog.Builder(activity).setTitle("歌词显示").setView(form).setNegativeButton("取消",null).setNeutralButton("重置校准",(d,w)->{repo.lyricOffset(configured,0);if(BuildConfig.MGV2000)repo.lyricSpeed(configured,100);if(song!=null&&song.key().equals(configured.key())){delay=0;lyricSpeed=100;renderDocument();}}).setPositiveButton("保存",(d,w)->{if(song==null||!song.key().equals(configured.key())){activity.toast("歌曲已变化，请重新打开设置");return;}delay=offset.getProgress()-10000;repo.lyricOffset(configured,delay);if(BuildConfig.MGV2000){lyricSpeed=80+speedBar.getProgress();repo.lyricSpeed(configured,lyricSpeed);}font=fontBar.getProgress()+base;secondary=extra.isChecked();activity.getPreferences(0).edit().putBoolean("lyrics_secondary",secondary).putInt("lyrics_font",font).apply();renderDocument();}).show();}
    public void imported(Song imported,String raw){io.execute(()->{try{repo.storeLyrics(imported,raw);main.post(()->{if(!closed){activity.toast("歌词已导入");if(song!=null&&song.key().equals(imported.key()))load(false,null);}});}catch(Exception e){main.post(()->{if(!closed)activity.toast("歌词保存失败，请重试");});}});}
    public void close(){windowState.hide();closed=true;++generation;main.removeCallbacksAndMessages(null);io.shutdownNow();if(dialog!=null)dialog.dismiss();}
}
