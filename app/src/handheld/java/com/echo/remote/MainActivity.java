package com.echo.remote;
import cc.echomusic.android.R;

import android.app.*;
import android.os.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import org.json.*;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private RemoteApi api;
    private LinearLayout shell,content,results,mini,tabs;
    private TextView title,artist,lyric,time,remaining,miniTitle,miniArtist,connection,section;
    private ImageView cover,miniCover;
    private LyricPage lyricPage;
    private TextView libraryScope;
    private MusicUi.Icon play,miniPlay,favorite;
    private MusicUi.Icon lyricsToggle;
    private SeekBar seek,volume;
    private TextView volumeLabel;
    private Button modeButton;
    private final java.util.ArrayList<Runnable> songIndicators=new java.util.ArrayList<>();
    private boolean volumeDragging,modePending;
    private EditText query;
    private String songId="",page="player",libraryTab="favorites",selectedList="",coverKey="";
    private String trackTitle="",trackArtist="";
    private Bitmap artwork;
    private JSONObject latest=new JSONObject();
    private boolean resumed,polling,connected,playing,isFavorite,loadingCover,seekDragging;
    private int generation,screenEpoch;
    private long lyricsFetched;
    private JSONObject lyrics=new JSONObject();
    private boolean pendingNotification,pendingOverlay;
    private boolean keepAwake;
    private long desktopRequestedUntil;
    private final LiveVolumeQueue liveVolume=new LiveVolumeQueue(value->{if(api==null)throw new java.io.IOException("请先连接电脑");api.command(RemoteApi.body("volume","volume",value));},error->ui.post(()->{if(!isDestroyed())message(error.getMessage());}));
    interface Request { JSONObject run(RemoteApi api) throws Exception; }
    interface Result { void accept(JSONObject result) throws Exception; }
    private int dp(float n) { return MusicUi.dp(this,n); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        keepAwake=getPreferences(0).getBoolean("keep_awake",false);
        getWindow().setStatusBarColor(MusicUi.BG); getWindow().setNavigationBarColor(MusicUi.BG);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        shell=new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setBackgroundColor(MusicUi.BG);
        setContentView(shell);
        shell.setOnApplyWindowInsetsListener((v,i)->{
            v.setPadding(0,i.getSystemWindowInsetTop(),0,i.getSystemWindowInsetBottom()); return i;
        });
        LinearLayout header=row(shell);
        header.setPadding(dp(20),dp(8),dp(12),dp(8)); header.setGravity(Gravity.CENTER_VERTICAL);
        icon(header,"back","返回音乐主界面",this::finish,48);
        TextView brand=text("Echo 远控",22,MusicUi.TEXT); brand.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        header.addView(brand,new LinearLayout.LayoutParams(0,dp(48),1)); brand.setGravity(Gravity.CENTER_VERTICAL);
        connection=text("连接电脑",12,MusicUi.BLUE); connection.setPadding(dp(14),0,dp(14),0);
        connection.setGravity(Gravity.CENTER); connection.setBackground(MusicUi.shape(MusicUi.SURFACE,24,this));
        header.addView(connection,new LinearLayout.LayoutParams(-2,dp(40))); connection.setOnClickListener(v->connectionDialog());
        lyricsToggle=icon(header,"lyric","打开歌词页",this::toggleLyrics,48);
        icon(header,"more","设置",this::settings,48);
        content=column(); shell.addView(content,new LinearLayout.LayoutParams(-1,0,1));
        mini=row(shell); mini.setPadding(dp(16),dp(8),dp(10),dp(8)); mini.setGravity(Gravity.CENTER_VERTICAL);
        mini.setBackground(MusicUi.shape(MusicUi.SURFACE,0,this));
        mini.setOnClickListener(v->show("player"));
        miniCover=image(44); mini.addView(miniCover,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout names=column(); names.setPadding(dp(12),0,0,0); mini.addView(names,new LinearLayout.LayoutParams(0,-2,1));
        miniTitle=text("等待电脑播放",14,MusicUi.TEXT); oneLine(miniTitle); names.addView(miniTitle);
        miniArtist=text("连接电脑，开始听歌",11,MusicUi.MUTED); oneLine(miniArtist); names.addView(miniArtist);
        names.setOnClickListener(v->show("player")); miniCover.setOnClickListener(v->show("player"));
        icon(mini,"prev","上一首",()->command("prev"),48);
        miniPlay=icon(mini,"play","播放",()->command("toggle"),48);
        icon(mini,"next","下一首",()->command("next"),48);
        icon(mini,"queue","播放队列",()->{show("library");selectLibrary("queue");},48);
        tabs=row(shell); tabs.setPadding(dp(16),dp(4),dp(16),dp(4));
        show("player");
        String savedAddress=getPreferences(0).getString("address","");
        if(!savedAddress.isEmpty()) try { api=new RemoteApi(savedAddress);api.configureLibrary(this); } catch(Exception ignored) {}
        if(api==null) ui.post(this::connectionDialog);
    }
    private TextView text(String s,int size,int color) { return MusicUi.text(this,s,size,color); }
    private void oneLine(TextView v) { v.setSingleLine(true); v.setEllipsize(TextUtils.TruncateAt.END); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row(LinearLayout parent) { LinearLayout l=new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); parent.addView(l); return l; }
    private MusicUi.Icon icon(LinearLayout parent,String glyph,String label,Runnable action,int size) {
        MusicUi.Icon v=new MusicUi.Icon(this,glyph,label,action);
        android.graphics.drawable.Drawable background=new android.graphics.drawable.InsetDrawable(MusicUi.shape(MusicUi.SURFACE,40,this),dp(4));
        v.setBackground(new android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22008a65),background,null));
        parent.addView(v,new LinearLayout.LayoutParams(dp(size),dp(size))); return v;
    }
    private Button pill(LinearLayout parent,String s,Runnable action) {
        Button b=MusicUi.pill(this,s,action); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(48));
        p.setMargins(dp(4),dp(4),dp(4),dp(4)); parent.addView(b,p); return b;
    }
    private ImageView image(int radius) {
        ImageView i=new MusicUi.Cover(this,radius); i.setScaleType(ImageView.ScaleType.CENTER_CROP);
        i.setBackground(MusicUi.shape(0xffe4e9ec,radius,this)); i.setClipToOutline(true);
        i.setImageResource(R.drawable.er_ic_remote); i.setContentDescription("歌曲封面"); return i;
    }
    private void show(String destination) {
        screenEpoch++; page=destination; content.removeAllViews(); title=null; artist=null; cover=null; lyric=null; seek=null;
        time=null; remaining=null; volume=null; volumeLabel=null;modeButton=null;volumeDragging=false; play=null; favorite=null; results=null; query=null;songIndicators.clear();
        lyricPage=null;libraryScope=null;
        mini.setVisibility("player".equals(page)?View.GONE:View.VISIBLE);
        tabs.removeAllViews();
        nav("player","music","播放"); nav("discover","search","发现"); nav("library","heart","我的");nav("ktv","mic","KTV");
        if("player".equals(page)) playerScreen();
        else if("lyrics".equals(page)){
            lyricPage=new LyricPage(this,()->show("player"),this::overlay,t->command("seek","time",t));
            lyricPage.setKeepAwakeAction(this::toggleKeepAwake,keepAwake);
            content.addView(lyricPage,new LinearLayout.LayoutParams(-1,-1));
        }
        else if("discover".equals(page)) discoverScreen();
        else libraryScreen();
        renderState(); if(api!=null) tick();
    }
    private void nav(String destination,String glyph,String label) {
        LinearLayout item=column(); item.setGravity(Gravity.CENTER); item.setPadding(0,dp(2),0,dp(2));
        tabs.addView(item,new LinearLayout.LayoutParams(0,dp(64),1));
        Runnable navigate=()->{if("ktv".equals(destination)){if(api==null||!connected){message("请先连接电脑");return;}startActivity(new Intent(this,KtvActivity.class).putExtra("address",api.base));}else show(destination);};
        MusicUi.Icon symbol=new MusicUi.Icon(this,glyph,label,navigate);
        boolean selected=page.equals(destination)||("lyrics".equals(page)&&"player".equals(destination));
        symbol.color(selected?MusicUi.BLUE:MusicUi.MUTED);
        item.addView(symbol,new LinearLayout.LayoutParams(dp(40),dp(40)));
        TextView caption=text(label,11,selected?MusicUi.BLUE:MusicUi.MUTED); caption.setGravity(Gravity.CENTER);item.addView(caption);
        item.setContentDescription(label); item.setOnClickListener(v->navigate.run());
    }
    private LinearLayout scrollContent() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        content.addView(scroll,new LinearLayout.LayoutParams(-1,-1));
        LinearLayout body=column(); body.setPadding(dp(24),dp(8),dp(24),dp(20)); scroll.addView(body); return body;
    }
    private void playerScreen() {
        LinearLayout body=scrollContent();
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{0xffe7eeeb,MusicUi.BG});
        body.setBackground(gradient);
        TextView caption=text("正在播放 · 电脑输出",12,MusicUi.MUTED); caption.setGravity(Gravity.CENTER);
        body.addView(caption); gap(body,16);
        cover=image(28);
        cover.setContentDescription("点击封面查看歌词");cover.setOnClickListener(v->show("lyrics"));
        int side=Math.min(dp(184),getResources().getDisplayMetrics().widthPixels-dp(100));
        LinearLayout.LayoutParams art=new LinearLayout.LayoutParams(side,side); art.gravity=Gravity.CENTER_HORIZONTAL;
        body.addView(cover,art); gap(body,18);
        title=text("让音乐，连接生活",24,MusicUi.TEXT); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        title.setGravity(Gravity.CENTER); title.setMaxLines(2); title.setEllipsize(TextUtils.TruncateAt.END); body.addView(title);
        artist=text("连接电脑，选择你喜欢的歌",14,MusicUi.MUTED); artist.setGravity(Gravity.CENTER); body.addView(artist);
        gap(body,10);
        lyric=text("此刻的音乐，在这里同步",14,MusicUi.BLUE); lyric.setGravity(Gravity.CENTER); lyric.setMaxLines(2); body.addView(lyric);
        gap(body,10); seek=slider(body); seek.setMax(0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user) { if(user && time!=null)time.setText(clock(p)); }
            public void onStartTrackingTouch(SeekBar b){seekDragging=true;}
            public void onStopTrackingTouch(SeekBar b){seekDragging=false;command("seek","time",b.getProgress());}
        });
        LinearLayout times=row(body); time=text("00:00",11,MusicUi.MUTED); remaining=text("00:00",11,MusicUi.MUTED);
        times.addView(time,new LinearLayout.LayoutParams(0,-2,1)); times.addView(remaining);
        LinearLayout controls=row(body); controls.setGravity(Gravity.CENTER); controls.setPadding(0,dp(12),0,dp(12));
        icon(controls,"prev","上一首",()->command("prev"),64);
        Space spacer=new Space(this); controls.addView(spacer,new LinearLayout.LayoutParams(dp(16),1));
        play=icon(controls,"play","播放",()->command("toggle"),76);
        play.setBackground(MusicUi.shape(MusicUi.BLUE,40,this));
        controls.addView(new Space(this),new LinearLayout.LayoutParams(dp(16),1));
        icon(controls,"next","下一首",()->command("next"),64);
        LinearLayout actions=row(body); actions.setGravity(Gravity.CENTER);
        favorite=icon(actions,"heart","收藏当前歌曲",this::toggleFavorite,56);
        icon(actions,"plus","加入歌单",()->choosePlaylist(songId),56);
        icon(actions,"lyric","歌词和桌面歌词",this::lyricsDialog,56);
        icon(actions,"queue","播放队列",()->{show("library");selectLibrary("queue");},56);
        LinearLayout modes=row(body); modes.setGravity(Gravity.CENTER);
        modeButton=pill(modes,modeLabel(),this::cycleMode); pill(modes,"音频设置 ›",this::audioSettings);
        volumeLabel=text("音乐音量  "+latest.optInt("volume",50)+"%",12,MusicUi.MUTED);body.addView(volumeLabel);
        volume=slider(body);volume.setContentDescription("音乐软件音量");volume.setMax(100);volume.setProgress(latest.optInt("volume",50));
        volume.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){if(user){volumeLabel.setText(getString(R.string.er_music_volume,p));liveVolume.submit(p);}}
            public void onStartTrackingTouch(SeekBar b){volumeDragging=true;}
            public void onStopTrackingTouch(SeekBar b){liveVolume.submit(b.getProgress());volumeDragging=false;}
        });
    }
    private void gap(LinearLayout parent,int size) { parent.addView(new Space(this),new LinearLayout.LayoutParams(1,dp(size))); }
    private SeekBar slider(LinearLayout parent) { SeekBar b=new SeekBar(this); tint(b); parent.addView(b,new LinearLayout.LayoutParams(-1,dp(36))); return b; }
    private void tint(SeekBar b) { b.setProgressTintList(ColorStateList.valueOf(MusicUi.BLUE)); b.setThumbTintList(ColorStateList.valueOf(MusicUi.TEXT)); b.setProgressBackgroundTintList(ColorStateList.valueOf(0xffdce3e5)); }
    private void discoverScreen() {
        LinearLayout body=scrollContent();
        TextView heading=text("发现好音乐",27,MusicUi.TEXT); heading.setTypeface(Typeface.DEFAULT,Typeface.BOLD); body.addView(heading);
        TextView subtitle=text("搜索曲库 · 在电脑上播放",13,MusicUi.MUTED); body.addView(subtitle); gap(body,16);
        LinearLayout searchBox=row(body); searchBox.setBackground(MusicUi.shape(MusicUi.SURFACE,18,this));
        query=new EditText(this); query.setTextColor(MusicUi.TEXT); query.setHintTextColor(MusicUi.MUTED);
        query.setHint("歌名、歌手或专辑"); query.setSingleLine(true); query.setTextSize(15);
        query.setPadding(dp(16),0,0,0); query.setBackgroundColor(Color.TRANSPARENT); query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(query,new LinearLayout.LayoutParams(0,dp(52),1));
        icon(searchBox,"search","搜索歌曲",()->search(1),52);
        query.setOnEditorActionListener((v,id,event)->{if(id==EditorInfo.IME_ACTION_SEARCH){search(1);return true;}return false;});
        section=text("搜索你想听的歌",16,MusicUi.MUTED); section.setPadding(0,dp(22),0,dp(12)); body.addView(section);
        results=column(); body.addView(results); empty("从喜欢的旋律开始","搜索后点击歌曲，即可让电脑播放");
    }
    private void libraryScreen() {
        LinearLayout body=scrollContent();
        LinearLayout heading=row(body);
        TextView label=text("我的音乐",27,MusicUi.TEXT); label.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        heading.addView(label,new LinearLayout.LayoutParams(0,-2,1)); icon(heading,"plus","新建歌单",this::createPlaylist,48);
        libraryScope=text(api!=null&&api.localLibrary()?"收藏与歌单保存在此手机":"收藏与歌单，随时回到喜欢的声音",13,MusicUi.MUTED); body.addView(libraryScope); gap(body,16);
        LinearLayout filters=row(body);
        for(String key:new String[]{"favorites","playlists","queue"}) {
            String caption="favorites".equals(key)?"我喜欢":"playlists".equals(key)?"歌单":"播放队列";
            Button tab=MusicUi.pill(this,caption,()->selectLibrary(key));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1); p.setMargins(dp(3),0,dp(3),0);
            filters.addView(tab,p);
        }
        section=text("",16,MusicUi.TEXT); section.setPadding(0,dp(20),0,dp(12)); body.addView(section);
        results=column(); body.addView(results); selectLibrary(libraryTab);
    }
    private void empty(String heading,String detail) {
        if(results==null)return; results.removeAllViews();
        LinearLayout card=column(); card.setPadding(dp(20),dp(36),dp(20),dp(36)); card.setGravity(Gravity.CENTER);
        card.setBackground(MusicUi.shape(MusicUi.SURFACE,20,this)); results.addView(card);
        MusicUi.Icon icon=new MusicUi.Icon(this,"music","音乐",()->{}); icon.setClickable(false); icon.color(MusicUi.BLUE);
        card.addView(icon,new LinearLayout.LayoutParams(dp(56),dp(56)));
        TextView a=text(heading,17,MusicUi.TEXT); a.setGravity(Gravity.CENTER); card.addView(a);
        TextView b=text(detail,13,MusicUi.MUTED); b.setGravity(Gravity.CENTER); b.setPadding(0,dp(8),0,0); card.addView(b);
    }
    private void selectLibrary(String key) {
        libraryTab=key; selectedList="";
        if(!"library".equals(page))return;
        if("queue".equals(key)){queue(0);return;}
        section.setText("favorites".equals(key)?"我喜欢":"我的歌单");
        request(a->a.get("/api/library"),d->{
            if(!key.equals(libraryTab))return;
            updateFavorite(d);
            if("favorites".equals(key))songs(d.optJSONArray("favorites"),"playLibrary","favorites");
            else playlists(d.optJSONArray("playlists"));
        });
    }
    private void playlists(JSONArray lists) {
        results.removeAllViews(); if(lists==null || lists.length()==0){empty("还没有歌单","点击右上角 ＋ 创建你的第一张歌单");return;}
        for(int i=0;i<lists.length();i++)try{
            JSONObject p=lists.getJSONObject(i); String id=p.getString("id");
            LinearLayout line=listRow(); MusicUi.Icon badge=icon(line,"queue","歌单",()->openPlaylist(id),52); badge.color(MusicUi.BLUE);
            LinearLayout names=column(); names.setPadding(dp(12),0,dp(8),0); line.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            TextView name=text(p.optString("title"),17,MusicUi.TEXT); oneLine(name); names.addView(name);
            int count=p.optInt("songCount",p.optJSONArray("songs")==null?0:p.optJSONArray("songs").length());
            names.addView(text(count+" 首歌曲",12,MusicUi.MUTED)); names.setOnClickListener(v->openPlaylist(id));
            MusicUi.Icon more=icon(line,"more","歌单操作",()->{},48);
            more.setOnClickListener(v->{
                PopupMenu menu=new PopupMenu(this,v);
                menu.getMenu().add(api!=null&&api.localLibrary()?"播放首曲":"播放全部").setOnMenuItemClickListener(item->{command("playLibrary","playlistId",id);return true;});
                if(p.optBoolean("canDelete",true))menu.getMenu().add("删除歌单").setOnMenuItemClickListener(item->{
                    new AlertDialog.Builder(this).setMessage("删除歌单“"+p.optString("title")+"”？")
                        .setPositiveButton("删除",(d,w)->commandThen("deletePlaylist",()->selectLibrary("playlists"),"playlistId",id)).setNegativeButton("取消",null).show();return true;
                }); menu.show();
            });
        }catch(JSONException e){message(e.getMessage());}
    }
    private void openPlaylist(String id) {
        selectedList=id;
        request(a->a.get("/api/library/songs?playlistId="+URLEncoder.encode(id,"UTF-8")),d->{
            if(!id.equals(selectedList))return;
            section.setText(d.optString("title"));songs(d.optJSONArray("songs"),"playLibrary",id);
        });
    }
    private LinearLayout listRow() {
        LinearLayout line=row(results); line.setPadding(dp(6),dp(10),0,dp(10)); line.setMinimumHeight(dp(76)); return line;
    }
    private void songs(JSONArray songs,String action,String listId) {
        songIndicators.clear();
        if(results==null)return; results.removeAllViews();
        if(songs==null || songs.length()==0){empty("暂时没有歌曲","搜索喜欢的歌，收藏或加入歌单");return;}
        if("playLibrary".equals(action))pill(results,(api!=null&&api.localLibrary()?"播放首曲 · ":"播放全部 · ")+songs.length()+" 首",()->command("playLibrary","playlistId",listId));
        for(int i=0;i<songs.length();i++){
            JSONObject s=songs.optJSONObject(i); if(s==null)continue; String id=s.optString("id");
            LinearLayout line=listRow();
            TextView number=text(String.format(Locale.ROOT,"%02d",i+1),13,id.equals(songId)?MusicUi.BLUE:MusicUi.MUTED);
            number.setGravity(Gravity.CENTER); line.addView(number,new LinearLayout.LayoutParams(dp(36),dp(48)));
            LinearLayout names=column(); names.setPadding(dp(8),0,dp(8),0); line.addView(names,new LinearLayout.LayoutParams(0,-2,1));
            TextView name=text(s.optString("title"),16,id.equals(songId)?MusicUi.BLUE:MusicUi.TEXT); oneLine(name); names.addView(name);
            TextView by=text(s.optString("artist")+" · "+s.optString("album",""),12,MusicUi.MUTED); oneLine(by); names.addView(by);
            String detail=s.optString("artist")+" · "+s.optString("album","");
            Runnable indicator=()->{
                boolean current=!id.isEmpty()&&id.equals(songId);
                number.setText(current?(playing?"▥":"Ⅱ"):String.format(Locale.ROOT,"%02d",songsIndex(line)));
                number.setTextColor(current?MusicUi.BLUE:MusicUi.MUTED);name.setTextColor(current?MusicUi.BLUE:MusicUi.TEXT);
                by.setText(current?(playing?"正在播放 · ":"已暂停 · ")+detail:detail);
                by.setTextColor(current?MusicUi.BLUE:MusicUi.MUTED);
                line.setBackground(MusicUi.shape(current?0xffdcefe7:Color.TRANSPARENT,12,this));
                line.setContentDescription(s.optString("title")+(current?(playing?"，正在播放":"，已暂停"):""));
            };
            line.setTag(i+1);songIndicators.add(indicator);indicator.run();
            names.setMinimumHeight(dp(48)); names.setGravity(Gravity.CENTER_VERTICAL); names.setOnClickListener(v->{
                if("playLibrary".equals(action))command(action,"playlistId",listId,"songId",id);else command(action,"songId",id);
            });
            MusicUi.Icon more=icon(line,"more","歌曲操作："+s.optString("title"),()->{},48);
            more.setOnClickListener(v->{
                PopupMenu menu=new PopupMenu(this,v);
                menu.getMenu().add("收藏歌曲").setOnMenuItemClickListener(item->{command("favorite","songId",id,"favorite",true);return true;});
                menu.getMenu().add("加入歌单").setOnMenuItemClickListener(item->{choosePlaylist(id);return true;});
                if("searchPlay".equals(action))menu.getMenu().add("下一首播放").setOnMenuItemClickListener(item->{command("searchPlayNext","songId",id);return true;});
                if("playLibrary".equals(action))menu.getMenu().add("favorites".equals(listId)?"取消收藏":"从歌单移除").setOnMenuItemClickListener(item->{
                    if("favorites".equals(listId))commandThen("favorite",()->selectLibrary("favorites"),"songId",id,"favorite",false);
                    else commandThen("playlistRemove",()->openPlaylist(listId),"playlistId",listId,"songId",id);return true;
                });menu.show();
            });
        }
    }
    private void createPlaylist() { createPlaylist(""); }
    private void createPlaylist(String addSong) {
        EditText name=new EditText(this); name.setHint("给歌单取个名字"); name.setSingleLine(true);
        new AlertDialog.Builder(this).setTitle("新建歌单").setView(name).setPositiveButton("创建",
            (d,w)->request(a->a.command(RemoteApi.body("createPlaylist","title",name.getText().toString())),data->{
                if(!addSong.isEmpty()){
                    String id=data.optString("createdPlaylistId");
                    if(id.isEmpty()){message("歌单已创建，请从列表中选择加入");choosePlaylist(addSong);return;}
                    commandThen("playlistAdd",()->message("已加入新歌单"),"playlistId",id,"songId",addSong);
                }else{show("library");selectLibrary("playlists");}
            })).setNegativeButton("取消",null).show();
    }
    private void choosePlaylist(String id) {
        if(id.isEmpty()){message("请先播放一首歌");return;}
        request(a->a.get("/api/library"),d->{
            if(!d.optBoolean("loggedIn")){message("请先在电脑 EchoMusic 登录账号");return;}
            JSONArray lists=d.getJSONArray("playlists");String[] titles=new String[lists.length()],ids=new String[lists.length()];
            for(int i=0;i<lists.length();i++){JSONObject p=lists.getJSONObject(i);titles[i]=p.getString("title")+" · "+p.optInt("songCount")+" 首";ids[i]=p.getString("id");}
            AlertDialog.Builder chooser=new AlertDialog.Builder(this).setTitle("添加到电脑歌单")
                .setPositiveButton("＋ 新建歌单",(x,w)->createPlaylist(id)).setNegativeButton("取消",null);
            if(ids.length==0)chooser.setMessage("暂无可编辑歌单，可以新建后加入当前歌曲");
            else chooser.setItems(titles,(x,i)->commandThen("playlistAdd",()->message("已加入电脑歌单"),"playlistId",ids[i],"songId",id));
            chooser.show();
        });
    }
    private void queue(int offset) {
        section.setText("正在播放的队列");
        request(a->a.get("/api/queue?limit=100&offset="+offset),d->{
            if(!"queue".equals(libraryTab))return;
            songs(d.optJSONArray("songs"),"playQueueSong","");
            if(offset>0)pill(results,"上一页",()->queue(Math.max(0,offset-100)));
            if(d.optBoolean("hasMore"))pill(results,"下一页",()->queue(offset+100));
        });
    }
    private void search(int number) {
        if(query==null)return;
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
        if(keyboard!=null)keyboard.hideSoftInputFromWindow(query.getWindowToken(),0);
        query.clearFocus();content.setFocusableInTouchMode(true);content.requestFocus();
        String key=query.getText().toString().trim();if(key.isEmpty())return;
        section.setText("搜索中…");
        request(a->a.get("/api/search?keywords="+URLEncoder.encode(key,"UTF-8")+"&page="+number),d->{
            section.setText("搜索结果");songs(d.optJSONArray("songs"),"searchPlay","");
            if(number>1)pill(results,"上一页",()->search(number-1));
            if(d.optBoolean("hasMore"))pill(results,"下一页",()->search(number+1));
        });
    }
    private void connectionDialog() {
        LinearLayout box=column();box.setPadding(dp(24),dp(8),dp(24),dp(8));
        box.addView(text("手机与电脑连接同一 Wi-Fi",14,MusicUi.MUTED));
        EditText address=new EditText(this);address.setSingleLine(true);address.setTextColor(MusicUi.TEXT);address.setHintTextColor(MusicUi.MUTED);
        address.setHint("http://192.168.1.10:1987");address.setText(api==null?getPreferences(0).getString("address",""):api.base);box.addView(address);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("连接电脑").setView(box).setPositiveButton("连接",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{
                RemoteApi next=new RemoteApi(address.getText().toString());next.configureLibrary(this);connection.setText("连接中…");
                int epoch=++generation;io.execute(()->{
                    try{
                        JSONObject data=next.get("/api/state");
                        ui.post(()->{if(isDestroyed()||epoch!=generation)return;
                            stopService(new Intent(this,LyricService.class));stopService(new Intent(this,RemoteControlService.class));
                            api=next;latest=data;connected=true;lyrics=new JSONObject();lyricsFetched=0;coverKey="";artwork=null;
                            getPreferences(0).edit().putString("address",api.base).apply();dialog.dismiss();
                            renderState();enableNotification();refreshFavorite();tick();
                            if("library".equals(page))selectLibrary(libraryTab);
                        });
                    }catch(Exception e){ui.post(()->{if(!isDestroyed()&&epoch==generation){connection.setText("连接失败");message(e.getMessage());}});}
                });
            }catch(Exception e){message(e.getMessage());}
        }));dialog.show();
    }
    private void settings() {
        new AlertDialog.Builder(this).setTitle("遥控设置").setItems(new String[]{"连接 / 更换电脑","开启通知栏控制","关闭通知栏控制（电脑继续播放）","开启桌面歌词","关闭桌面歌词","暂停电脑并关闭遥控","电脑收藏同步说明",keepAwake?"屏幕常亮：已开启":"屏幕常亮：已关闭"},
            (d,i)->{switch(i){
                case 0:connectionDialog();break;case 1:enableNotification();break;
                case 2:stopService(new Intent(this,RemoteControlService.class));break;
                case 3:if(!LyricService.isRunning())overlay();break;case 4:desktopRequestedUntil=0;stopService(new Intent(this,LyricService.class));break;
                case 5:commandThen("pause",()->{stopService(new Intent(this,RemoteControlService.class));stopService(new Intent(this,LyricService.class));});break;
                case 6:libraryLocation();break;
                case 7:toggleKeepAwake();break;
            }}).show();
    }
    private void libraryLocation(){
        if(api==null){message("请先连接电脑");return;}
        new AlertDialog.Builder(this).setTitle("电脑收藏同步").setMessage("爱心直接同步电脑 EchoMusic 的我喜欢。请在电脑登录账号，并安装 1.7.0 完整插件；加号选择电脑歌单，也可以新建后加入。").setPositiveButton("知道了",null).show();
    }
    private int songsIndex(View row){return (Integer)row.getTag();}
    private String modeLabel(){
        switch(latest.optString("playMode","list")){case "sequential":return "顺序播放 ↻";case "random":return "随机播放 ↻";case "single":return "单曲循环 ↻";default:return "列表循环 ↻";}
    }
    private void cycleMode(){
        if(modePending)return;String[] modes={"sequential","list","random","single"};int index=0;
        for(int i=0;i<modes.length;i++)if(modes[i].equals(latest.optString("playMode","list")))index=i;
        String next=modes[(index+1)%modes.length];modePending=true;
        request(a->{try{return a.command(RemoteApi.body("playMode","mode",next));}finally{ui.post(()->modePending=false);}},d->{latest.put("playMode",next);renderState();tick();});
        if(api==null)modePending=false;
    }
    private void audioSettings(){
        LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(12));
        audioAction(box,"Windows 系统音量  ›","调节电脑默认输出设备总音量、静音",this::systemVolumeDialog);
        audioAction(box,"音乐输出设备  ›","为 EchoMusic 选择扬声器或耳机",this::outputDevices);
        TextView tip=text("音乐软件音量可在播放主页直接调节",12,MusicUi.MUTED);tip.setPadding(0,dp(16),0,0);box.addView(tip);
        showAudioDialog("电脑音频设置",box);
    }
    private void audioAction(LinearLayout box,String label,String description,Runnable action){
        Button button=MusicUi.pill(this,label,action);button.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);
        button.setBackgroundTintList(ColorStateList.valueOf(0xffdcefe7));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,dp(56));layout.topMargin=dp(12);box.addView(button,layout);
        TextView detail=text(description,12,MusicUi.MUTED);detail.setPadding(dp(4),dp(6),0,dp(6));box.addView(detail);
    }
    private AlertDialog showAudioDialog(String title,View box){
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(box).setNegativeButton("关闭",null).create();dialog.show();
        dialog.getWindow().setBackgroundDrawable(MusicUi.shape(MusicUi.SURFACE,20,this));return dialog;
    }
    private void systemVolumeDialog(){
        request(a->a.get("/api/system-volume"),data->{
            if(!data.optBoolean("supported")){message(data.optString("error","请更新电脑完整插件至 1.9.0"));return;}
            LinearLayout box=column();box.setPadding(dp(20),dp(8),dp(20),dp(16));
            TextView description=text("Windows 默认输出设备 · 影响电脑所有声音",12,MusicUi.MUTED);box.addView(description);
            TextView percent=text("",30,MusicUi.TEXT);percent.setGravity(Gravity.CENTER);box.addView(percent);
            SeekBar level=slider(box);level.setContentDescription("Windows 系统总音量");level.setMax(100);
            boolean[] active={true},dragging={false};JSONObject[] current={data};
            Button mute=MusicUi.pill(this,"静音",()->{});mute.setBackgroundTintList(ColorStateList.valueOf(0xffdcefe7));box.addView(mute,new LinearLayout.LayoutParams(-1,dp(52)));
            java.util.function.Consumer<JSONObject> render=state->{if(!active[0])return;current[0]=state;if(!dragging[0]){level.setProgress(state.optInt("volume"));percent.setText(getString(R.string.er_volume_percent,state.optInt("volume")));}mute.setText(state.optBoolean("muted")?"取消静音":"静音电脑");};
            java.util.function.Consumer<JSONObject> update=body->request(a->a.request("/api/system-volume",body),render::accept);
            LiveVolumeQueue systemVolume=new LiveVolumeQueue(value->{JSONObject result=api.request("/api/system-volume",new JSONObject().put("volume",value));ui.post(()->render.accept(result));},error->ui.post(()->{if(active[0])message(error.getMessage());}));
            level.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                public void onProgressChanged(SeekBar b,int value,boolean user){if(user){percent.setText(getString(R.string.er_volume_percent,value));systemVolume.submit(value);}}
                public void onStartTrackingTouch(SeekBar b){dragging[0]=true;}
                public void onStopTrackingTouch(SeekBar b){dragging[0]=false;systemVolume.submit(b.getProgress());}
            });
            mute.setOnClickListener(v->{try{update.accept(new JSONObject().put("muted",!current[0].optBoolean("muted")));}catch(JSONException ignored){}});
            render.accept(data);AlertDialog dialog=showAudioDialog("Windows 系统音量",box);
            Runnable refresh=new Runnable(){public void run(){if(!active[0]||!resumed)return;request(a->a.get("/api/system-volume"),render::accept);ui.postDelayed(this,2000);}};
            dialog.setOnDismissListener(d->{active[0]=false;systemVolume.close();ui.removeCallbacks(refresh);});ui.postDelayed(refresh,2000);
        });
    }
    private void outputDevices(){
        request(a->a.get("/api/audio"),d->{
            if(!d.optBoolean("outputSupported")){message("当前电脑版本不支持，请更新完整插件和 EchoMusic");return;}
            JSONArray devices=d.optJSONArray("devices");if(devices==null||devices.length()==0){message("暂无可用输出设备");return;}
            String[] labels=new String[devices.length()],ids=new String[devices.length()];int checked=-1;
            for(int i=0;i<devices.length();i++){JSONObject device=devices.getJSONObject(i);labels[i]=device.optString("title");ids[i]=device.optString("id");if(ids[i].equals(d.optString("selected")))checked=i;}
            new AlertDialog.Builder(this).setTitle("EchoMusic 输出设备").setSingleChoiceItems(labels,checked,(dialog,i)->{dialog.dismiss();commandThen("outputDevice",()->message("输出设备已切换"),"deviceId",ids[i]);}).setNegativeButton("取消",null).show();
        });
    }
    private void lyricsDialog() {
        toggleLyrics();
    }
    private void toggleLyrics(){show("lyrics".equals(page)?"player":"lyrics");}
    private void toggleKeepAwake(){
        keepAwake=!keepAwake;getPreferences(0).edit().putBoolean("keep_awake",keepAwake).apply();applyKeepAwake();
        if(lyricPage!=null)lyricPage.setKeepAwakeAction(this::toggleKeepAwake,keepAwake);
        message(keepAwake?"屏幕常亮已开启":"屏幕常亮已关闭");
    }
    private void applyKeepAwake(){
        if(keepAwake&&resumed)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private String lyricsText() {
        JSONArray lines=lyrics.optJSONArray("lines");if(lines==null||lines.length()==0)return "歌词正在同步，或此歌曲暂无歌词";
        StringBuilder s=new StringBuilder();for(int i=0;i<lines.length();i++){JSONObject l=lines.optJSONObject(i);if(l!=null)s.append(l.optString("text")).append('\n');}return s.toString();
    }
    private void enableNotification() {
        if(api==null || !connected){message("请先连接电脑");return;}
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingNotification=true;requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},11);return;
        }
        startForegroundService(new Intent(this,RemoteControlService.class).putExtra("address",api.base));
    }
    private void overlay() {
        if(LyricService.isRunning()||SystemClock.elapsedRealtime()<desktopRequestedUntil){
            desktopRequestedUntil=0;stopService(new Intent(this,LyricService.class));
            if(lyricPage!=null)lyricPage.desktopEnabled(false);return;
        }
        if(api==null || !connected){message("请先连接电脑");return;}
        if(!Settings.canDrawOverlays(this)){
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
            message("允许悬浮窗后返回，再点击开启");return;
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            pendingOverlay=true;requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},11);return;
        }
        desktopRequestedUntil=SystemClock.elapsedRealtime()+1500;
        startForegroundService(new Intent(this,LyricService.class).putExtra("address",api.base));
        if(lyricPage!=null)lyricPage.desktopEnabled(true);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==11){
            boolean granted=grants.length>0 && grants[0]==android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean notify=pendingNotification,overlay=pendingOverlay;pendingNotification=false;pendingOverlay=false;
            if(granted){if(notify)enableNotification();if(overlay)overlay();}
            else message("通知未授权，可继续在应用内遥控");
        }
    }
    private void toggleFavorite() {
        if(songId.isEmpty()){message("暂无正在播放的歌曲");return;}
        String clickedSong=songId;
        request(a->{
            JSONObject data=a.get("/api/library");
            boolean current=false;
            if(clickedSong.equals(data.optString("currentTrackId")))current=data.optBoolean("currentIsFavorite");
            else{
                JSONArray favorites=data.optJSONArray("favorites");
                if(favorites!=null)for(int i=0;i<favorites.length();i++){JSONObject song=favorites.optJSONObject(i);if(song!=null&&clickedSong.equals(song.optString("id")))current=true;}
            }
            return a.command(RemoteApi.body("favorite","songId",clickedSong,"favorite",!current));
        },data->{updateFavorite(data);refreshFavorite();tick();});
    }
    private void updateFavorite(JSONObject d) {
        if(libraryScope!=null)libraryScope.setText(d.optBoolean("localOnly")?"收藏与歌单保存在此手机":"收藏与歌单保存在电脑，多台手机共享");
        isFavorite=false;JSONArray list=d.optJSONArray("favorites");
        if(list!=null)for(int i=0;i<list.length();i++){JSONObject s=list.optJSONObject(i);if(s!=null && songId.equals(s.optString("id")))isFavorite=true;}
        if(d.optBoolean("nativeSync")&&songId.equals(d.optString("currentTrackId"))){
            isFavorite=d.optBoolean("currentIsFavorite");
            JSONObject pb=latest.optJSONObject("playback");
            if(pb!=null)try{pb.put("isFavorite",isFavorite).put("favoriteReady",true).put("favoriteSource","echomusic");}catch(JSONException ignored){}
        }
        if(favorite!=null){favorite.favorite(isFavorite);favorite.setContentDescription(isFavorite?"取消电脑收藏":"收藏到电脑的我喜欢");}
    }
    private void refreshFavorite(){if(api!=null)request(a->a.get("/api/library"),this::updateFavorite);}
    private void message(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    private void request(Request request,Result result){
        RemoteApi target=api;int epoch=generation,viewEpoch=screenEpoch;
        if(target==null){message("请先连接电脑");return;}
        io.execute(()->{
            try{JSONObject data=request.run(target);ui.post(()->{if(isDestroyed()||epoch!=generation||viewEpoch!=screenEpoch)return;try{result.accept(data);}catch(Exception e){message(e.getMessage());}});}
            catch(Exception e){ui.post(()->{if(!isDestroyed()&&epoch==generation&&viewEpoch==screenEpoch)message(e.getMessage());});}
        });
    }
    private void command(String action,Object... fields){commandThen(action,()->{},fields);}
    private void commandThen(String action,Runnable after,Object... fields){
        request(a->a.command(RemoteApi.body(action,fields)),d->{if(d.optBoolean("nativeSync"))updateFavorite(d);after.run();tick();});
    }
    interface Changed {void accept(int value);}
    private SeekBar.OnSeekBarChangeListener listener(Changed changed){return new SeekBar.OnSeekBarChangeListener(){
        public void onProgressChanged(SeekBar b,int p,boolean user){}public void onStartTrackingTouch(SeekBar b){}
        public void onStopTrackingTouch(SeekBar b){changed.accept(b.getProgress());}
    };}
    private final Runnable loop=()->{tick();if(resumed)ui.postDelayed(this.loopTask(),1000);};
    private Runnable loopTask(){return loop;}
    private void tick(){
        if(api==null||polling||!resumed)return;polling=true;RemoteApi target=api;int epoch=generation;
        io.execute(()->{
            try{
                JSONObject s=target.get("/api/state");JSONObject pb=s.optJSONObject("playback");
                String key=pb==null?"":pb.optString("trackId");
                JSONObject l=null;
                if(System.currentTimeMillis()-lyricsFetched>3000 || !key.equals(songId)){
                    try{l=target.get("/api/lyric");}catch(Exception missing){}
                }
                final JSONObject lyricData=l;
                ui.post(()->{if(isDestroyed()||epoch!=generation)return;
                    connected=true;latest=s;if(lyricData!=null){lyrics=lyricData;lyricsFetched=System.currentTimeMillis();}
                    boolean changed=!key.equals(songId);if(changed){artwork=null;coverKey="";isFavorite=false;if(lyricData==null)lyrics=new JSONObject();}
                    renderState();if(changed)refreshFavorite();loadCover();
                });
            }catch(Exception e){ui.post(()->{if(!isDestroyed()&&epoch==generation){connected=false;renderState();connection.setText("已断开 · 重连");if(lyric!=null)lyric.setText(R.string.er_connection_lost);}});}
            finally{ui.post(()->polling=false);}
        });
    }
    private void renderState(){
        JSONObject pb=latest.optJSONObject("playback");songId=pb==null?"":pb.optString("trackId");
        trackTitle=pb==null?"":pb.optString("title");trackArtist=pb==null?"":pb.optString("artist");
        playing=connected&&pb!=null&&pb.optBoolean("isPlaying");
        if(connected)connection.setText("● 已连接电脑");
        if(title!=null)title.setText(trackTitle.isEmpty()?"让音乐，连接生活":trackTitle);
        if(artist!=null)artist.setText(trackArtist.isEmpty()?"连接电脑，选择你喜欢的歌":trackArtist);
        miniTitle.setText(trackTitle.isEmpty()?"等待电脑播放":trackTitle);
        miniArtist.setText(trackArtist.isEmpty()?"点击打开播放器":trackArtist);
        if(play!=null)play.glyph(playing?"pause":"play",playing?"暂停":"播放");
        miniPlay.glyph(playing?"pause":"play",playing?"暂停":"播放");
        if(lyric!=null)lyric.setText(songId.isEmpty()?getString(R.string.er_lyric_waiting):RemoteApi.lyric(latest,lyrics));
        if(lyricPage!=null){lyricPage.update(latest,lyrics);lyricPage.desktopEnabled(LyricService.isRunning()||SystemClock.elapsedRealtime()<desktopRequestedUntil);}
        if(lyricsToggle!=null){lyricsToggle.color("lyrics".equals(page)?MusicUi.BLUE:MusicUi.TEXT);lyricsToggle.setContentDescription("lyrics".equals(page)?"关闭歌词页":"打开歌词页");}
        if(seek!=null&&!seekDragging){seek.setMax(pb==null?0:(int)pb.optDouble("duration"));seek.setProgress(pb==null?0:(int)pb.optDouble("currentTime"));}
        if(time!=null)time.setText(clock(pb==null?0:(int)pb.optDouble("currentTime")));
        if(remaining!=null)remaining.setText(clock(pb==null?0:(int)pb.optDouble("duration")));
        if(volume!=null&&!volumeDragging){volume.setProgress(latest.optInt("volume"));volumeLabel.setText(getString(R.string.er_music_volume,latest.optInt("volume")));}
        if(modeButton!=null){modeButton.setText(modeLabel());modeButton.setContentDescription(modeLabel()+"，点击切换播放模式");}
        for(Runnable indicator:songIndicators)indicator.run();
        if(pb!=null&&"echomusic".equals(pb.optString("favoriteSource"))&&pb.optBoolean("favoriteReady"))isFavorite=pb.optBoolean("isFavorite");
        if(favorite!=null){favorite.favorite(isFavorite);favorite.setContentDescription(isFavorite?"取消电脑收藏":"收藏到电脑的我喜欢");}
        if(artwork!=null){miniCover.setImageBitmap(artwork);if(cover!=null)cover.setImageBitmap(artwork);}
        else{miniCover.setImageResource(R.drawable.er_ic_remote);if(cover!=null)cover.setImageResource(R.drawable.er_ic_remote);}
    }
    private void loadCover(){
        if(api==null||songId.isEmpty()||loadingCover||songId.equals(coverKey))return;
        loadingCover=true;RemoteApi target=api;String key=songId;int epoch=generation;
        io.execute(()->{
            Bitmap bitmap=null;try{bitmap=target.cover();}catch(Exception ignored){}final Bitmap data=bitmap;
            ui.post(()->{loadingCover=false;if(isDestroyed()||epoch!=generation||!key.equals(songId))return;
                coverKey=key;artwork=data;renderState();
            });
        });
    }
    private String clock(int seconds){return String.format(Locale.ROOT,"%02d:%02d",Math.max(0,seconds)/60,Math.max(0,seconds)%60);}
    @Override protected void onResume(){super.onResume();resumed=true;applyKeepAwake();ui.post(loop);}
    @Override public void onBackPressed(){if("lyrics".equals(page))show("player");else super.onBackPressed();}
    @Override protected void onPause(){resumed=false;applyKeepAwake();ui.removeCallbacks(loop);super.onPause();}
    @Override protected void onDestroy(){liveVolume.close();ui.removeCallbacksAndMessages(null);io.shutdownNow();super.onDestroy();}
}
