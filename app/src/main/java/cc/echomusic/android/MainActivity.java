package cc.echomusic.android;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private static final int BG=BuildConfig.TV?0xffa5f2f3:0xfff3f5f7, WHITE=0xffffffff, INK=BuildConfig.TV?0xff102e35:0xff18252b, MUTED=BuildConfig.TV?0xff36575e:0xff718087, GREEN=BuildConfig.TV?0xff07515d:0xff138755, LINE=BuildConfig.TV?0xff78cdd1:0xffe5e9ec;
    private final ExecutorService io=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private MusicRepository repo;
    private PlaybackCache playbackCache;
    private String playlistFilter="",playlistFilterKey="",publicPlaylistQuery="";
    private List<HandheldCatalog.Entry> publicPlaylists=Collections.emptyList();
    private int feedBackPage=10;
    private PlaylistView playlistView;
    private boolean batchMode,playlistPageError;
    private final Set<Song> selectedTracks=Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<String> trackPageSignatures=new HashSet<>();
    private long playlistTotal=-1;
    private MusicDownloads downloads;private final Set<String> resolvingDownloads=new HashSet<>();private boolean activityStarted;
    private ArtworkLoader artworkLoader;private ImageView playerArtwork;private TextView artworkPlaceholder;
    private LoginDialog loginDialog;
    private VerificationDialog verificationDialog;
    private PlaybackService service;
    private boolean bound=false, tablet=false, expanded=false, busy=false, more=false, fullPlayer=false;
    private int page=0, request=0, resultPage=1;
    private boolean catalogRecommended,diskUploading,diskDeleting;
    private String catalogTab="playlists",catalogCategory="0",catalogLabel="全部",feedPath="";
    private List<HandheldCatalog.Entry> catalogEntries=Collections.emptyList();
    private MusicCloudDisk diskPage;
    private CloudLibrary.Session<AuthStore.Scope> diskUploadPending;
    private String query="", heading="", playlistId="", status="";
    private List<Song> tracks=new ArrayList<>();
    private LinearLayout root,body,content,playerPanel,mini;
    private TextView miniTitle,miniSubtitle,miniPlay,playerTitle,playerArtist,playerTime,playerPlay,playerLike,playerRepeat,playerStatus;
    private SeekBar progress;
    private Dialog playerDialog;
    private android.graphics.Bitmap pendingLoginQr;
    private LyricsPanel lyricsPanel;
    private TextView lyricPreview;
    private Song pendingLyricImport;
    private CloudProtocol.Playlist activeCloud;
    private boolean cloudFavoritesPage=false,cloudSyncing=false,cloudMutation=false;
    private int cloudSerial=0,cloudTrackSerial=0,cloudOperation=0;
    private String cloudMessage="";
    private long lastCloudResume=0;
    private TextView notice;private String favoriteTrackKey="";
    private final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){service=((PlaybackService.LocalBinder)b).service();bound=true;render();}
        public void onServiceDisconnected(ComponentName n){service=null;bound=false;}
    };
    private final BroadcastReceiver changed=new BroadcastReceiver(){public void onReceive(Context c,Intent i){updatePlayer();}};
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // Legacy registration only executes below Android 13.
    @Override public void onCreate(Bundle state){
        super.onCreate(state);if(BuildConfig.TV)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);repo=new MusicRepository(this);if(!BuildConfig.TV)playbackCache=PlaybackCache.get(this);downloads=BuildConfig.TV?null:new MusicDownloads(this);artworkLoader=new ArtworkLoader(this,repo,io);
        loginDialog=new LoginDialog(this,repo);verificationDialog=new VerificationDialog(this,loginDialog);lyricsPanel=new LyricsPanel(this,repo);
        if(state!=null){page=state.getInt("page");if(page==11||page==13||page==14)page=2;localAudioQuery=state.getString("localAudioQuery","");query=state.getString("query","");heading=state.getString("heading","");playlistId=state.getString("playlist","");resultPage=state.getInt("resultPage",1);feedPath=state.getString("feedPath","");more=state.getBoolean("more");if(page==12&&feedPath.isEmpty())page=2;try{tracks=MusicRepository.songs(new JSONArray(state.getString("tracks","[]")));}catch(Exception ignored){}}
        if(page==6||page==7||(BuildConfig.TV&&page==0))page=2;
        bindService(new Intent(this,PlaybackService.class),connection,BIND_AUTO_CREATE);
        IntentFilter f=new IntentFilter(PlaybackService.CHANGED);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(changed,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(changed,f);
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},23);
        render();SystemBars.apply(getWindow());
    }
    @Override public void onStart(){super.onStart();activityStarted=true;main.post(ticker);main.post(refreshDownloads);VerificationBroker.instance().attach(verificationDialog);loginDialog.foreground(true);lyricsPanel.foreground(true);}
    @Override public void onPostResume(){super.onPostResume();syncCloud(false);if(!getSharedPreferences("echo",0).getBoolean("login_prompted_v3",false)&&repo.auth.authorization().isEmpty()){getSharedPreferences("echo",0).edit().putBoolean("login_prompted_v3",true).apply();main.post(()->{if(!isFinishing()&&!isDestroyed())loginDialog.open(installed->onAccountChanged(),null);});}}
    @Override public void onStop(){activityStarted=false;main.removeCallbacks(ticker);main.removeCallbacks(refreshDownloads);if(recognitionDialog!=null)recognitionDialog.dismiss();lyricsPanel.foreground(false);loginDialog.foreground(false);VerificationBroker.instance().detach(verificationDialog);verificationDialog.pause();super.onStop();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("page",page);b.putString("localAudioQuery",localAudioQuery);b.putString("query",query);b.putString("heading",heading);b.putString("playlist",playlistId);b.putInt("resultPage",resultPage);b.putString("feedPath",feedPath);b.putBoolean("more",more);JSONArray a=new JSONArray();for(Song s:tracks)a.put(s.json());b.putString("tracks",a.toString());}
    @Override public void onConfigurationChanged(Configuration c){super.onConfigurationChanged(c);if(playerDialog!=null){playerDialog.dismiss();playerDialog=null;}fullPlayer=false;render();loginDialog.configurationChanged();verificationDialog.resize();lyricsPanel.resize();}
    private final Runnable ticker=new Runnable(){public void run(){updatePlayer();main.postDelayed(this,BuildConfig.TV?1500:700);}};
    @Override public void onDestroy(){VerificationBroker.instance().detach(verificationDialog);verificationDialog.close();loginDialog.close();lyricsPanel.close();hidePlaybackPeek.run();main.removeCallbacksAndMessages(null);++request;io.shutdownNow();unregisterReceiver(changed);if(bound)unbindService(connection);if(playerDialog!=null)playerDialog.dismiss();super.onDestroy();}
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    TextView text(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(BuildConfig.TV?Math.max(18,size):size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    TextView title(String s,int size){TextView t=text(s,size,INK);t.setTypeface(null,Typeface.BOLD);return t;}
    GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    void pad(View v,int n){v.setPadding(dp(n),dp(n),dp(n),dp(n));}
    TextView button(String label,Runnable action){TextView v=text(label,15,GREEN);v.setGravity(Gravity.CENTER);v.setMinHeight(dp(48));v.setPadding(dp(14),0,dp(14),0);v.setBackground(shape(BuildConfig.TV?0xffd7fafb:0xffffffff,14));v.setOnClickListener(w->action.run());return v;}
    void gap(LinearLayout l,int h){l.addView(new View(this),lp(1,h));}
    void line(LinearLayout l){View v=new View(this);v.setBackgroundColor(LINE);l.addView(v,lp(-1,1));}
    void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @android.annotation.TargetApi(21)
    void render(){
        if(lyricsPanel!=null&&lyricsPanel.isOpen())return;
        if(isFinishing())return;
        if(BuildConfig.TV){renderTv();return;}
        playlistView=null;
        if(!isPlaylistPage()){resetBatch();playlistPageError=false;}
        main.removeCallbacks(refreshDownloads);downloadList=null;
        if(page!=9)localAudioSongs=null;
        int width=getResources().getConfiguration().screenWidthDp;
        tablet=width>=600;expanded=false;
        miniTitle=null;playerTitle=null;progress=null;
        root=col();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{if(Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout()|WindowInsets.Type.ime());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);}else{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());}return insets;});
        setContentView(root);root.requestApplyInsets();
        body=row();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        
        content=col();pad(content,tablet?26:20);body.addView(content,new LinearLayout.LayoutParams(0,-1,1));
        if(page==0)home();else if(page==1)searchPage();else if(page==2)library();else if(page==3)settings();else if(page==6)cloudListsPage();else if(page==8)downloadsPage();else if(page==9)localAudioPage();else if(page==10)catalogPage();else if(page==14)publicPlaylistPage();else trackPage();
        if(expanded){playerPanel=col();pad(playerPanel,24);playerPanel.setBackgroundColor(0xffbff5f6);body.addView(playerPanel,lp(320,-1));buildPlayer(playerPanel,false);}
        else buildMini();
        root.addView(navigation(false),lp(-1,66));
        updatePlayer();
    }
    private ListView tvSongList;private boolean tvEnterSongs,tvRestoreSongs;private int tvSongPosition;
    void renderTv(){if(tvSongList!=null&&tvSongList.hasFocus()){tvRestoreSongs=true;tvSongPosition=Math.max(0,tvSongList.getSelectedItemPosition());}tvSongList=null;if(page!=7){tvEnterSongs=false;tvRestoreSongs=false;}String previousFocus=TvFocus.focusedLabel(root);miniTitle=null;playerTitle=null;progress=null;playerArtwork=null;root=col();root.setBackgroundColor(0xff132c3e);setContentView(root);body=row();root.addView(body,new LinearLayout.LayoutParams(-1,0,1));LinearLayout nav=col();pad(nav,16);nav.addView(text("echo. TV",28,0xffa5f2f3),lp(-1,68));nav.addView(button("云端音乐库",()->{page=2;render();syncCloud(false);}),lp(-1,60));gap(nav,12);nav.addView(button("搜索歌曲",()->{page=1;render();}),lp(-1,60));gap(nav,12);nav.addView(button("正在播放 / 歌词",this::openPlayer),lp(-1,60));gap(nav,12);nav.addView(button("局域网远控",this::remoteSettings),lp(-1,60));gap(nav,12);nav.addView(button("账号 / 设置",()->{page=3;render();}),lp(-1,60));body.addView(nav,lp(210,-1));content=col();pad(content,24);content.setBackgroundColor(0xffecf7f8);body.addView(content,new LinearLayout.LayoutParams(0,-1,1));if(page==1)searchPage();else if(page==3){header("电视设置","方向键移动 · 确认键选择 · 返回键退出当前层");if(BuildConfig.MGV2000)content.addView(button("设置音乐服务地址",this::advancedConnection),lp(-1,56));content.addView(button("扫码登录 / 手机验证码",()->loginDialog.open(installed->onAccountChanged(),null)),lp(-1,64));gap(content,16);content.addView(button("局域网远控设置",this::remoteSettings),lp(-1,64));gap(content,16);if(!repo.auth.authorization().isEmpty()){content.addView(button("退出账号",()->new AlertDialog.Builder(this).setTitle("退出当前账号？").setMessage("停止播放并退出云端账号，保留设备设置和播放记录。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{if(service!=null)service.stopPlayback();repo.auth.logout();onAccountChanged();render();}).show()),lp(-1,64));gap(content,16);}content.addView(text("音乐不落盘 · 仅保留一首歌词和封面缓存 · 辅助缓存上限 8MB",18,MUTED));gap(content,12);content.addView(text("电视优化版 · 无本地播放与下载入口",20,MUTED));}else if(page==7||page==4||page==5)trackPage();else tvLibrary();updatePlayer();TvFocus.install(root);TvFocus.restore(root,previousFocus);if(tvSongList!=null&&tvSongList.getCount()>0&&(tvEnterSongs||tvRestoreSongs)){final ListView target=tvSongList;final int at=tvEnterSongs?0:Math.min(tvSongPosition,target.getCount()-1);tvEnterSongs=false;tvRestoreSongs=false;target.post(()->{if(target==tvSongList&&target.isAttachedToWindow()){target.requestFocus();target.setSelection(at);}});}}
    void tvLibrary(){CloudLibrary.Snapshot<AuthStore.Scope> state=repo.cloud.snapshot();header("云端音乐库",cloudSyncing?"正在同步…":cloudMessage.isEmpty()?repo.auth.displayName():cloudMessage);if(!AuthProtocol.positiveId(state.session.userid)){content.addView(button("扫码登录云端账号",()->loginDialog.open(installed->onAccountChanged(),null)),lp(-1,64));return;}LinearLayout actions=row();actions.addView(button("我喜欢 · "+state.favorites.size()+" 首",this::openCloudFavorites));actions.addView(button("刷新云端",()->syncCloud(true)));actions.addView(button("选择收藏歌单",this::chooseCloudFavorites));content.addView(actions);gap(content,12);ListView list=new ListView(this);list.setDivider(null);List<CloudProtocol.Playlist> items=state.playlists;list.setAdapter(new BaseAdapter(){public int getCount(){return items.size();}public Object getItem(int position){return items.get(position);}public long getItemId(int position){return position;}public View getView(int position,View recycled,ViewGroup parent){CloudProtocol.Playlist playlist=items.get(position);TextView view=recycled instanceof TextView?(TextView)recycled:text("",22,INK);view.setText(getString(R.string.playlist_name_count,playlist.name,playlist.count));view.setMinHeight(dp(68));pad(view,14);return view;}});list.setOnItemClickListener((parent,v,position,id)->openCloudPlaylist(items.get(position)));content.addView(list,new LinearLayout.LayoutParams(-1,0,1));}
    View navigation(boolean rail){LinearLayout nav=rail?col():row();nav.setBackgroundColor(Color.WHITE);pad(nav,rail?12:4);if(rail){TextView logo=title("echo.",30);logo.setTextColor(GREEN);logo.setGravity(Gravity.CENTER);nav.addView(logo,lp(-1,86));}String[] names={"发现","远控","音乐库","设置"},icons={"home","remote","library","settings"};for(int i=0;i<4;i++){final int index=i;LinearLayout item=col();item.setGravity(Gravity.CENTER);MusicIconView icon=new MusicIconView(this,22);icon.setText(icons[i]);icon.setTextColor((page==i&&i!=1)?GREEN:MUTED);icon.setContentDescription(names[i]);item.addView(icon,lp(28,28));TextView label=text(names[i],rail?13:11,(page==i&&i!=1)?GREEN:MUTED);label.setGravity(Gravity.CENTER);item.addView(label,lp(-1,20));if(page==i&&i!=1)item.setBackground(shape(0xffe2f4eb,14));item.setOnClickListener(v->{if(index==1){startActivity(new Intent().setClassName(this,"com.echo.remote.MainActivity"));return;}++request;busy=false;page=index;heading="";playlistId="";status="";tracks=new ArrayList<>();activeCloud=null;cloudFavoritesPage=false;render();if(index==2)syncCloud(false);});if(rail){nav.addView(item,lp(-1,64));gap(nav,8);}else nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));}return nav;}
    void header(String name,String sub){content.addView(title(name,30));gap(content,8);content.addView(text(sub,13,MUTED));gap(content,24);}
    void home(){if(!BuildConfig.TV){HandheldHome.render(this,content,repo,artworkLoader,this::openTouchSearch,()->{page=2;render();syncCloud(false);},()->{page=8;render();});return;}
        header("让音乐，自然发生","E C H O M U S I C   /   ANDROID");
        ScrollView scroll=new ScrollView(this);content.addView(scroll,lp(-1,-1));LinearLayout inner=col();scroll.addView(inner);
        if(repo.auth.authorization().isEmpty()){inner.addView(button("扫码登录 · 开始听歌",()->loginDialog.open(installed->onAccountChanged(),null)),lp(-1,54));gap(inner,16);}
        LinearLayout hero=col();pad(hero,24);hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff17304c,0xff385873}));
        hero.addView(text("YOUR DAILY SOUNDTRACK",12,0xffc8dfdf));gap(hero,18);TextView heroTitle=title("听见此刻\n喜欢的声音",30);heroTitle.setTextColor(Color.WHITE);hero.addView(heroTitle);gap(hero,10);hero.addView(text("从一首歌，开始今天的好心情。",14,0xffd2dce7));gap(hero,24);
        hero.addView(button("探索音乐  →",()->{page=1;render();}),lp(-2,48));inner.addView(hero,lp(-1,-2));gap(inner,24);
        LinearLayout shortcuts=row();shortcuts.addView(button("↓ 本地下载",()->{page=8;render();}),new LinearLayout.LayoutParams(0,dp(56),1));TextView fav=button("♡ 我的收藏",this::openCloudFavorites);LinearLayout.LayoutParams f=new LinearLayout.LayoutParams(0,dp(56),1);f.leftMargin=dp(12);shortcuts.addView(fav,f);inner.addView(shortcuts);gap(inner,24);
        inner.addView(title("继续聆听",20));gap(inner,12);
        List<Song> history=repo.saved("history");
        if(history.isEmpty()){TextView empty=text("还没有播放记录。导入音乐，或在搜索页寻找喜欢的歌。",15,MUTED);pad(empty,16);inner.addView(empty);}
        else for(int i=0;i<Math.min(6,history.size());i++){final int at=i;inner.addView(songRow(history.get(i),i,()->play(history,at)),lp(-1,76));}
        gap(inner,16);inner.addView(button("打开在线歌单",this::askPlaylist));
    }
    private boolean searchKeyboardPending,hotLoading;private java.util.List<String> hotWords=new ArrayList<>();
    void openTouchSearch(){++request;busy=false;more=false;query="";tracks=new ArrayList<>();status="";page=1;searchKeyboardPending=true;render();}
    private void rememberSearch(String word){JSONArray old;try{old=new JSONArray(getSharedPreferences("echo",0).getString("search_history_v1","[]"));}catch(Exception e){old=new JSONArray();}JSONArray next=new JSONArray();next.put(word);for(int i=0;i<old.length()&&next.length()<12;i++){String saved=old.optString(i);if(!word.equalsIgnoreCase(saved)&&!saved.isEmpty())next.put(saved);}getSharedPreferences("echo",0).edit().putString("search_history_v1",next.toString()).apply();}
    private void keywordChips(LinearLayout parent,java.util.List<String> words,EditText input,Runnable run){SearchChips chips=new SearchChips(this);for(String word:words){TextView chip=text(word,13,INK);chip.setSingleLine();chip.setEllipsize(android.text.TextUtils.TruncateAt.END);chip.setPadding(dp(14),0,dp(14),0);chip.setBackground(shape(Color.WHITE,18));chip.setOnClickListener(v->{input.setText(word);run.run();});chips.addView(chip,new ViewGroup.LayoutParams(-2,dp(36)));}parent.addView(chips,lp(-1,-2));}
    private void hotTerms(Object data,LinkedHashSet<String> terms,int depth){if(depth>7||terms.size()>=12)return;if(data instanceof JSONObject){JSONObject object=(JSONObject)data;String word=object.optString("keyword","").trim();if(!word.isEmpty()&&word.length()<80)terms.add(word);java.util.Iterator<String> keys=object.keys();while(keys.hasNext())hotTerms(object.opt(keys.next()),terms,depth+1);}else if(data instanceof JSONArray){JSONArray array=(JSONArray)data;for(int i=0;i<array.length();i++)hotTerms(array.opt(i),terms,depth+1);}}
    private void searchSuggestions(EditText input,Runnable run){ScrollView scroll=new ScrollView(this);scroll.setVerticalScrollBarEnabled(false);content.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));LinearLayout suggestions=col();scroll.addView(suggestions);LinearLayout historyHeading=row();historyHeading.addView(title("搜索历史",18),new LinearLayout.LayoutParams(0,dp(44),1));historyHeading.addView(button("清空",()->{getSharedPreferences("echo",0).edit().remove("search_history_v1").apply();render();}));suggestions.addView(historyHeading);gap(suggestions,8);List<String> history=new ArrayList<>();try{JSONArray saved=new JSONArray(getSharedPreferences("echo",0).getString("search_history_v1","[]"));for(int i=0;i<saved.length();i++)history.add(saved.optString(i));}catch(Exception ignored){}if(history.isEmpty())suggestions.addView(text("还没有搜索记录",13,MUTED),lp(-1,36));else keywordChips(suggestions,history,input,run);gap(suggestions,24);suggestions.addView(title("热门搜索",18));gap(suggestions,14);LinearLayout hot=col();suggestions.addView(hot);if(hotWords.isEmpty())try{JSONArray cache=new JSONArray(getSharedPreferences("echo",0).getString("hot_words_v1","[]"));for(int i=0;i<cache.length();i++)hotWords.add(cache.optString(i));}catch(Exception ignored){}if(!hotWords.isEmpty())keywordChips(hot,hotWords,input,run);else hot.addView(text("正在获取热门搜索…",13,MUTED));if(!hotLoading&&(hotWords.isEmpty()||System.currentTimeMillis()-getSharedPreferences("echo",0).getLong("hot_time_v1",0)>600000)){hotLoading=true;io.execute(()->{try{LinkedHashSet<String> words=new LinkedHashSet<>();hotTerms(repo.get("/search/hot",MusicRepository.params()),words,0);List<String> loaded=new ArrayList<>(words);main.post(()->{hotLoading=false;if(loaded.isEmpty())return;hotWords=loaded;getSharedPreferences("echo",0).edit().putString("hot_words_v1",new JSONArray(loaded).toString()).putLong("hot_time_v1",System.currentTimeMillis()).apply();if(page==1&&hot.isAttachedToWindow()){hot.removeAllViews();keywordChips(hot,hotWords,input,run);}});}catch(Exception e){main.post(()->{hotLoading=false;if(page==1&&hot.isAttachedToWindow()&&hotWords.isEmpty()){hot.removeAllViews();hot.addView(text("热门搜索暂不可用，请稍后重试",13,MUTED));}});}});}}
    void searchPage(){
        header("搜索","搜索歌曲与歌手，找到你的下一首循环。");
        LinearLayout search=row();EditText input=new EditText(this);if(!BuildConfig.TV){search.setBackground(shape(0xffe6eaee,24));input.setBackgroundColor(Color.TRANSPARENT);input.setPadding(dp(16),0,dp(8),0);}input.setSingleLine();input.setText(query);input.setTextSize(16);input.setHint("歌曲、歌手");input.setTextColor(INK);input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);search.addView(input,new LinearLayout.LayoutParams(0,dp(52),1));
        Runnable run=()->{query=input.getText().toString().trim();if(query.isEmpty())return;if(!BuildConfig.TV)rememberSearch(query);hideKeyboard(input);tracks=new ArrayList<>();resultPage=1;fetch(false);};
        input.setOnEditorActionListener((v,a,event)->{if(a==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH){run.run();return true;}return false;});if(BuildConfig.TV)search.addView(button("搜索",run),lp(76,48));
        else{
            search.addView(touchControl("search","搜索",run),lp(46,48));
            TextView recognize=button("识曲",()->{hideKeyboard(input);recognizeSong();});
            search.addView(recognize,lp(62,48));
        }content.addView(search);if(BuildConfig.MGV2000)content.addView(button("遥控器键盘",()->Mgv2000Input.open(this,input,false)),lp(-1,48));gap(content,12);if(!BuildConfig.TV&&searchKeyboardPending){searchKeyboardPending=false;input.requestFocus();content.setAlpha(0);content.setTranslationY(dp(8));content.animate().alpha(1).translationY(0).setDuration(180).start();input.postDelayed(()->{if(page==1&&input.isAttachedToWindow()){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);}},140);}
        if(!BuildConfig.TV)content.addView(button("搜索公开歌单",()->openPlaylistSearch(input.getText().toString().trim())),lp(-1,44));
        notice=text(busy?"正在搜索…":status,14,MUTED);content.addView(notice,lp(-1,40));
        if(tracks.isEmpty()&&!busy&&!BuildConfig.TV){searchSuggestions(input,run);}else if(tracks.isEmpty()&&!busy){TextView hint=text("输入关键词，开始发现音乐。",16,MUTED);pad(hint,20);content.addView(hint);if(repo.endpoint().isEmpty())content.addView(button("打开设置",()->{page=3;render();}));}
        else listTracks();
    }
    void fetch(boolean append){
        if(!BuildConfig.TV&&page==4){loadPagedTracks(append);return;}
        final int ticket=++request; final int expectedPage=page; final String q=query,id=playlistId;final int p=resultPage;
        busy=true;status=append?"正在加载下一页…":"正在加载…";render();
        io.execute(()->{try{List<Song> result=expectedPage==4?repo.playlist(id,p):repo.search(q,p);main.post(()->{if(ticket!=request||isDestroyed())return;busy=false;if(append)tracks.addAll(result);else tracks=result;more=result.size()==30;status=result.isEmpty()?(append?"已加载全部歌曲":"没有找到歌曲"):"已显示 "+tracks.size()+" 首";render();});}catch(Exception e){main.post(()->{if(ticket!=request||isDestroyed())return;busy=false;if(append)resultPage=Math.max(1,p-1);status=e.getMessage();render();});}});
    }
    void library(){if(!BuildConfig.TV){HandheldHome.library(this,content,repo,artworkLoader,()->loginDialog.open(installed->onAccountChanged(),null),()->syncCloud(true),this::createCloudPlaylist,()->{page=8;render();});return;}
        header("音乐库","云端歌单与收藏，和当前账号保持一致。");ScrollView scroll=new ScrollView(this);content.addView(scroll,lp(-1,-1));LinearLayout list=col();scroll.addView(list);
        CloudLibrary.Snapshot<AuthStore.Scope> state=repo.cloud.snapshot();
        if(AuthProtocol.positiveId(state.session.userid)){
            list.addView(text(repo.auth.displayName(),16,INK));gap(list,8);list.addView(text(cloudSyncing?"正在更新云端…":cloudMessage.isEmpty()?(state.updatedAt>0?"显示账号缓存，可刷新获取最新内容":"尚未同步云端"):cloudMessage,13,MUTED));gap(list,10);
            list.addView(button("♥ 云端我喜欢    "+state.favorites.size()+" 首  →",this::openCloudFavorites),lp(-1,56));gap(list,10);
            list.addView(title("云端歌单",18));gap(list,8);for(CloudProtocol.Playlist playlist:state.playlists){list.addView(button((playlist.name.isEmpty()?"云端歌单":playlist.name)+"    "+playlist.count+" 首  →",()->openCloudPlaylist(playlist)),lp(-1,54));gap(list,6);}
            LinearLayout actions=row();actions.addView(button("刷新云端",()->syncCloud(true)),new LinearLayout.LayoutParams(0,dp(48),1));actions.addView(button("新建歌单",this::createCloudPlaylist),new LinearLayout.LayoutParams(0,dp(48),1));list.addView(actions);list.addView(button("选择收藏歌单",this::chooseCloudFavorites),lp(-1,48));gap(list,14);

        }else{list.addView(button("登录以同步云端歌单与收藏",()->loginDialog.open(installed->onAccountChanged(),null)),lp(-1,56));gap(list,16);}
        if(!BuildConfig.TV){line(list);gap(list,16);list.addView(title("本地",18));gap(list,10);list.addView(button("↓ 本地下载",()->{page=8;render();}),lp(-1,56));gap(list,10);list.addView(button("最近播放    "+repo.saved("history").size()+" 首 →",()->openSaved("history","最近播放")),lp(-1,56));}

    }
    void cloudListsPage(){
        CloudLibrary.Snapshot<AuthStore.Scope> state=repo.cloud.snapshot();header("我的云端歌单",cloudSyncing?"正在同步…":cloudMessage.isEmpty()?"自建歌单与收藏歌单":cloudMessage);
        LinearLayout actions=row();actions.addView(button("← 音乐库",()->{page=2;render();}));actions.addView(button("刷新",()->syncCloud(true)));actions.addView(button("新建歌单",this::createCloudPlaylist));content.addView(actions);gap(content,12);
        List<CloudProtocol.Playlist> lists=state.playlists;
        ListView view=new ListView(this);view.setDivider(null);view.setAdapter(new BaseAdapter(){public int getCount(){return lists.size();}public Object getItem(int p){return lists.get(p);}public long getItemId(int p){return p;}public View getView(int p,View recycled,ViewGroup parent){CloudProtocol.Playlist playlist=lists.get(p);LinearLayout item=col();pad(item,14);item.addView(title(playlist.name.isEmpty()?"云端歌单":playlist.name,18));gap(item,6);item.addView(text((playlist.owned(state.session.userid)?"自建":"收藏")+" · "+playlist.count+" 首",13,MUTED));return item;}});view.setOnItemClickListener((parent,v,position,id)->openCloudPlaylist(lists.get(position)));content.addView(view,new LinearLayout.LayoutParams(-1,0,1));if(lists.isEmpty()&&!cloudSyncing)content.addView(text("暂无云端歌单，可刷新或新建。",15,MUTED));
    }
    void syncCloud(boolean force){
        CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();if(!AuthProtocol.positiveId(expected.userid))return;if(cloudSyncing)return;
        long now=SystemClock.elapsedRealtime();if(!force&&now-lastCloudResume<30000&&repo.cloud.snapshot().favoritesReady)return;lastCloudResume=now;cloudSyncing=true;int serial=++cloudSerial;cloudMessage="正在同步云端…";
        io.execute(()->{try{try{repo.cloud.restore(expected);}catch(Exception ignored){}main.post(()->{if(serial==cloudSerial&&repo.auth.current(expected.scope)&&(page==2||page==6||(!BuildConfig.TV&&page==0)))render();});CloudLibrary.Snapshot<AuthStore.Scope> result=repo.cloud.refresh(expected);main.post(()->{if(serial!=cloudSerial||!repo.auth.current(expected.scope)||isDestroyed())return;cloudSyncing=false;cloudMessage=result.favoritesReady?"已与云端更新":"歌单已更新，未识别到账号默认收藏";if(page==7&&cloudFavoritesPage){tracks=cloudDisplay(result.favorites);busy=false;}if(page==2||page==6||(!BuildConfig.TV&&page==0)||(page==7&&cloudFavoritesPage))render();updatePlayer();});}catch(Exception error){main.post(()->{if(serial!=cloudSerial||!repo.auth.current(expected.scope)||isDestroyed())return;cloudSyncing=false;cloudMessage="同步未完成："+error.getMessage();if(page==7&&cloudFavoritesPage){busy=false;status=cloudMessage;}if(page==2||page==6||(!BuildConfig.TV&&page==0)||(page==7&&cloudFavoritesPage))render();});}});
    }
    String cloudOrderKey(){return "cloud_reverse_v1_"+repo.cloud.session().key+"_"+(cloudFavoritesPage?"favorites":activeCloud==null?"":activeCloud.id);}
    List<Song> cloudDisplay(List<Song> songs){List<Song> display=new ArrayList<>(songs);if(getPreferences(0).getBoolean(cloudOrderKey(),true))Collections.reverse(display);return display;}
    void toggleCloudOrder(){boolean reverse=getPreferences(0).getBoolean(cloudOrderKey(),true);getPreferences(0).edit().putBoolean(cloudOrderKey(),!reverse).apply();Collections.reverse(tracks);render();}
    void openCloudFavorites(){playlistFilter="";resetBatch();if(BuildConfig.TV)tvEnterSongs=true;if(!AuthProtocol.positiveId(repo.cloud.session().userid)){toast("请先登录云端账号");loginDialog.open(installed->onAccountChanged(),null);return;}++request;++cloudTrackSerial;page=7;cloudFavoritesPage=true;activeCloud=null;heading="云端我喜欢";tracks=cloudDisplay(repo.cloud.snapshot().favorites);more=false;busy=cloudSyncing;status=cloudMessage;render();syncCloud(true);}
    void openCloudPlaylist(CloudProtocol.Playlist playlist){playlistFilter="";resetBatch();if(BuildConfig.TV)tvEnterSongs=true;++request;page=7;cloudFavoritesPage=false;activeCloud=playlist;heading=playlist.name;tracks=new ArrayList<>();more=false;loadCloudPlaylist();}
    void loadCloudPlaylist(){
        if(activeCloud==null)return;CloudProtocol.Playlist target=activeCloud;CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();int serial=++cloudTrackSerial;busy=true;status="正在读取完整云端歌单…";render();
        io.execute(()->{try{List<Song> cached=repo.cloud.cachedPlaylist(target.id);main.post(()->{if(serial==cloudTrackSerial&&repo.auth.current(expected.scope)&&page==7&&!cloudFavoritesPage&&activeCloud==target&&tracks.isEmpty()&&!cached.isEmpty()){tracks=cloudDisplay(cached);render();}});List<Song> loaded=repo.cloud.loadPlaylist(expected,target.id);main.post(()->{if(serial!=cloudTrackSerial||!repo.auth.current(expected.scope)||page!=7||activeCloud!=target||isDestroyed())return;tracks=cloudDisplay(loaded);busy=false;status="已读取完整云端歌单 · "+loaded.size()+" 首";render();});}catch(Exception error){main.post(()->{if(serial!=cloudTrackSerial||!repo.auth.current(expected.scope)||page!=7||activeCloud!=target||isDestroyed())return;busy=false;status="读取未完成："+error.getMessage();render();});}});
    }
    interface CloudAction{String run(CloudLibrary.Session<AuthStore.Scope> session)throws Exception;}
    void cloudAction(CloudAction action){cloudAction(repo.cloud.session(),action);}
    void cloudAction(CloudLibrary.Session<AuthStore.Scope> expected,CloudAction action){
        if(!AuthProtocol.positiveId(expected.userid)){toast("请先登录云端账号");loginDialog.open(installed->onAccountChanged(),null);return;}if(!repo.auth.current(expected.scope)){toast("账号已变化，请重新操作");return;}if(cloudMutation){toast("正在同步上一项操作，请稍候");return;}cloudMutation=true;int operation=++cloudOperation;updatePlayer();toast("正在同步云端…");
        io.execute(()->{try{if(!repo.auth.current(expected.scope))throw new java.io.IOException("账号已变化");String result=action.run(expected);main.post(()->{if(operation!=cloudOperation||!repo.auth.current(expected.scope)||isDestroyed())return;cloudMutation=false;toast(result);cloudMessage="云端操作已确认";if(page==7){if(cloudFavoritesPage){tracks=cloudDisplay(repo.cloud.snapshot().favorites);status=cloudMessage;render();}else loadCloudPlaylist();}else if(page==2||page==6)render();updatePlayer();syncCloud(true);});}catch(Exception error){main.post(()->{if(operation!=cloudOperation||!repo.auth.current(expected.scope)||isDestroyed())return;cloudMutation=false;cloudMessage=error.getMessage();toast(cloudMessage);updatePlayer();if(page==2||page==6)render();});}});
    }
    void toggleSongFavorite(Song song){if(!repo.cloud.snapshot().favoritesReady){syncCloud(true);toast("正在确认云端收藏，请同步完成后再操作");return;}if(!song.cloudIdentified()){toast("此本地文件没有云端歌曲标识，无法同步收藏");return;}Song remote=song.cloudSong();cloudAction(expected->{boolean added=repo.cloud.toggleFavorite(expected,remote);return added?"已确认云端收藏":"已确认取消云端收藏";});}
    void chooseCloudFavorites(){CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();List<CloudProtocol.Playlist> options=new ArrayList<>();for(CloudProtocol.Playlist p:repo.cloud.snapshot().playlists)if(p.source!=2&&(p.owner.isEmpty()||p.owner.equals(expected.userid)))options.add(p);if(options.isEmpty()){toast("请先刷新云端获取歌单");return;}String[] names=new String[options.size()];for(int i=0;i<names.length;i++)names[i]=options.get(i).name+" · "+options.get(i).count+" 首";new AlertDialog.Builder(this).setTitle("选择用于爱心收藏的账号歌单").setItems(names,(d,which)->new AlertDialog.Builder(this).setTitle("使用此歌单作为收藏？").setMessage(options.get(which).name+"：之后爱心操作会添加或移除此歌单中的歌曲。").setNegativeButton("取消",null).setPositiveButton("使用",(a,b)->cloudAction(expected,session->{repo.cloud.selectFavorites(session,options.get(which).id);return "收藏歌单已设置";})).show()).setNegativeButton("取消",null).show();}
    void createCloudPlaylist(){CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();if(!AuthProtocol.positiveId(expected.userid)){toast("请先登录云端账号");return;}EditText name=new EditText(this);name.setHint("歌单名称");new AlertDialog.Builder(this).setTitle("新建云端歌单").setView(name).setNegativeButton("取消",null).setPositiveButton("创建",(d,w)->cloudAction(expected,session->{repo.cloud.create(session,name.getText().toString());return "云端歌单已创建并刷新";})).show();}
    void addToCloudPlaylist(Song song,CloudLibrary.Session<AuthStore.Scope> expected){
        if(!AuthProtocol.positiveId(expected.userid)){toast("请先登录云端账号");return;}io.execute(()->{try{CloudLibrary.Snapshot<AuthStore.Scope> state=repo.cloud.refresh(expected);List<CloudProtocol.Playlist> owned=new ArrayList<>();for(CloudProtocol.Playlist playlist:state.playlists)if(playlist.owned(expected.userid))owned.add(playlist);main.post(()->{if(!repo.auth.current(expected.scope)||isDestroyed())return;if(owned.isEmpty()){toast("没有可编辑的云端歌单，请先在音乐库新建");return;}String[] names=new String[owned.size()];for(int i=0;i<owned.size();i++)names[i]=owned.get(i).name;new AlertDialog.Builder(this).setTitle("添加到云端歌单").setItems(names,(d,which)->cloudAction(expected,session->{repo.cloud.add(session,owned.get(which).id,song);return "已确认加入云端歌单";})).setNegativeButton("取消",null).show();});}catch(Exception error){main.post(()->{if(repo.auth.current(expected.scope)&&!isDestroyed())toast(error.getMessage());});}});
    }
    private void addToCloudPlaylist(List<Song> selected,CloudLibrary.Session<AuthStore.Scope> expected){
        List<CloudProtocol.Playlist> owned=new ArrayList<>();
        for(CloudProtocol.Playlist playlist:repo.cloud.snapshot().playlists)
            if(playlist.owned(expected.userid))owned.add(playlist);
        if(owned.isEmpty()){toast("请在音乐库刷新或新建自建歌单");return;}
        String[] names=new String[owned.size()];
        for(int i=0;i<names.length;i++)names[i]=owned.get(i).name;
        new AlertDialog.Builder(this).setTitle("添加到我的歌单").setItems(names,(dialog,which)->
            cloudAction(expected,session->{
                int count=repo.cloud.addAll(session,owned.get(which).id,selected);
                return count==0?"所选歌曲已在此歌单":"已确认添加 "+count+" 首歌曲";
            })).setNegativeButton("取消",null).show();
    }
    void importOldFavorites(){CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();List<Song> incoming=new ArrayList<>();for(Song song:repo.saved("favorites"))if(!song.local()&&!repo.cloud.snapshot().favorite(song))incoming.add(song);if(incoming.isEmpty()){toast("没有需要导入的旧版本在线收藏");return;}StringBuilder preview=new StringBuilder("将 "+incoming.size()+" 首旧版本在线收藏导入当前账号“"+repo.auth.displayName()+"”。本地音频不会上传。\n");for(int i=0;i<Math.min(8,incoming.size());i++)preview.append("\n").append(incoming.get(i).title);new AlertDialog.Builder(this).setTitle("导入设备收藏到云端").setMessage(preview).setNegativeButton("取消",null).setPositiveButton("导入",(d,w)->cloudAction(expected,session->{int count=repo.cloud.importFavorites(session,incoming);return "已确认导入 "+count+" 首收藏";})).show();}
    void openSaved(String key,String title){playlistFilter="";resetBatch();activeCloud=null;cloudFavoritesPage=false;++cloudTrackSerial;++request;busy=false;page=5;heading=title;playlistId="";tracks=repo.saved(key);status="";more=false;render();}
    void openCatalog(boolean recommended){
        catalogRecommended=recommended;
        catalogCategory="0";
        catalogLabel="全部";
        loadCatalog("playlists");
    }

    private void loadCatalog(String tab){
        catalogTab=tab;
        catalogEntries=Collections.emptyList();
        page=10;
        busy=true;
        status="";
        int ticket=++request;
        String category=catalogCategory;
        render();
        io.execute(()->{
            try{
                List<HandheldCatalog.Entry> result;
                if(tab.equals("ranks")){
                    try{result=HandheldCatalog.entries(repo.get("/rank/list",MusicRepository.params()),"rank");}
                    catch(Exception error){result=Collections.emptyList();}
                    if(result.isEmpty())result=HandheldCatalog.entries(repo.get("/rank/top",MusicRepository.params()),"rank");
                }else{
                    result=HandheldCatalog.entries(repo.get("/top/playlist",MusicRepository.params(
                        "category_id",category,"page","1","pagesize","30","withsong","1","withtag","1")),"playlist");
                }
                List<HandheldCatalog.Entry> loaded=result;
                finishHandheldRequest(ticket,()->{catalogEntries=loaded;status=loaded.isEmpty()?"暂无可展示的内容":"";});
            }catch(Exception error){finishHandheldRequest(ticket,()->status=error.getMessage());}
        });
    }

    private void chooseCatalogCategory(){
        int ticket=request;
        io.execute(()->{
            try{
                List<HandheldCatalog.Entry> entries=HandheldCatalog.entries(repo.get("/playlist/tags",MusicRepository.params()),"tags");
                main.post(()->{
                    if(ticket!=request||page!=10||isDestroyed())return;
                    if(entries.isEmpty()){toast("暂无歌单分类");return;}
                    String[] names=new String[entries.size()+1];
                    names[0]="全部";
                    for(int i=0;i<entries.size();i++)names[i+1]=entries.get(i).name;
                    new AlertDialog.Builder(this).setTitle("选择歌单分类").setItems(names,(dialog,index)->{
                        if(ticket!=request||page!=10)return;
                        catalogCategory=index==0?"0":entries.get(index-1).id;
                        catalogLabel=names[index];
                        loadCatalog("playlists");
                    }).setNegativeButton("取消",null).show();
                });
            }catch(Exception error){main.post(()->{if(ticket==request&&!isDestroyed())toast(error.getMessage());});}
        });
    }

    private void catalogPage(){
        header(catalogRecommended?"为您推荐":"探索发现",catalogRecommended?"每日推荐歌曲与精选歌单":"按分类发现歌单、新歌和排行榜");
        LinearLayout actions=row();
        actions.addView(button("返回",this::backFromTracks));
        if(catalogRecommended)actions.addView(button("每日推荐",()->openFeedTracks("每日推荐","/everyday/recommend","")));
        else{
            actions.addView(button("分类",this::chooseCatalogCategory));
            actions.addView(button("新歌",()->openFeedTracks("新歌速递","/top/song","")));
            actions.addView(button(catalogTab.equals("ranks")?"歌单":"榜单",()->loadCatalog(catalogTab.equals("ranks")?"playlists":"ranks")));
        }
        content.addView(actions);
        gap(content,12);
        if(busy||!status.isEmpty())content.addView(text(busy?"正在载入…":status,14,MUTED),lp(-1,38));
        if(!busy&&catalogEntries.isEmpty())content.addView(button("重试",()->loadCatalog(catalogTab)));
        if(catalogEntries.isEmpty())return;
        content.addView(title(catalogTab.equals("ranks")?"排行榜":catalogRecommended?"精选歌单":"歌单 · "+catalogLabel,19));
        gap(content,10);
        GridView grid=new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(154));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(dp(10));
        grid.setVerticalSpacing(dp(10));
        List<HandheldCatalog.Entry> entries=catalogEntries;
        grid.setAdapter(new BaseAdapter(){
            public int getCount(){return entries.size();}
            public Object getItem(int position){return entries.get(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){
                HandheldCatalog.Entry entry=entries.get(position);
                LinearLayout card=row();
                card.setBackground(shape(WHITE,16));
                pad(card,10);
                FrameLayout art=HandheldHome.cover(MainActivity.this,artworkLoader,null,48);
                card.addView(art);
                artworkLoader.bindCatalog((ImageView)art.getChildAt(0),(TextView)art.getChildAt(1),entry.cover);
                TextView name=text(entry.name,14,INK);
                name.setPadding(dp(8),0,0,0);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER_VERTICAL);
                card.addView(name,new LinearLayout.LayoutParams(0,dp(56),1));
                return card;
            }
        });
        grid.setOnItemClickListener((parent,view,position,id)->{
            HandheldCatalog.Entry entry=entries.get(position);
            if(catalogTab.equals("ranks"))openFeedTracks(entry.name,"/rank/audio",entry.id);
            else openFeedTracks(entry.name,"/playlist/track/all",entry.id);
        });
        content.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
    }

    private void finishHandheldRequest(int ticket,Runnable update){
        main.post(()->{
            if(ticket!=request||isDestroyed())return;
            busy=false;
            update.run();
            render();
        });
    }

    private void openFeedTracks(String title,String path,String id){
        resetBatch();feedBackPage=page==14?14:10;
        heading=title;feedPath=path;playlistId=id;playlistFilter="";
        page=12;resultPage=0;tracks=new ArrayList<>();more=false;
        fetchFeed(false);
    }

    private void fetchFeed(boolean append){loadPagedTracks(append);}

    void openCloudDisk(){playlistFilter="";resetBatch();
        if(!AuthProtocol.positiveId(repo.cloud.session().userid)){
            loginDialog.open(installed->{onAccountChanged();openCloudDisk();},null);
            return;
        }
        page=11;heading="音乐云盘";playlistId="";tracks=new ArrayList<>();diskPage=null;resultPage=0;more=false;
        loadCloudDisk(false);
    }

    private void loadCloudDisk(boolean append){loadPagedTracks(append);}

    private void loadPagedTracks(boolean append){
        if(append&&resultPage>=CloudLibrary.MAX_PAGES){
            playlistPageError=true;status="歌单超过可读取的分页范围";more=false;
            if(playlistView!=null)playlistView.update();
            return;
        }
        int ticket=++request, requested=append?resultPage+1:1;
        final int sourcePage=page;
        String path=sourcePage==4?"/playlist/track/all":feedPath,id=playlistId;
        CloudLibrary.Session<AuthStore.Scope> account=repo.cloud.session();
        if(!append){
            playlistTotal=-1;trackPageSignatures.clear();resultPage=0;
            resetBatch();
        }
        busy=true;playlistPageError=false;status="";
        if(append&&playlistView!=null)playlistView.update();else render();
        io.execute(()->{
            try{
                if(sourcePage==11){
                    MusicCloudDisk loaded=new MusicCloudDisk(repo.diskGet("/user/cloud",MusicRepository.params(
                        "page",String.valueOf(requested),"pagesize",String.valueOf(MusicCloudDisk.PAGE_SIZE)),account),account.key);
                    finishPagedTracks(ticket,account.scope,()->{
                        diskPage=loaded;
                        addTrackPage(loaded.songs,loaded.more(requested),append,requested,loaded.total,
                            loaded.songs.stream().map(Song::key).collect(java.util.stream.Collectors.joining(",")));
                    });
                }else{
                    HandheldCatalog.TrackPage loaded=HandheldCatalog.tracks(repo.get(path,MusicRepository.params(
                        path.equals("/rank/audio")?"rankid":"id",id,"page",String.valueOf(requested),
                        "pagesize",String.valueOf(HandheldCatalog.PAGE_SIZE))));
                    finishPagedTracks(ticket,account.scope,()->addTrackPage(loaded.songs,
                        !path.equals("/everyday/recommend")&&loaded.hasNext(requested),append,requested,loaded.total,loaded.signature));
                }
            }catch(Exception error){finishPagedTracks(ticket,account.scope,()->{
                playlistPageError=true;
                status=error.getMessage()==null?"读取歌单失败":error.getMessage();
            });}
        });
    }

    private void finishPagedTracks(int ticket,AuthStore.Scope scope,Runnable update){
        main.post(()->{
            if(ticket!=request||isDestroyed())return;
            busy=false;
            if(repo.auth.current(scope))update.run();
            else{playlistPageError=true;status="账号已变化，请重新加载";}
            if(playlistView!=null)playlistView.update();
            else render();
        });
    }

    private void addTrackPage(List<Song> loaded,boolean hasNext,boolean append,int requested,long total,String signature){
        if(!trackPageSignatures.add(signature)&&!loaded.isEmpty()){
            if(hasNext||total>=0&&tracks.size()<total){playlistPageError=true;status="服务返回了重复页面";}
            more=false;
            return;
        }
        if(!append)tracks=new ArrayList<>();
        Set<String> known=new HashSet<>();
        for(Song song:tracks)known.add(song.key());
        for(Song song:loaded)if(known.add(song.key()))tracks.add(song);
        resultPage=requested;
        if(total>=0)playlistTotal=total;
        more=hasNext;
    }

    private void chooseCloudUpload(){
        if(diskUploadPending!=null||diskUploading)return;
        diskUploadPending=repo.cloud.session();
        Intent choose=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*");
        try{startActivityForResult(choose,13);}catch(ActivityNotFoundException error){
            diskUploadPending=null;toast("此设备没有可用的文件选择器");
        }
    }

    private void confirmCloudUpload(Uri uri,CloudLibrary.Session<AuthStore.Scope> expected){
        if(expected==null||diskUploading||!repo.auth.current(expected.scope))return;
        new AlertDialog.Builder(this).setTitle("上传到当前账号的音乐云盘？")
            .setMessage("支持单个不超过 20 MB 的音频文件。")
            .setNegativeButton("取消",null).setPositiveButton("上传",(dialog,which)->{
                if(diskUploading||!repo.auth.current(expected.scope))return;
                diskUploading=true;
                toast("正在上传音频…");
                io.execute(()->{
                    String result;
                    try{uploadCloudFile(uri,expected);result="上传成功";}
                    catch(Exception error){result="上传未确认："+error.getMessage();}
                    String message=result;
                    main.post(()->{
                        diskUploading=false;
                        if(isDestroyed()||!repo.auth.current(expected.scope))return;
                        toast(message);
                        if(page==11)loadCloudDisk(false);
                    });
                });
            }).show();
    }

    private void uploadCloudFile(Uri uri,CloudLibrary.Session<AuthStore.Scope> expected)throws Exception{
        String filename="";
        try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0);
        }
        if(filename==null)filename="";
        int dot=filename.lastIndexOf('.');
        if(dot<=0||dot>=filename.length()-1)throw new java.io.IOException("请选择有音频扩展名的文件");
        String extension=filename.substring(dot+1).toLowerCase(Locale.ROOT);
        if(!Arrays.asList("mp3","flac","wav","m4a","aac","ogg","opus","ape","wma").contains(extension))
            throw new java.io.IOException("此音频格式暂不支持上传");
        try(java.io.InputStream input=getContentResolver().openInputStream(uri);
            java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream()){
            if(input==null)throw new java.io.IOException("无法读取所选文件");
            byte[] buffer=new byte[8192];
            int count;
            while((count=input.read(buffer))!=-1){
                if(output.size()+count>MusicCloudDisk.MAX_UPLOAD)throw new java.io.IOException("文件超过 20 MB");
                output.write(buffer,0,count);
            }
            if(output.size()==0)throw new java.io.IOException("文件为空");
            repo.uploadDisk(filename,extension,output.toByteArray(),expected);
        }
    }

    private void backFromTracks(){
        ++request;busy=false;status="";
        playlistFilter="";resetBatch();playlistPageError=false;
        if(page==12){page=feedBackPage;render();return;}
        if(page==14){page=1;tracks=new ArrayList<>();more=false;render();return;}
        page=page==10?0:2;
        render();
    }

    private void resetBatch(){batchMode=false;selectedTracks.clear();}

    private void showBatchActions(View anchor,List<Song> selected){
        if(selected.isEmpty())return;
        CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();
        CloudProtocol.Playlist playlist=activeCloud;
        boolean favorites=page==7&&cloudFavoritesPage;
        boolean editable=page==7&&(favorites||playlist!=null&&playlist.owned(expected.userid));
        boolean disk=page==11;
        List<String> labels=new ArrayList<>();
        List<Runnable> actions=new ArrayList<>();
        labels.add("播放选中");actions.add(()->play(selected,0));
        labels.add("加入播放队列");actions.add(()->{
            if(service==null){toast("播放器正在初始化");return;}
            service.appendAll(selected);toast("已加入 "+selected.size()+" 首歌曲");
        });
        List<Song> online=new ArrayList<>(),addable=new ArrayList<>();
        for(Song song:selected){
            if(!song.local())online.add(song);
            if(!song.cloudDisk()&&song.cloudSong().hash.matches("[a-fA-F0-9]{32}"))addable.add(song.cloudSong());
        }
        if(!online.isEmpty()){labels.add("下载到本地 · "+online.size()+" 首");actions.add(()->downloadSongs(online,expected.scope));}
        if(!addable.isEmpty()&&AuthProtocol.positiveId(expected.userid)){
            labels.add("添加到我的歌单 · "+addable.size()+" 首");actions.add(()->addToCloudPlaylist(addable,expected));
        }
        if(editable&&selected.stream().allMatch(song->AuthProtocol.positiveId(song.fileId))){
            labels.add("从此歌单移除");actions.add(()->new AlertDialog.Builder(this)
                .setTitle("从此歌单移除 "+selected.size()+" 首？").setMessage("该操作会同步到当前账号。")
                .setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->cloudAction(expected,session->{
                    int count=repo.cloud.removeAll(session,favorites?null:playlist.id,selected);
                    return "已确认移除 "+count+" 首歌曲";
                })).show());
        }
        if(disk&&selected.stream().allMatch(song->AuthProtocol.positiveId(song.cloudDiskId()))){
            labels.add("删除云盘文件");actions.add(()->deleteCloudFiles(selected,expected));
        }
        PopupMenu menu=new PopupMenu(this,anchor);
        for(int i=0;i<labels.size();i++)menu.getMenu().add(0,i,i,labels.get(i));
        menu.setOnMenuItemClickListener(item->{
            if(!repo.auth.current(expected.scope)){resetBatch();render();toast("账号已变化，请重新选择");return true;}
            actions.get(item.getItemId()).run();
            return true;
        });
        menu.show();
    }

    private void deleteCloudFiles(List<Song> selected,CloudLibrary.Session<AuthStore.Scope> expected){
        new AlertDialog.Builder(this).setTitle("删除云盘中的 "+selected.size()+" 个文件？")
            .setMessage("删除会同步到当前账号的音乐云盘。")
            .setNegativeButton("取消",null).setPositiveButton("删除",(dialog,which)->{
                if(diskDeleting){toast("正在处理上一批文件");return;}
                if(!repo.auth.current(expected.scope))return;
                diskDeleting=true;
                toast("正在删除云盘文件…");
                io.execute(()->{
                    String result;
                    try{result="已提交删除 "+MusicCloudDisk.remove(repo,expected,selected)+" 个文件";}
                    catch(Exception error){result="删除未全部确认："+error.getMessage();}
                    String message=result;
                    main.post(()->{
                        diskDeleting=false;
                        if(isDestroyed()||!repo.auth.current(expected.scope))return;
                        toast(message);
                        if(page==11)loadCloudDisk(false);
                    });
                });
            }).show();
    }

    private void downloadSongs(List<Song> selected,AuthStore.Scope expected){
        if(!repo.auth.current(expected))return;
        List<Song> batch=new ArrayList<>();
        for(Song song:selected)if(resolvingDownloads.add(song.key()))batch.add(song);
        if(batch.isEmpty()){toast("所选歌曲已在获取下载地址");return;}
        toast("正在准备 "+batch.size()+" 首歌曲的下载…");
        io.execute(()->{
            int added=0,skipped=selected.size()-batch.size(),failed=0;
            String firstError="";
            for(Song song:batch){
                if(!repo.auth.current(expected)){skipped++;continue;}
                try{
                    boolean exists=false;
                    for(MusicDownloads.Item item:downloads.items())if(Song.same(item.song,song)&&(item.active()||item.complete())){exists=true;break;}
                    if(exists){skipped++;continue;}
                    String url=repo.resolveDownload(song,expected);
                    if(!repo.auth.current(expected)){skipped++;continue;}
                    downloads.enqueue(song,url);
                    added++;
                }catch(Exception error){failed++;if(firstError.isEmpty()&&error.getMessage()!=null)firstError=error.getMessage();}
            }
            String message="已加入 "+added+" 首，跳过 "+skipped+" 首，失败 "+failed+" 首"+(firstError.isEmpty()?"":"\n"+firstError);
            main.post(()->{
                for(Song song:batch)resolvingDownloads.remove(song.key());
                if(isDestroyed())return;
                toast(message);
                if(page==8&&downloadList!=null)downloadList.refresh();
            });
        });
    }

    void trackPage(){
        if(!BuildConfig.TV){handheldTrackPage();return;}
        header(heading.isEmpty()?"在线歌单":heading,tracks.size()+" 首歌曲");
        LinearLayout actions=row();actions.addView(button("← 音乐库",()->{++request;busy=false;page=2;render();}));actions.addView(button("播放全部",()->{if(!tracks.isEmpty())play(tracks,0);}));if(page==7)actions.addView(button(BuildConfig.TV?"切换顺序":"顺序 ⇅",this::toggleCloudOrder));if(page==7)actions.addView(button("刷新",()->{if(cloudFavoritesPage)syncCloud(true);else loadCloudPlaylist();}));content.addView(actions);gap(content,8);
        notice=text(busy?"正在加载…":status,14,MUTED);content.addView(notice,lp(-1,40));
        if(tracks.isEmpty()&&!busy)content.addView(text("这里还没有音乐。",16,MUTED));else listTracks();
    }
    private boolean isPlaylistPage(){
        return page==4||page==5||page==7||page==11||page==12||page==13;
    }

    private void handheldTrackPage(){
        String key=page+"|"+heading+"|"+playlistId+"|"+(activeCloud==null?"":activeCloud.id);
        if(!key.equals(playlistFilterKey)){playlistFilterKey=key;playlistFilter="";resetBatch();playlistPageError=false;}
        playlistView=new PlaylistView();
        playlistView.update();
    }

    private void refreshHandheldPlaylist(){
        resetBatch();
        if(page==13)openCachedMusic();
        else if(page==11||page==12||page==4)loadPagedTracks(false);
        else if(cloudFavoritesPage)syncCloud(true);
        else loadCloudPlaylist();
    }

    private TextView toolbarItem(LinearLayout bar,String label,Runnable action){
        TextView control=button(label,action);
        control.setSingleLine(true);
        control.setEllipsize(android.text.TextUtils.TruncateAt.END);
        control.setPadding(dp(4),0,dp(4),0);
        control.setAutoSizeTextTypeUniformWithConfiguration(12,15,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout.LayoutParams slot=tablet?lp(104,48):new LinearLayout.LayoutParams(0,dp(48),1);
        if(bar.getChildCount()>0)slot.leftMargin=dp(6);
        bar.addView(control,slot);
        return control;
    }

    @android.annotation.TargetApi(26)
    private final class PlaylistView {
        final int source=page;
        final List<Song> shown=new ArrayList<>();
        final ListView list=new ListView(MainActivity.this);
        final LinearLayout actions=row();
        final TextView details=text("",13,MUTED),message=text("",13,MUTED);
        final EditText search=new EditText(MainActivity.this);
        private TextView selectAll,selectedCount,options;
        private final Runnable nextPage=this::maybeNext;
        final BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return shown.size();}
            public Object getItem(int at){return shown.get(at);}
            public long getItemId(int at){return at;}
            public View getView(int at,View old,ViewGroup parent){
                Song song=shown.get(at);
                return songRow(song,at,null,batchMode,selectedTracks.contains(song));
            }
        };

        PlaylistView(){
            LinearLayout headingRow=row();
            headingRow.setGravity(Gravity.TOP);
            TextView back=touchControl("back","返回",MainActivity.this::backFromTracks);
            back.setBackground(shape(WHITE,24));
            headingRow.addView(back,lp(48,48));
            TextView name=title(heading.isEmpty()?"在线歌单":heading,28);
            name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameSlot=new LinearLayout.LayoutParams(0,-2,1);
            nameSlot.leftMargin=dp(10);headingRow.addView(name,nameSlot);
            content.addView(headingRow);gap(content,8);content.addView(details);gap(content,14);
            content.addView(actions);gap(content,10);
            search.setSingleLine();search.setTextSize(15);search.setHint("搜索歌曲、歌手或专辑");search.setText(playlistFilter);
            search.setPadding(dp(14),0,dp(14),0);search.setBackground(shape(0xffe6eaee,18));
            content.addView(search,lp(-1,44));
            search.addTextChangedListener(new android.text.TextWatcher(){
                public void beforeTextChanged(CharSequence value,int start,int count,int after){}
                public void onTextChanged(CharSequence value,int start,int before,int count){
                    playlistFilter=value.toString();
                    selectedTracks.clear();
                    update();
                    list.setSelection(0);
                }
                public void afterTextChanged(android.text.Editable value){}
            });
            search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            search.setOnEditorActionListener((v,action,event)->{hideKeyboard(search);return true;});
            message.setPadding(0,dp(8),0,dp(4));content.addView(message);
            message.setOnClickListener(v->{
                if(playlistPageError&&!busy)loadPagedTracks(resultPage>0&&more);
            });
            list.setDivider(null);list.setSelector(shape(0xffe0f4eb,10));list.setAdapter(adapter);
            list.setOnItemClickListener((p,v,at,id)->{
                if(batchMode){
                    Song song=shown.get(at);
                    if(!selectedTracks.remove(song))selectedTracks.add(song);
                    adapter.notifyDataSetChanged();updateBatchBar();
                }else play(shown,at);
            });
            list.setOnItemLongClickListener((p,v,at,id)->{
                if(batchMode)return false;
                songMenu(shown.get(at));return true;
            });
            list.setOnScrollListener(new AbsListView.OnScrollListener(){
                public void onScrollStateChanged(AbsListView view,int state){}
                public void onScroll(AbsListView view,int first,int visible,int total){
                    if(visible>0&&first+visible>=total)queueNext();
                }
            });
            content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
            buildActions();
        }

        private void buildActions(){
            actions.removeAllViews();
            selectAll=null;selectedCount=null;options=null;
            if(batchMode){
                selectAll=toolbarItem(actions,"全选",()->{
                    if(selectedTracks.containsAll(shown))selectedTracks.clear();else selectedTracks.addAll(shown);
                    adapter.notifyDataSetChanged();updateBatchBar();
                });
                selectedCount=text("",13,MUTED);selectedCount.setGravity(Gravity.CENTER);
                selectedCount.setSingleLine(true);
                actions.addView(selectedCount,new LinearLayout.LayoutParams(0,dp(48),1));
                options=toolbarItem(actions,"操作",()->{
                    List<Song> selection=new ArrayList<>();
                    for(Song song:shown)if(selectedTracks.contains(song))selection.add(song);
                    showBatchActions(options,selection);
                });
                toolbarItem(actions,"取消",()->setBatch(false));
                updateBatchBar();
            }else{
                toolbarItem(actions,"播放列表",()->{
                    if(shown.isEmpty())toast("当前没有可播放的歌曲");else play(shown,0);
                });
                toolbarItem(actions,"批量管理",()->{
                    if(shown.isEmpty())toast("当前没有匹配的歌曲");else setBatch(true);
                });
                if(source==11)toolbarItem(actions,"上传音频",MainActivity.this::chooseCloudUpload);
                if(source==7)toolbarItem(actions,"切换顺序",MainActivity.this::toggleCloudOrder);
                if(source!=5)toolbarItem(actions,"刷新",MainActivity.this::refreshHandheldPlaylist);
            }
        }

        void setBatch(boolean enabled){
            resetBatch();batchMode=enabled;
            hideKeyboard(search);
            buildActions();adapter.notifyDataSetChanged();
        }

        private void updateBatchBar(){
            if(selectAll==null)return;
            selectAll.setText(!shown.isEmpty()&&selectedTracks.containsAll(shown)?"取消全选":"全选");
            selectedCount.setText("已选 "+selectedTracks.size()+" 首");
            options.setEnabled(!selectedTracks.isEmpty());
            options.setAlpha(options.isEnabled()?1f:.48f);
        }

        void update(){
            String keyword=playlistFilter.trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for(Song song:tracks){
                if(keyword.isEmpty()||(HandheldHome.name(song.title)+" "+song.artist+" "+song.album)
                    .toLowerCase(Locale.ROOT).contains(keyword))shown.add(song);
            }
            selectedTracks.retainAll(shown);
            adapter.notifyDataSetChanged();updateBatchBar();
            boolean paged=source==4||source==11||source==12;
            boolean searching=paged&&!keyword.isEmpty()&&more;
            String count=paged&&playlistTotal>=0?"已载入 "+tracks.size()+" / "+playlistTotal+" 首":tracks.size()+" 首歌曲";
            if(source==11&&diskPage!=null&&diskPage.capacity>0&&diskPage.available>=0)
                count+=" · 可用 "+android.text.format.Formatter.formatFileSize(MainActivity.this,diskPage.available);
            details.setText(count);
            String hint=playlistPageError?"读取未完成 · 点击重试\n"+status:
                searching?"正在搜索整张歌单…":busy?"正在加载…":
                shown.isEmpty()?(keyword.isEmpty()?(source==13?"暂无已缓存歌曲":"暂无歌曲"):"没有匹配的歌曲"):status;
            message.setText(hint);message.setVisibility(hint.isEmpty()?View.GONE:View.VISIBLE);
            message.setClickable(playlistPageError);
            if(searching)queueNext();
        }

        private void queueNext(){list.removeCallbacks(nextPage);list.postDelayed(nextPage,180);}

        private void maybeNext(){
            if(playlistView!=this||!list.isAttachedToWindow()||busy||!more||playlistPageError
                ||(source!=4&&source!=11&&source!=12))return;
            if(!playlistFilter.trim().isEmpty()||(!shown.isEmpty()&&list.getLastVisiblePosition()>=shown.size()-1))
                loadPagedTracks(true);
        }
    }

    void listTracks(){
        ListView list=new ListView(this);list.setDivider(null);
        if(BuildConfig.TV)tvSongList=list;
        list.setSelector(shape(0xffc6f6f7,10));list.setClipToPadding(false);
        list.setAdapter(new BaseAdapter(){
            public int getCount(){return tracks.size();}
            public Object getItem(int at){return tracks.get(at);}
            public long getItemId(int at){return at;}
            public View getView(int at,View old,ViewGroup parent){return songRow(tracks.get(at),at,null);}
        });
        list.setOnItemClickListener((p,v,at,id)->play(tracks,at));
        list.setOnItemLongClickListener((p,v,at,id)->{songMenu(tracks.get(at));return true;});
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        if(more&&(page==1||page==4))content.addView(button(busy?"加载中…":"加载更多",()->{if(!busy){resultPage++;fetch(true);}}),lp(-1,48));
    }
    void openCachedMusic(){
        page=13;heading="已缓存歌曲";playlistId="";tracks=new ArrayList<>();more=false;
        int ticket=++request;
        CloudLibrary.Session<AuthStore.Scope> account=repo.cloud.session();
        busy=true;status="";render();
        io.execute(()->{
            List<Song> saved=playbackCache.songs(account.key);
            finishHandheldRequest(ticket,()->{if(repo.auth.current(account.scope))tracks=saved;});
        });
    }

    private void openPlaylistSearch(String keyword){
        page=14;publicPlaylistQuery=keyword;publicPlaylists=Collections.emptyList();busy=false;status="";
        ++request;
        if(keyword.isEmpty())render();else findPublicPlaylists(keyword);
    }
    private void findPublicPlaylists(String keyword){
        if(keyword.isEmpty())return;
        publicPlaylistQuery=keyword;
        int ticket=++request;
        busy=true;status="";publicPlaylists=Collections.emptyList();render();
        io.execute(()->{
            try{
                JSONObject result=repo.get("/search",MusicRepository.params("keywords",keyword,"type","special","page","1","pagesize","30"));
                List<HandheldCatalog.Entry> entries=HandheldCatalog.entries(result,"playlist");
                finishHandheldRequest(ticket,()->{publicPlaylists=entries;status=entries.isEmpty()?"没有匹配的公开歌单":"";});
            }catch(Exception failure){finishHandheldRequest(ticket,()->status=failure.getMessage());}
        });
    }
    private void publicPlaylistPage(){
        header("搜索公开歌单","按歌单名称查找在线歌单");
        LinearLayout bar=row();
        EditText input=new EditText(this);input.setSingleLine();input.setTextSize(16);input.setHint("输入歌单名称");input.setText(publicPlaylistQuery);
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        Runnable submit=()->{hideKeyboard(input);findPublicPlaylists(input.getText().toString().trim());};
        input.setOnEditorActionListener((v,action,event)->{submit.run();return true;});
        bar.addView(input,new LinearLayout.LayoutParams(0,dp(48),1));bar.addView(button("搜索",submit));content.addView(bar);
        content.addView(button("返回",this::backFromTracks));
        if(busy||!status.isEmpty())content.addView(text(busy?"正在搜索…":status,14,MUTED));
        List<HandheldCatalog.Entry> entries=publicPlaylists;
        ListView list=new ListView(this);
        list.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,
            entries.stream().map(entry->entry.name).toArray(String[]::new)));
        list.setOnItemClickListener((parent,view,pos,id)->{
            HandheldCatalog.Entry entry=entries.get(pos);openFeedTracks(entry.name,"/playlist/track/all",entry.id);
        });
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
    }

    View songRow(Song s,int pos,Runnable tap){return songRow(s,pos,tap,false,false);}

    private View songRow(Song s,int pos,Runnable tap,boolean picking,boolean selected){
        LinearLayout r=row();r.setPadding(dp(2),dp(8),dp(2),dp(8));
        TextView number=text(String.format(Locale.ROOT,"%02d",pos+1),12,MUTED);r.addView(number,lp(36,48));
        LinearLayout labels=col();TextView t=title(s.title,16);t.setSingleLine();t.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(t);gap(labels,5);TextView a=text(s.artist.isEmpty()?"本地音频":s.artist,13,MUTED);a.setSingleLine();a.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(a);r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        if(picking){
            CheckBox box=new CheckBox(this);
            box.setChecked(selected);box.setClickable(false);box.setFocusable(false);
            r.addView(box,lp(42,48));
            r.setContentDescription(s.title+(selected?"，已选中":"，未选中"));
            if(selected)r.setBackground(shape(0xffe0f4eb,10));
            return r;
        }
        TextView menu=text(BuildConfig.TV?"操作":"⋯",BuildConfig.TV?18:24,MUTED);menu.setGravity(Gravity.CENTER);r.addView(menu,lp(48,48));menu.setContentDescription("歌曲操作："+s.title);menu.setOnClickListener(v->songMenu(s));if(tap!=null)r.setOnClickListener(v->tap.run());return r;
    }
    void songMenu(Song song){
        CloudLibrary.Session<AuthStore.Scope> expected=repo.cloud.session();CloudProtocol.Playlist target=activeCloud;List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();
        if(song.cloudIdentified()){labels.add(repo.favorite(song)?"取消云端收藏":"收藏到云端账号");actions.add(()->{if(!repo.auth.current(expected.scope)){toast("账号已变化，请重新操作");return;}toggleSongFavorite(song);});}
        labels.add("加入播放队列");actions.add(()->{if(service!=null){service.append(song);toast("已加入播放队列");}});labels.add("下一首播放");actions.add(()->{if(service!=null){service.insertNext(song);toast("已设为下一首");}});
        if(!BuildConfig.TV&&!song.local()){labels.add("下载到本地");actions.add(()->downloadSong(song,expected.scope));}
        if(!song.cloudDisk()&&song.cloudIdentified()){labels.add("添加到云端歌单");actions.add(()->addToCloudPlaylist(song.cloudSong(),expected));}
        if(page==7&&!cloudFavoritesPage&&target!=null&&target.owned(expected.userid)){labels.add("从此云端歌单移除");actions.add(()->new AlertDialog.Builder(this).setTitle("移出云端歌单？").setMessage(song.title).setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->cloudAction(expected,session->{repo.cloud.remove(session,target.id,song);return "已确认从云端歌单移除";})).show());}
        if(!BuildConfig.TV&&page==11&&AuthProtocol.positiveId(song.cloudDiskId())){
            labels.add("删除云盘文件");actions.add(()->deleteCloudFiles(Collections.singletonList(song),expected));
        }
        if(page==5&&heading.startsWith("设备收藏")){labels.add("从设备收藏移除");actions.add(()->{List<Song> list=repo.saved("favorites");list.removeIf(existing->Song.same(existing,song));repo.save("favorites",list);openSaved("favorites",heading);});}
        new AlertDialog.Builder(this).setTitle(song.title).setItems(labels.toArray(new String[0]),(d,which)->actions.get(which).run()).show();
    }
    void downloadCurrent(){if(BuildConfig.TV){remoteSettings();return;}Song song=currentSong();if(song==null){toast("请先选择在线歌曲");return;}downloadSong(song,repo.auth.scope());}
    void downloadSong(Song song,AuthStore.Scope expected){
        if(song.local()){toast("这首歌已在本地，无需重复下载");return;}if(!repo.auth.current(expected)){toast("账号已变化，请重新操作");return;}if(!resolvingDownloads.add(song.key())){toast("正在获取这首歌的下载地址");return;}
        toast("正在获取下载地址…");io.execute(()->{try{String address=repo.resolveDownload(song,expected);if(!repo.auth.current(expected))throw new java.io.IOException("账号已变化，请重新下载");downloads.enqueue(song,address);main.post(()->{resolvingDownloads.remove(song.key());if(!isDestroyed()){toast("已加入下载，可在音乐库查看进度");if(page==8&&downloadList!=null)downloadList.refresh();}});}catch(Exception error){main.post(()->{resolvingDownloads.remove(song.key());if(!isDestroyed())toast("下载未开始："+error.getMessage());});}});
    }
    private DownloadList downloadList;
    private final Runnable refreshDownloads=()->{
        DownloadList view=downloadList;
        if(view!=null)view.refresh();
    };
    void downloadsPage(){
        header("本地下载","查看下载进度，完成后可离线播放。");
        LinearLayout actions=row();
        actions.addView(button("音乐库",()->{page=2;render();}),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(button("本地音频",this::localAudio),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(button("打开文件夹",this::openDownloadFolder),new LinearLayout.LayoutParams(0,dp(48),1));
        content.addView(actions);
        gap(content,12);
        downloadList=new DownloadList();
        downloadList.refresh();
    }
    private final class DownloadList extends BaseAdapter {
        private List<MusicDownloads.Item> items=Collections.emptyList();
        private final TextView summary;
        private boolean refreshing;
        DownloadList(){
            LinearLayout bar=row();
            summary=text("正在读取下载记录…",13,MUTED);
            bar.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
            bar.addView(button("刷新",this::refresh),lp(68,48));
            content.addView(bar);
            ListView list=new ListView(MainActivity.this);
            list.setDivider(null);
            list.setAdapter(this);
            content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        }
        void refresh(){
            if(refreshing||downloadList!=this||!activityStarted)return;
            refreshing=true;
            main.removeCallbacks(refreshDownloads);
            io.execute(()->{
                List<MusicDownloads.Item> loaded=downloads.items();
                main.post(()->{
                    refreshing=false;
                    if(downloadList!=this||!activityStarted||isDestroyed())return;
                    int completed=0,running=0;
                    boolean changed=items.size()!=loaded.size();
                    for(int i=0;i<loaded.size();i++){
                        MusicDownloads.Item item=loaded.get(i);
                        if(item.complete())completed++;
                        else if(item.active())running++;
                        if(!changed&&!item.sameProgress(items.get(i)))changed=true;
                    }
                    items=loaded;
                    if(changed)notifyDataSetChanged();
                    summary.setText(items.isEmpty()?"暂无下载，可从在线歌曲菜单添加":completed+" 首已下载 · "+running+" 项进行中");
                    if(running>0)main.postDelayed(refreshDownloads,1000);
                });
            });
        }
        @Override public int getCount(){return items.size();}
        @Override public Object getItem(int position){return items.get(position);}
        @Override public long getItemId(int position){return items.get(position).id;}
        @Override public boolean hasStableIds(){return true;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            DownloadRow row=recycled instanceof DownloadRow?(DownloadRow)recycled:new DownloadRow();
            row.bind(items.get(position));
            return row;
        }
        private final class DownloadRow extends LinearLayout {
            final TextView name,artist,status,more;
            final ProgressBar progressBar;
            DownloadRow(){
                super(MainActivity.this);
                setOrientation(VERTICAL);
                pad(this,12);
                LinearLayout head=row();
                LinearLayout metadata=col();
                name=title("",16);
                name.setSingleLine();
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                artist=text("",13,MUTED);
                artist.setSingleLine();
                artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
                metadata.addView(name);
                metadata.addView(artist);
                head.addView(metadata,new LinearLayout.LayoutParams(0,-2,1));
                more=touchControl("⋮","管理下载",()->{});
                head.addView(more,lp(48,48));
                addView(head);
                status=text("",12,MUTED);
                addView(status);
                progressBar=new ProgressBar(MainActivity.this,null,android.R.attr.progressBarStyleHorizontal);
                progressBar.setMax(100);
                progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
                progressBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
                LinearLayout.LayoutParams line=lp(-1,4);
                line.topMargin=dp(8);
                addView(progressBar,line);
            }
            void bind(MusicDownloads.Item item){
                name.setText(HandheldHome.name(item.song.title));
                artist.setText(item.song.artist);
                status.setText(item.label());
                progressBar.setVisibility(item.active()?View.VISIBLE:View.GONE);
                progressBar.setIndeterminate(item.active()&&item.total<=0&&item.status==android.app.DownloadManager.STATUS_RUNNING);
                progressBar.setProgress(item.percent());
                progressBar.setContentDescription(item.label());
                more.setOnClickListener(v->manageDownload(item));
                setOnClickListener(v->{
                    if(item.complete()){
                        List<Song> offline=new ArrayList<>();
                        int selected=0;
                        for(MusicDownloads.Item entry:items)if(entry.complete()){
                            if(entry.id==item.id)selected=offline.size();
                            offline.add(entry.local());
                        }
                        play(offline,selected);
                    }else if(!item.active()){
                        new AlertDialog.Builder(MainActivity.this).setTitle("重试下载？")
                            .setMessage(item.song.title).setNegativeButton("取消",null).setPositiveButton("重试",(d,w)->{
                                try{downloads.remove(item.id);downloadSong(item.song,repo.auth.scope());refresh();}
                                catch(Exception error){toast(error.getMessage());}
                            }).show();
                    }else toast(item.label());
                });
            }
        }
    }
    void openDownloadFolder(){android.net.Uri folder=android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",Build.VERSION.SDK_INT>=29?"primary:Download/EchoMusic":"primary:Android/data/cc.echomusic.android/files/Music");try{Intent view=new Intent(Intent.ACTION_VIEW).setDataAndType(folder,"vnd.android.document/directory").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(view);}catch(ActivityNotFoundException|SecurityException e){try{Intent picker=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);if(Build.VERSION.SDK_INT>=26)picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,folder);startActivity(picker);}catch(ActivityNotFoundException|SecurityException failure){try{startActivity(new Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));}catch(ActivityNotFoundException ignored){toast("请在系统文件管理器中打开 下载/EchoMusic");}}}}
    void manageDownload(MusicDownloads.Item item){List<String> labels=new ArrayList<>();List<Runnable> actions=new ArrayList<>();labels.add("打开下载文件夹");actions.add(this::openDownloadFolder);if(item.complete()){labels.add("分享 / 用其他应用打开");actions.add(()->{try{Intent share=new Intent(Intent.ACTION_SEND).setType("audio/*").putExtra(Intent.EXTRA_STREAM,item.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);share.setClipData(ClipData.newRawUri("audio",item.uri));startActivity(Intent.createChooser(share,"分享已下载歌曲"));}catch(ActivityNotFoundException|SecurityException e){toast("没有可用的文件分享应用");}});}labels.add(item.complete()?"删除已下载文件":"取消并移除任务");actions.add(()->removeDownload(item));new AlertDialog.Builder(this).setTitle(item.song.title).setItems(labels.toArray(new String[0]),(d,w)->actions.get(w).run()).setNegativeButton("关闭",null).show();}
    void removeDownload(MusicDownloads.Item item){new AlertDialog.Builder(this).setTitle(item.complete()?"删除已下载音频？":"取消下载并移除任务？").setMessage(item.song.title+"\n只移除本次下载的本地文件，云端歌单不变。").setNegativeButton("取消",null).setPositiveButton("移除",(d,w)->{try{downloads.remove(item.id);if(downloadList!=null)downloadList.refresh();}catch(Exception e){toast(e.getMessage());}}).show();}
    void askPlaylist(){EditText id=new EditText(this);id.setHint("歌单 ID（数字）");id.setInputType(2);new AlertDialog.Builder(this).setTitle("打开酷狗歌单").setView(id).setNegativeButton("取消",null).setPositiveButton("打开",(d,w)->{String value=id.getText().toString().trim();if(value.isEmpty())return;playlistId=value;page=4;heading="在线歌单";playlistFilter="";resultPage=1;tracks=new ArrayList<>();more=false;fetch(false);}).show();}
    void remoteSettings(){if(service==null){toast("播放器正在初始化");return;}LinearLayout body=col();pad(body,20);body.addView(text("手机与本设备连接同一局域网，扫码或输入下方 IP:端口即可访问。",16,INK));TextView addresses=text(service.remoteAddresses(),14,GREEN);addresses.setTextIsSelectable(true);body.addView(addresses);ImageView code=new ImageView(this);body.addView(code,lp(-1,230));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("EchoRemote 局域网远控").setView(body).setNegativeButton("关闭页面",null).setNeutralButton(service.remoteEnabled()?"停止远控":"开启远控",null).setPositiveButton("复制配对地址",(d,w)->{((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("EchoMusic",service.remoteAddresses()));toast("地址已复制");}).create();dialog.setOnShowListener(d->{dialog.getButton(-3).setOnClickListener(v->{String result=service.setRemoteEnabled(!service.remoteEnabled());toast(result);dialog.dismiss();remoteSettings();});if(service.remoteEnabled()){String address=service.remoteAddresses().split("\n")[0];if(address.startsWith("http://"))io.execute(()->{try{JSONObject qr=repo.get("/__qr",MusicRepository.params("text",address));String encoded=qr.optString("image");byte[] bytes=android.util.Base64.decode(encoded.substring(encoded.indexOf(',')+1),android.util.Base64.DEFAULT);android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length);main.post(()->{if(dialog.isShowing())code.setImageBitmap(bitmap);});}catch(Exception ignored){}});}if(BuildConfig.TV)TvFocus.install(dialog.getWindow().getDecorView());});dialog.show();}
    private void cacheSettings(LinearLayout form){
        form.addView(title("离线缓存",19));gap(form,8);
        Switch enabled=new Switch(this);
        enabled.setText("播放时自动缓存");enabled.setTextColor(INK);enabled.setChecked(playbackCache.enabled());
        form.addView(enabled,lp(-1,48));
        form.addView(text("开启后自动缓存播放过的在线歌曲。缓存完成后可离线播放，关闭开关后仍可播放已缓存歌曲。",14,MUTED));
        enabled.setOnCheckedChangeListener((view,on)->{
            playbackCache.enabled(on);
            if(on&&service!=null)service.cacheCurrent();
        });
        TextView size=text("",13,MUTED);form.addView(size,lp(-1,34));
        io.execute(()->{
            String space=android.text.format.Formatter.formatFileSize(this,playbackCache.bytes());
            main.post(()->{if(size.isAttachedToWindow())size.setText("设备音频缓存 · "+space);});
        });
        form.addView(button("查看已缓存歌曲",this::openCachedMusic),lp(-1,48));gap(form,8);
        form.addView(button("清空音频缓存",()->new AlertDialog.Builder(this).setTitle("清空音频缓存？")
            .setMessage("将清空这台设备上的自动缓存。手动下载的音乐会保留。")
            .setNegativeButton("取消",null).setPositiveButton("清空",(dialog,which)->io.execute(()->{
                boolean cleared=playbackCache.clear();
                main.post(()->{if(!isDestroyed()){toast(cleared?"已清空音频缓存":"部分缓存暂未清除，请重试");if(page==3)render();}});
            })).show()),lp(-1,48));gap(form,20);
    }

    void settings(){
        header("设置","音乐与你的设备，默契配合。");ScrollView scroll=new ScrollView(this);content.addView(scroll,lp(-1,-1));LinearLayout form=col();scroll.addView(form);
        form.addView(button("局域网远控",this::remoteSettings),lp(-1,56));gap(form,16);if(!BuildConfig.TV){form.addView(button("状态栏歌词",()->StatusLyrics.settings(this)),lp(-1,56));gap(form,16);}
        if(!BuildConfig.TV)cacheSettings(form);
        form.addView(title("账号",19));gap(form,8);form.addView(text(repo.auth.displayName(),16,INK));gap(form,12);
        form.addView(button("扫码 / 手机验证码登录",()->loginDialog.open(installed->onAccountChanged(),null)),lp(-1,50));gap(form,12);
        if(!repo.auth.authorization().isEmpty()){form.addView(button("退出登录",()->new AlertDialog.Builder(this).setTitle("退出当前账号？").setMessage("本地音乐、收藏和历史记录会保留。").setNegativeButton("取消",null).setPositiveButton("退出",(d,w)->{repo.auth.logout();onAccountChanged();render();}).show()),lp(-1,48));gap(form,12);}
        gap(form,12);line(form);gap(form,20);form.addView(title("音乐连接",19));gap(form,10);
        form.addView(text(ApiEndpoint.embedded(repo.endpoint())?"已使用内置连接，打开应用即可在线听歌。":"正在使用你设置的外部连接。",15,MUTED));gap(form,14);
        form.addView(button("检查连接",()->{int ticket=++request;toast("正在检查连接…");io.execute(()->{try{if(ApiEndpoint.embedded(repo.endpoint())){EmbeddedApi.instance().await(this);repo.get("/__health",MusicRepository.params());}else repo.get("/search",MusicRepository.params("keywords","音乐","type","song","page","1","pagesize","1"));main.post(()->{if(ticket==request&&!isDestroyed())toast("连接正常");});}catch(Exception e){main.post(()->{if(ticket==request&&!isDestroyed())toast(e.getMessage());});}});}),lp(-1,48));gap(form,10);
        form.addView(button("高级连接设置",this::advancedConnection),lp(-1,48));gap(form,24);line(form);gap(form,20);
        form.addView(title("关于 EchoMusic Android",18));gap(form,10);form.addView(text("0.10.2 · 云端音乐库与逐字歌词版\n手机 / 平板 / 横竖屏 / 分屏自适应\n基于 hoowhoami/EchoMusic · GPL-3.0-only\n\n内置音乐连接，无需电脑或额外安装工具。支持扫码和短信登录、平台安全验证、在线搜索、本地音乐、队列、收藏、同步歌词和后台媒体控制。",14,MUTED));gap(form,20);
        form.addView(button("查看原项目",()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/hoowhoami/EchoMusic")))));
    }
    void advancedConnection(){advancedConnection(null);}
    void advancedConnection(Runnable afterSave){
        LinearLayout form=col();pad(form,20);form.addView(text(BuildConfig.MGV2000?"默认直连酷狗。仅在使用自己的音乐服务时填写地址，清空可恢复直连。":"默认使用内置连接。仅在需要连接自己的服务时填写地址；切换连接后需要重新登录。",14,MUTED));gap(form,10);
        EditText endpoint=new EditText(this);endpoint.setSingleLine();endpoint.setInputType(17);endpoint.setHint(Build.VERSION.SDK_INT<21?"留空直接扫码登录":"留空使用内置连接");endpoint.setText(ApiEndpoint.embedded(repo.endpoint())?"":repo.endpoint());form.addView(endpoint,lp(-1,56));
        TextView keyboard=BuildConfig.MGV2000?button("遥控器键盘",()->Mgv2000Input.open(this,endpoint,true)):null;
        if(keyboard!=null)form.addView(keyboard,lp(-1,48));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(BuildConfig.MGV2000?"设置音乐服务地址":"高级连接设置").setView(form).setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=endpoint.getText().toString().trim();if(BuildConfig.MGV2000&&!value.isEmpty()&&!value.contains("://"))value="http://"+value;try{
                if(!value.isEmpty()){java.net.URI uri=new java.net.URI(value);if((!"https".equals(uri.getScheme())&&!"http".equals(uri.getScheme()))||uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null)throw new Exception("请输入有效的 HTTP 或 HTTPS 服务地址");}
                String selected=ApiEndpoint.selected(value);repo.auth.configure(selected,selected.equals(repo.endpoint())?repo.auth.authorization():"");VerificationBroker.instance().cancelAll("连接设置已变化，请重试");onAccountChanged();dialog.dismiss();render();toast("连接设置已保存");if(afterSave!=null)afterSave.run();
            }catch(Exception error){endpoint.setError(error.getMessage()==null?"地址无效":error.getMessage());}
        }));
        if(keyboard!=null)dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        if(keyboard!=null){TvFocus.install(dialog.getWindow().getDecorView());keyboard.requestFocus();}
    }
    void onAccountChanged(){resetBatch();++cloudSerial;++cloudTrackSerial;++cloudOperation;cloudSyncing=false;cloudMutation=false;lastCloudResume=0;cloudMessage="";activeCloud=null;cloudFavoritesPage=false;if(page==7||page==11||page==13){page=2;tracks=new ArrayList<>();}diskPage=null;++request;busy=false;if(service!=null&&service.current()!=null&&service.current().source.isEmpty())service.stopPlayback();syncCloud(true);}
    void saveLoginQr(android.graphics.Bitmap bitmap){pendingLoginQr=bitmap;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("image/png");intent.putExtra(Intent.EXTRA_TITLE,"EchoMusic-登录二维码.png");startActivityForResult(intent,11);}
    void importMusic(){Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("audio/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,10);}
    @Override public void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(!BuildConfig.TV&&req==13){
        CloudLibrary.Session<AuthStore.Scope> expected=diskUploadPending;diskUploadPending=null;
        if(result==RESULT_OK&&data!=null&&data.getData()!=null)confirmCloudUpload(data.getData(),expected);
        return;
    }if(req==12){Song selected=pendingLyricImport;pendingLyricImport=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&selected!=null){Uri uri=data.getData();io.execute(()->{try(java.io.InputStream input=getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){if(input==null)throw new java.io.IOException();byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>2*1024*1024)throw new java.io.IOException("歌词文件过大");}String raw=Lyrics.decodeFile(bytes.toByteArray());if(raw.trim().isEmpty())throw new java.io.IOException("歌词内容为空");lyricsPanel.imported(selected,raw);}catch(Exception error){main.post(()->{if(!isDestroyed())toast("导入歌词失败："+error.getMessage());});}});}return;}if(req==11){android.graphics.Bitmap image=pendingLoginQr;pendingLoginQr=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&image!=null){try(java.io.OutputStream output=getContentResolver().openOutputStream(data.getData())){if(output==null||!image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))throw new java.io.IOException();toast("二维码已保存，请及时完成扫码确认");}catch(Exception error){toast("二维码保存失败，请重试");}}return;}if(req!=10||result!=RESULT_OK||data==null)return;List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());List<Song> local=repo.saved("local");int count=0;for(Uri uri:uris){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}String name="本地音频";try(Cursor cursor=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}catch(Exception ignored){}String key=uri.toString();if(local.stream().noneMatch(s->s.key().equals(key))){local.add(new Song(key,name,"本地音乐","","","",key));count++;}}repo.save("local",local);toast("已导入 "+count+" 首音乐");if(page==9){localAudioSongs=null;render();}else openSaved("local","本地音乐");}
    void play(List<Song> songs,int index){if(service==null){toast("播放器正在初始化");return;}startMusicService(new Intent(this,PlaybackService.class));service.play(new ArrayList<>(songs),index);updatePlayer();if(BuildConfig.TV)main.postDelayed(this::openPlayer,200);}
    private FrameLayout miniCover;private String miniCoverKey="";private TextView miniLike,peekTitle,peekArtist,peekLine,peekPlay,peekLike,peekTime;private ImageView peekArt;private TextView peekPlaceholder;private SeekBar peekProgress;private PopupWindow playbackPeek;
    private final Runnable hidePlaybackPeek=()->{if(playbackPeek!=null){PopupWindow old=playbackPeek;playbackPeek=null;old.dismiss();}clearPeek();};
    private void clearPeek(){peekTitle=null;peekArtist=null;peekLine=null;peekPlay=null;peekLike=null;peekTime=null;peekArt=null;peekPlaceholder=null;peekProgress=null;}
    private TextView touchControl(String label,String description,Runnable action){TextView view=new MusicIconView(this,24);view.setText(label);view.setTextColor(INK);view.setGravity(Gravity.CENTER);view.setContentDescription(description);view.setOnClickListener(v->action.run());return view;}
    void skipTouch(boolean forward){if(service==null||service.queue().isEmpty())return;startMusicService(new Intent(this,PlaybackService.class));if(forward)service.next();else service.playAt((service.queueIndex()-1+service.queue().size())%service.queue().size());updatePlayer();if(miniCover!=null&&!lyricsPanel.isOpen()){miniCover.setAlpha(.4f);miniCover.animate().alpha(1).setDuration(180).start();}if(playbackPeek!=null){main.removeCallbacks(hidePlaybackPeek);main.postDelayed(hidePlaybackPeek,5000);}}
    @android.annotation.TargetApi(21)
    void showPlaybackPeek(){if(BuildConfig.TV||isFinishing()||lyricsPanel.isOpen()||currentSong()==null)return;main.removeCallbacks(hidePlaybackPeek);hidePlaybackPeek.run();LinearLayout card=col();card.setPadding(dp(18),dp(16),dp(18),dp(12));GradientDrawable background=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xfff0f1fc,0xffffffff});background.setCornerRadius(dp(24));card.setBackground(background);
        LinearLayout header=row();FrameLayout art=new FrameLayout(this);art.setBackground(shape(0xffe8ebef,14));art.setClipToOutline(true);peekArt=new ImageView(this);peekArt.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(peekArt,new FrameLayout.LayoutParams(-1,-1));peekPlaceholder=text("♪",28,MUTED);peekPlaceholder.setGravity(Gravity.CENTER);art.addView(peekPlaceholder,new FrameLayout.LayoutParams(-1,-1));header.addView(art,lp(84,84));art.setOnClickListener(v->{hidePlaybackPeek.run();openPlayer();});LinearLayout meta=col();meta.setPadding(dp(14),0,dp(8),0);peekTitle=title("",19);peekTitle.setSingleLine();peekTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.addView(peekTitle);peekArtist=text("",13,MUTED);peekArtist.setSingleLine();meta.addView(peekArtist);gap(meta,8);peekLine=text("",12,MUTED);peekLine.setSingleLine();peekLine.setEllipsize(android.text.TextUtils.TruncateAt.END);meta.addView(peekLine);header.addView(meta,new LinearLayout.LayoutParams(0,-2,1));header.addView(touchControl("⌄","收起播放浮窗",hidePlaybackPeek),lp(32,44));card.addView(header);gap(card,10);
        peekProgress=new SeekBar(this);peekProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));peekProgress.setThumbTintList(android.content.res.ColorStateList.valueOf(INK));card.addView(peekProgress,lp(-1,26));peekProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){}public void onStartTrackingTouch(SeekBar b){main.removeCallbacks(hidePlaybackPeek);}public void onStopTrackingTouch(SeekBar b){seekCurrent(b.getProgress());main.postDelayed(hidePlaybackPeek,5000);}});peekTime=text("",11,MUTED);card.addView(peekTime);LinearLayout controls=row();peekLike=touchControl("♡","收藏当前歌曲",()->{if(currentSong()!=null)toggleSongFavorite(currentSong());});controls.addView(peekLike,new LinearLayout.LayoutParams(0,dp(52),1));controls.addView(touchControl("|◀","上一首",()->skipTouch(false)),new LinearLayout.LayoutParams(0,dp(52),1));peekPlay=touchControl("▶","播放或暂停",this::toggle);peekPlay.setBackground(shape(0xffe7ebee,28));controls.addView(peekPlay,lp(56,56));controls.addView(touchControl("▶|","下一首",()->skipTouch(true)),new LinearLayout.LayoutParams(0,dp(52),1));controls.addView(touchControl("☷","播放队列",()->{hidePlaybackPeek.run();showQueue();}),new LinearLayout.LayoutParams(0,dp(52),1));card.addView(controls);
        int width=Math.min(getResources().getDisplayMetrics().widthPixels-dp(24),dp(560));playbackPeek=new PopupWindow(card,width,-2,false);playbackPeek.setBackgroundDrawable(shape(Color.WHITE,24));playbackPeek.setOutsideTouchable(true);playbackPeek.setElevation(dp(6));playbackPeek.setTouchInterceptor((v,event)->{main.removeCallbacks(hidePlaybackPeek);int action=event.getActionMasked();if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)main.postDelayed(hidePlaybackPeek,5000);return false;});playbackPeek.setOnDismissListener(()->{playbackPeek=null;clearPeek();main.removeCallbacks(hidePlaybackPeek);});int[] location=new int[2];mini.getLocationOnScreen(location);int bottom=getResources().getDisplayMetrics().heightPixels-location[1]+dp(8);playbackPeek.showAtLocation(root,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL,0,bottom);updatePlayer();main.postDelayed(hidePlaybackPeek,5000);
    }
    private void startMusicService(Intent intent){if(Build.VERSION.SDK_INT>=26)super.startForegroundService(intent);else startService(intent);}
    void toggle(){if(service==null)return;if(service.current()==null){toast("先选择一首音乐");return;}startMusicService(new Intent(this,PlaybackService.class).setAction(PlaybackService.TOGGLE));}
    void buildMini(){mini=new SwipePlayerBar(this,()->skipTouch(false),()->skipTouch(true));FrameLayout thumb=HandheldHome.cover(this,artworkLoader,currentSong(),42);mini.addView(thumb);thumb.setOnClickListener(v->openPlayer());miniCover=thumb;miniCoverKey=currentSong()==null?"":currentSong().key();mini.setBackgroundColor(Color.WHITE);mini.setPadding(dp(14),dp(8),dp(8),dp(8));LinearLayout labels=col();labels.setPadding(dp(10),0,dp(4),0);miniTitle=title("选择一首喜欢的歌",14);miniTitle.setSingleLine();miniTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);miniSubtitle=text("左右滑动切换歌曲",12,MUTED);miniSubtitle.setSingleLine();labels.addView(miniTitle);labels.addView(miniSubtitle);mini.addView(labels,new LinearLayout.LayoutParams(0,-2,1));labels.setOnClickListener(v->openPlayer());miniLike=touchControl("♡","收藏当前歌曲",()->{if(currentSong()!=null)toggleSongFavorite(currentSong());});mini.addView(miniLike,lp(40,48));miniPlay=touchControl("▶","播放或暂停",this::toggle);mini.addView(miniPlay,lp(42,48));mini.addView(touchControl("☷","播放队列",this::showQueue),lp(42,48));root.addView(mini,lp(-1,64));}
    void previousTrack(){if(service!=null)startMusicService(new Intent(this,PlaybackService.class).setAction(PlaybackService.PREVIOUS));}
    void nextTrack(){if(service!=null)startMusicService(new Intent(this,PlaybackService.class).setAction(PlaybackService.NEXT));}
    void openPlayer(){if(lyricsPanel.isOpen())lyricsPanel.dismiss();else lyricsPanel.open();}
    void bindTheaterTitle(TextView title,TextView artist){playerTitle=title;playerArtist=artist;}
    void buildPlayer(LinearLayout panel,boolean modal){buildPlayerArtwork(panel,modal);buildPlayerControls(panel);}
    void buildPlayerArtwork(LinearLayout panel,boolean modal){
        panel.addView(text("N O W   P L A Y I N G",12,GREEN));gap(panel,22);
        FrameLayout art=new FrameLayout(this){@Override protected void onMeasure(int widthSpec,int heightSpec){int size=View.MeasureSpec.getSize(widthSpec);super.onMeasure(View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size,View.MeasureSpec.EXACTLY));}};
        art.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff07515d,0xff248a97}));playerArtwork=new ImageView(this);playerArtwork.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(playerArtwork,new FrameLayout.LayoutParams(-1,-1));artworkPlaceholder=text("♫",72,0xffe8ffff);artworkPlaceholder.setGravity(Gravity.CENTER);art.addView(artworkPlaceholder,new FrameLayout.LayoutParams(-1,-1));art.setOnClickListener(v->openPlayer());art.setContentDescription("打开或收起完整播放页");panel.addView(art,lp(-1,-2));gap(panel,20);
        playerTitle=title("等待音乐响起",24);panel.addView(playerTitle);gap(panel,8);playerArtist=text("从音乐库或搜索中选择歌曲",14,MUTED);panel.addView(playerArtist);playerTitle.setOnClickListener(v->openPlayer());playerArtist.setOnClickListener(v->openPlayer());gap(panel,12);
    }
    void buildPlayerControls(LinearLayout panel){
        playerStatus=text("",12,GREEN);panel.addView(playerStatus);progress=new SeekBar(this);panel.addView(progress,lp(-1,48));progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean user){}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){if(service!=null)service.seek(b.getProgress());}});
        playerTime=text("0:00 / 0:00",12,MUTED);panel.addView(playerTime);gap(panel,18);
        LinearLayout controls=row();controls.setGravity(Gravity.CENTER);controls.addView(button("|◀",()->{if(service!=null&&service.current()!=null){startMusicService(new Intent(this,PlaybackService.class).setAction(PlaybackService.PREVIOUS));}}),lp(64,56));
        playerPlay=button("▶",this::toggle);LinearLayout.LayoutParams p=lp(80,64);p.leftMargin=dp(10);p.rightMargin=dp(10);controls.addView(playerPlay,p);controls.addView(button("▶|",()->{if(service!=null&&service.current()!=null)startMusicService(new Intent(this,PlaybackService.class).setAction(PlaybackService.NEXT));}),lp(64,56));panel.addView(controls);gap(panel,18);
        LinearLayout extras=row();playerLike=button("♡",()->{if(service!=null&&service.current()!=null){toggleSongFavorite(service.current());}});extras.addView(playerLike,new LinearLayout.LayoutParams(0,dp(48),1));playerRepeat=button("循环",()->{if(service!=null){service.repeat=(service.repeat+1)%3;updatePlayer();}});extras.addView(playerRepeat,new LinearLayout.LayoutParams(0,dp(48),1));extras.addView(button("下载",this::downloadCurrent),new LinearLayout.LayoutParams(0,dp(48),1));extras.addView(button("队列",this::showQueue),new LinearLayout.LayoutParams(0,dp(48),1));panel.addView(extras);gap(panel,12);lyricPreview=text("正在准备歌词…",15,GREEN);lyricPreview.setGravity(Gravity.CENTER);lyricPreview.setOnClickListener(v->showLyrics());panel.addView(lyricPreview,lp(-1,64));panel.addView(button("打开逐字歌词",this::showLyrics),lp(-1,48));
    }
    void bindLyricArtwork(ImageView image,TextView fallback,Song song){if(image!=null&&fallback!=null)artworkLoader.bind(image,fallback,song);}
    private TextView theaterIcon(String label,String description,Runnable action){TextView view=text(label,BuildConfig.TV?20:26,Color.WHITE);view.setGravity(Gravity.CENTER);view.setContentDescription(description);view.setOnClickListener(v->action.run());return view;}
    void buildTheaterControls(LinearLayout panel){if(!BuildConfig.TV){buildTouchTransport(panel);return;}

        panel.setPadding(dp(8),dp(4),dp(8),dp(6));playerStatus=text("",12,0xffe0edf3);panel.addView(playerStatus);progress=new SeekBar(this);if(Build.VERSION.SDK_INT>=21){progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x668faebb));progress.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));}panel.addView(progress,lp(-1,28));progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int p,boolean user){
                if(BuildConfig.TV&&!BuildConfig.MGV2000&&user)seekCurrent(p);
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){seekCurrent(b.getProgress());}
        });
        if(BuildConfig.MGV2000){
            progress.setFocusable(true);
            progress.setContentDescription("播放进度，左右键快退或快进 5 秒");
            progress.setOnKeyListener((v,key,event)->{
                if(key!=KeyEvent.KEYCODE_DPAD_LEFT&&key!=KeyEvent.KEYCODE_DPAD_RIGHT)return false;
                if(event.getAction()==KeyEvent.ACTION_DOWN&&service!=null){
                    service.seekRelative(key==KeyEvent.KEYCODE_DPAD_RIGHT?5000:-5000);
                    updatePlayer();
                }
                return true;
            });
        }
        playerTime=text("",11,0xffe0edf3);panel.addView(playerTime);LinearLayout controls=row();controls.setGravity(Gravity.CENTER);playerLike=theaterIcon("♡","收藏当前歌曲",()->{if(currentSong()!=null)toggleSongFavorite(currentSong());});controls.addView(playerLike,new LinearLayout.LayoutParams(0,dp(56),1));playerRepeat=theaterIcon("循环","切换循环模式",()->{if(service!=null){service.repeat=(service.repeat+1)%3;updatePlayer();}});playerRepeat.setTextSize(11);controls.addView(playerRepeat,new LinearLayout.LayoutParams(0,dp(56),1));controls.addView(theaterIcon(BuildConfig.TV?"上一首":"|◀","上一首",this::previousTrack),new LinearLayout.LayoutParams(0,dp(56),1));playerPlay=theaterIcon("▶","播放或暂停",this::toggle);playerPlay.setTextSize(36);controls.addView(playerPlay,new LinearLayout.LayoutParams(0,dp(64),1));controls.addView(theaterIcon(BuildConfig.TV?"下一首":"▶|","下一首",this::nextTrack),new LinearLayout.LayoutParams(0,dp(56),1));controls.addView(theaterIcon(BuildConfig.TV?"队列":"☷","播放队列",this::showQueue),new LinearLayout.LayoutParams(0,dp(56),1));if(!BuildConfig.TV)controls.addView(theaterIcon("↓","下载到本地",this::downloadCurrent),new LinearLayout.LayoutParams(0,dp(56),1));if(BuildConfig.TV)controls.addView(theaterIcon("原唱/伴奏","切换原唱与伴奏",()->{if(service!=null)service.audioMode(service.audioMode().equals("original")?"accompaniment":"original");}),new LinearLayout.LayoutParams(0,dp(56),1));panel.addView(controls);lyricPreview=null;
    }
    @android.annotation.TargetApi(21)
    void buildTouchTransport(LinearLayout panel){playerStatus=text("",12,0xffe0edf3);panel.addView(playerStatus);progress=new SeekBar(this);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));progress.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));panel.addView(progress,lp(-1,28));progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean u){}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){seekCurrent(b.getProgress());}});playerTime=text("",12,0xffe0edf3);panel.addView(playerTime);LinearLayout auxiliary=row();playerLike=theaterIcon("♡","云端收藏",()->{if(currentSong()!=null)toggleSongFavorite(currentSong());});playerRepeat=theaterIcon("循环","播放模式",()->{if(service!=null){service.repeat=(service.repeat+1)%3;updatePlayer();}});playerRepeat.setTextSize(12);auxiliary.addView(playerLike,new LinearLayout.LayoutParams(0,dp(44),1));auxiliary.addView(playerRepeat,new LinearLayout.LayoutParams(0,dp(44),1));auxiliary.addView(theaterIcon("↓","下载",this::downloadCurrent),new LinearLayout.LayoutParams(0,dp(44),1));panel.addView(auxiliary);LinearLayout controls=row();controls.setGravity(Gravity.CENTER);controls.addView(theaterIcon("◀","上一首",this::previousTrack),new LinearLayout.LayoutParams(0,dp(64),1));playerPlay=theaterIcon("▶","播放暂停",this::toggle);playerPlay.setTextSize(34);playerPlay.setBackground(shape(0x33ffffff,40));controls.addView(playerPlay,lp(76,76));controls.addView(theaterIcon("▶|","下一首",this::nextTrack),new LinearLayout.LayoutParams(0,dp(64),1));controls.addView(theaterIcon("☷","队列",this::showQueue),lp(48,64));panel.addView(controls);}
    private LinearLayout lightControlRow;
    void fullLyricsControls(boolean full){if(BuildConfig.TV||lightControlRow==null)return;for(int i=0;i<lightControlRow.getChildCount();i++){View child=lightControlRow.getChildAt(i);LinearLayout.LayoutParams params=(LinearLayout.LayoutParams)child.getLayoutParams();params.height=dp(full?48:64);if(child==playerPlay)params.width=dp(full?48:64);child.setLayoutParams(params);}}
    @android.annotation.TargetApi(21)
    void buildLightTransport(LinearLayout panel){
        panel.setPadding(dp(2),dp(8),dp(2),0);playerStatus=text("",12,MUTED);playerStatus.setVisibility(View.GONE);panel.addView(playerStatus);progress=new SeekBar(this);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));progress.setThumbTintList(android.content.res.ColorStateList.valueOf(INK));panel.addView(progress,lp(-1,24));progress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int n,boolean u){}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){seekCurrent(b.getProgress());}});playerTime=text("",11,MUTED);panel.addView(playerTime);LinearLayout controls=row();lightControlRow=controls;playerRepeat=touchControl("↻","播放模式",()->{if(service!=null){service.repeat=(service.repeat+1)%3;updatePlayer();}});controls.addView(playerRepeat,new LinearLayout.LayoutParams(0,dp(64),1));controls.addView(touchControl("|◀","上一首",()->skipTouch(false)),new LinearLayout.LayoutParams(0,dp(64),1));playerPlay=new MusicIconView(this,28);playerPlay.setText("▶");playerPlay.setTextColor(Color.WHITE);playerPlay.setGravity(Gravity.CENTER);playerPlay.setBackground(shape(0xff10191e,32));playerPlay.setContentDescription("播放或暂停");playerPlay.setOnClickListener(v->toggle());controls.addView(playerPlay,lp(64,64));controls.addView(touchControl("▶|","下一首",()->skipTouch(true)),new LinearLayout.LayoutParams(0,dp(64),1));controls.addView(touchControl("☷","播放队列",this::showQueue),new LinearLayout.LayoutParams(0,dp(64),1));playerLike=touchControl("♡","收藏",()->{if(currentSong()!=null)toggleSongFavorite(currentSong());});playerLike.setVisibility(View.GONE);panel.addView(playerLike);panel.addView(controls);lyricPreview=null;
    }
    @android.annotation.TargetApi(21)
    void tintTabletTransport(LinearLayout panel){if(getResources().getConfiguration().screenWidthDp>getResources().getConfiguration().screenHeightDp){LinearLayout.LayoutParams centered=new LinearLayout.LayoutParams(Math.min(dp(600),getResources().getDisplayMetrics().widthPixels-dp(72)),dp(64));centered.gravity=Gravity.CENTER_HORIZONTAL;lightControlRow.setLayoutParams(centered);}tintTabletViews(panel);progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));progress.setThumbTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x55ffffff));playerPlay.setBackground(shape(0x22ffffff,40));playerPlay.setTextColor(Color.WHITE);playerTime.setTextColor(0xffe4e9e9);}
    private void tintTabletViews(View view){if(view instanceof TextView)((TextView)view).setTextColor(Color.WHITE);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)tintTabletViews(group.getChildAt(i));}}
    Song currentSong(){return service==null?null:service.current();}
    int currentPosition(){return service==null?0:service.position();}
    int currentDuration(){return service==null?0:service.duration();}
    boolean playbackSeeking(){return BuildConfig.MGV2000&&service!=null&&service.seeking();}
    void seekCurrent(int position){if(service!=null)service.seek(position);}
    private AlertDialog recognitionDialog;
    void recognizeSong(){
        if(BuildConfig.TV||recognitionDialog!=null)return;
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=android.content.pm.PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},72);
            return;
        }
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("听歌识曲")
            .setMessage("将录制约 8 秒环境声音，并发送给酷狗识别。").setNegativeButton("取消",null)
            .setPositiveButton("开始录音",(d,w)->captureSong()).create();
        recognitionDialog=dialog;
        dialog.setOnDismissListener(d->{if(recognitionDialog==dialog)recognitionDialog=null;});
        dialog.show();
    }
    private void captureSong(){
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        LinearLayout body=col();
        pad(body,22);
        TextView stage=title("正在聆听",19);
        TextView hint=text("把设备靠近声源，减少周围噪声。",14,MUTED);
        body.addView(stage);
        gap(body,6);
        body.addView(hint);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(8);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(GREEN));
        gap(body,18);
        body.addView(progress,lp(-1,6));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("听歌识曲")
            .setView(body).setNegativeButton("取消",null).create();
        recognitionDialog=dialog;
        dialog.setOnDismissListener(d->{cancelled.set(true);if(recognitionDialog==dialog)recognitionDialog=null;});
        dialog.show();
        new Thread(()->{
            try{
                byte[] audio=SongRecognition.capture(cancelled,seconds->main.post(()->{
                    if(cancelled.get())return;
                    progress.setProgress(seconds);
                    hint.setText("已录制 "+seconds+" / 8 秒");
                }));
                if(cancelled.get())return;
                main.post(()->{
                    if(cancelled.get())return;
                    stage.setText("正在匹配曲库");
                    hint.setText("正在查找可播放的歌曲版本。");
                    progress.setIndeterminate(true);
                });
                List<Song> matches=repo.recognize(audio,cancelled::get);
                main.post(()->{
                    if(cancelled.get()||isDestroyed())return;
                    dialog.dismiss();
                    if(matches.isEmpty()){recognitionRetry("没有找到匹配歌曲，请靠近声源后重试。");return;}
                    showRecognitionMatches(matches);
                });
            }catch(Exception error){main.post(()->{
                if(cancelled.get()||isDestroyed())return;
                dialog.dismiss();
                recognitionRetry(error.getMessage()==null?"识曲失败，请重试。":error.getMessage());
            });}
        },"Echo-Recognition").start();
    }
    private void recognitionRetry(String message){
        new AlertDialog.Builder(this).setTitle("听歌识曲").setMessage(message)
            .setNegativeButton("关闭",null).setPositiveButton("再试一次",(d,w)->recognizeSong()).show();
    }
    private void showRecognitionMatches(List<Song> matches){
        LinearLayout body=col();pad(body,16);
        LocalAudioList adapter=new LocalAudioList();
        adapter.filter(matches,"");
        ListView list=new ListView(this);list.setDivider(null);list.setAdapter(adapter);
        body.addView(list,lp(-1,Math.min(dp(360),getResources().getDisplayMetrics().heightPixels/2)));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("识曲结果").setView(body)
            .setNegativeButton("关闭",null).setPositiveButton("再识一首",(d,w)->recognizeSong()).create();
        list.setOnItemClickListener((parent,view,position,id)->{dialog.dismiss();play(matches,position);});
        dialog.show();
    }
    void sleepTimer(){
        if(BuildConfig.TV||service==null)return;
        long remaining=service.sleepRemaining();
        int[] minutes={15,30,45,60,90,0};
        new AlertDialog.Builder(this)
            .setTitle(remaining>0?"定时关闭 · 还剩 "+((remaining+59999)/60000)+" 分钟":"定时关闭")
            .setItems(new String[]{"15 分钟","30 分钟","45 分钟","60 分钟","90 分钟","取消定时"},(d,which)->{
                if(service==null)return;
                service.sleepMinutes(minutes[which]);
                toast(minutes[which]==0?"已取消定时":"将在 "+minutes[which]+" 分钟后停止播放");
            }).setNegativeButton("返回",null).show();
    }
    void playbackQuality(){
        if(BuildConfig.TV)return;
        android.content.SharedPreferences prefs=getSharedPreferences("echo",0);
        String[] qualities={"128","320","flac"};
        int selected=Arrays.asList(qualities).indexOf(prefs.getString("playback_quality","128"));
        new AlertDialog.Builder(this).setTitle("在线播放音质")
            .setSingleChoiceItems(new String[]{"标准 · 128 kbps","高音质 · 320 kbps","无损 · FLAC"},selected,(d,which)->{
                prefs.edit().putString("playback_quality",qualities[which]).apply();
                if(service!=null&&selected!=which)service.changeQuality();
                d.dismiss();
            }).setNegativeButton("返回",null).show();
    }
    private String localAudioQuery="";
    private List<Song> localAudioSongs;
    void localAudio(){
        if(BuildConfig.TV)return;
        ++request;
        busy=false;
        page=9;
        localAudioQuery="";
        localAudioSongs=null;
        render();
    }
    private String localAudioPermission(){
        return Build.VERSION.SDK_INT>=33?Manifest.permission.READ_MEDIA_AUDIO:Manifest.permission.READ_EXTERNAL_STORAGE;
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results){
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if(requestCode==72){
            if(results.length>0&&results[0]==android.content.pm.PackageManager.PERMISSION_GRANTED)recognizeSong();
            else toast("听歌识曲需要麦克风权限");
        }
        if(requestCode==71&&page==9){
            localAudioSongs=null;
            render();
        }
    }
    private void localAudioPage(){
        header("本地音频","查找设备上的歌曲、录音与已导入的音频。");
        content.setFocusableInTouchMode(true);
        content.requestFocus();
        LinearLayout actions=row();
        actions.addView(button("音乐库",()->{page=2;render();}),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(button("本地下载",()->{page=8;render();}),new LinearLayout.LayoutParams(0,dp(48),1));
        actions.addView(button("导入文件",this::importMusic),new LinearLayout.LayoutParams(0,dp(48),1));
        content.addView(actions);
        gap(content,12);

        EditText search=new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setPadding(dp(16),0,dp(16),0);
        search.setBackground(shape(0xffe6eaee,22));
        search.setHint("搜索歌名、歌手或专辑");
        search.setText(localAudioQuery);
        content.addView(search,lp(-1,46));

        LinearLayout status=row();
        TextView summary=text("正在扫描设备…",13,MUTED);
        status.addView(summary,new LinearLayout.LayoutParams(0,-2,1));
        boolean allowed=checkSelfPermission(localAudioPermission())==android.content.pm.PackageManager.PERMISSION_GRANTED;
        TextView scan=button(allowed?"重新扫描":"授权扫描",()->{
            if(!allowed){requestPermissions(new String[]{localAudioPermission()},71);return;}
            localAudioSongs=null;
            render();
        });
        status.addView(scan,lp(-2,48));
        content.addView(status);

        ListView list=new ListView(this);
        content.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LocalAudioList adapter=new LocalAudioList();
        list.setAdapter(adapter);
        list.setDivider(null);
        list.setOnItemClickListener((parent,view,position,id)->{
            hideKeyboard(search);
            play(adapter.visible,position);
        });

        Runnable filter=()->{
            if(localAudioSongs==null)return;
            adapter.filter(localAudioSongs,localAudioQuery);
            if(!allowed)summary.setText("授权后可扫描设备 · "+adapter.getCount()+" 首已导入");
            else summary.setText(adapter.getCount()==0?(localAudioQuery.isEmpty()?"没有找到音频，可手动导入":"没有符合条件的音频"):adapter.getCount()+" 首音频");
        };
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){localAudioQuery=text.toString();filter.run();}
            public void afterTextChanged(android.text.Editable text){}
        });
        if(localAudioSongs!=null){filter.run();return;}

        LinearLayout screen=content;
        scan.setEnabled(false);
        io.execute(()->{
            Map<String,Song> songs=new LinkedHashMap<>();
            for(Song song:repo.saved("local"))if(song.local())songs.put(song.key(),song);
            boolean scanFailed=false;
            if(allowed){
                Uri base=android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                try(Cursor cursor=getContentResolver().query(base,new String[]{"_id","title","artist","album","duration"},null,null,"title COLLATE NOCASE")){
                    while(cursor!=null&&cursor.moveToNext()){
                        String uri=ContentUris.withAppendedId(base,cursor.getLong(0)).toString();
                        String name=cursor.getString(1),artist=cursor.getString(2),album=cursor.getString(3);
                        songs.put(uri,new Song(uri,name==null||name.isEmpty()?"本地音频":name,
                            artist==null||"<unknown>".equals(artist)?"本地音频":artist,
                            album==null?"":album,"","",uri,"","",cursor.getInt(4)));
                    }
                }catch(RuntimeException error){scanFailed=true;}
            }
            boolean failed=scanFailed;
            main.post(()->{
                if(isDestroyed()||page!=9||content!=screen)return;
                localAudioSongs=new ArrayList<>(songs.values());
                scan.setEnabled(true);
                filter.run();
                if(failed)toast("无法读取系统音频，仍可播放已导入的文件");
            });
        });
    }
    private final class LocalAudioList extends BaseAdapter {
        private final List<Song> visible=new ArrayList<>();
        void filter(List<Song> songs,String query){
            String needle=query.trim().toLowerCase(Locale.ROOT);
            visible.clear();
            for(Song song:songs)if(needle.isEmpty()||(song.title+" "+song.artist+" "+song.album).toLowerCase(Locale.ROOT).contains(needle))visible.add(song);
            notifyDataSetChanged();
        }
        @Override public int getCount(){return visible.size();}
        @Override public Object getItem(int position){return visible.get(position);}
        @Override public long getItemId(int position){return position;}
        @Override public View getView(int position,View recycled,ViewGroup parent){
            LinearLayout item=recycled instanceof LinearLayout?(LinearLayout)recycled:row();
            if(item.getChildCount()==0){
                pad(item,10);
                MusicIconView icon=new MusicIconView(MainActivity.this,21);
                icon.setText("library");icon.setTextColor(GREEN);
                item.addView(icon,lp(40,44));
                LinearLayout labels=col();
                TextView name=title("",16),artist=text("",13,MUTED);
                name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                artist.setSingleLine();artist.setEllipsize(android.text.TextUtils.TruncateAt.END);
                labels.addView(name);labels.addView(artist);
                item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                item.addView(text("",12,MUTED));
            }
            Song song=visible.get(position);
            LinearLayout labels=(LinearLayout)item.getChildAt(1);
            ((TextView)labels.getChildAt(0)).setText(HandheldHome.name(song.title));
            ((TextView)labels.getChildAt(1)).setText(song.artist);
            ((TextView)item.getChildAt(2)).setText(song.durationMs>0?time(song.durationMs):"");
            return item;
        }
    }
    void showLyrics(){lyricsPanel.open();}
    void importLyrics(){Song selected=currentSong();if(selected==null){toast("先选择一首歌");return;}pendingLyricImport=selected;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");startActivityForResult(intent,12);}
    void showQueue(){if(!BuildConfig.TV){showTouchQueue();return;}

        if(service==null)return;List<Song> queue=service.queue();long revision=service.queueRevision();if(queue.isEmpty()){toast("播放队列为空");return;}String[] names=new String[queue.size()];for(int i=0;i<queue.size();i++)names[i]=(i==service.queueIndex()?"▶ ":"")+(i+1)+". "+queue.get(i).title;
        new AlertDialog.Builder(this).setTitle("播放队列 · "+queue.size()+" 首").setItems(names,(d,which)->{
            String[] choices={"播放这首","上移","下移","移出队列"};new AlertDialog.Builder(this).setTitle(queue.get(which).title).setItems(choices,(menu,action)->{
                if(service==null)return;boolean result;
                if(action==0){startMusicService(new Intent(this,PlaybackService.class));result=service.playAt(which,revision);}else if(action==1)result=service.moveQueue(which,which-1,revision);else if(action==2)result=service.moveQueue(which,which+1,revision);else result=service.removeQueue(which,revision);
                if(!result)toast("队列已变化或已到边界，请重新选择");
            }).setNegativeButton("取消",null).show();
        }).setNeutralButton("清空队列",(d,w)->new AlertDialog.Builder(this).setTitle("清空播放队列？").setMessage("将停止当前播放，不会删除本地歌曲或云端歌单。").setNegativeButton("取消",null).setPositiveButton("清空",(confirm,action)->{if(service!=null&&!service.clearQueue(revision))toast("队列已变化，请重新操作");}).show()).setNegativeButton("关闭",null).show();
    }
    void showTouchQueue(){if(service==null)return;List<Song> songs=service.queue();if(songs.isEmpty()){toast("播放队列为空");return;}long revision=service.queueRevision();Dialog sheet=new Dialog(this);LinearLayout panel=col();pad(panel,20);panel.setBackground(shape(Color.WHITE,24));LinearLayout header=row();header.addView(title("播放队列 · "+songs.size(),20),new LinearLayout.LayoutParams(0,dp(52),1));header.addView(button("关闭",sheet::dismiss));panel.addView(header);ListView list=new ListView(this);list.setDivider(null);list.setAdapter(new BaseAdapter(){public int getCount(){return songs.size();}public Object getItem(int i){return songs.get(i);}public long getItemId(int i){return i;}public View getView(int i,View old,ViewGroup parent){LinearLayout item=row();item.setPadding(0,dp(10),0,dp(10));item.addView(HandheldHome.cover(MainActivity.this,artworkLoader,songs.get(i),44));LinearLayout labels=col();labels.setPadding(dp(12),0,dp(8),0);TextView name=text(HandheldHome.name(songs.get(i).title),15,i==service.queueIndex()?GREEN:INK);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);labels.addView(name);labels.addView(text(songs.get(i).artist,12,MUTED));item.addView(labels,new LinearLayout.LayoutParams(0,dp(52),1));TextView more=button("⋮",()->{String[] options={"上移","下移","移出队列"};new AlertDialog.Builder(MainActivity.this).setItems(options,(d,choice)->{boolean ok=choice==0?service.moveQueue(i,i-1,revision):choice==1?service.moveQueue(i,i+1,revision):service.removeQueue(i,revision);if(ok)sheet.dismiss();else toast("队列已变化，请重新打开");}).show();});item.addView(more,lp(48,48));return item;}});list.setOnItemClickListener((parent,v,index,id)->{startMusicService(new Intent(this,PlaybackService.class));if(service.playAt(index,revision))sheet.dismiss();else toast("队列已变化，请重新打开");});panel.addView(list,new LinearLayout.LayoutParams(-1,0,1));panel.addView(button("清空队列",()->new AlertDialog.Builder(this).setTitle("清空播放队列？").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{if(service.clearQueue(revision))sheet.dismiss();}).show()),lp(-1,48));sheet.setContentView(panel);sheet.show();Window window=sheet.getWindow();window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.3f);window.setGravity(Gravity.BOTTOM);window.setLayout(Math.min(getResources().getDisplayMetrics().widthPixels,dp(640)),Math.round(getResources().getDisplayMetrics().heightPixels*.68f));list.setSelection(service.queueIndex());}
    void updatePlayer(){if(service==null)return;Song song=service.current();boolean playing=service.playing()||(!BuildConfig.TV&&service.loading()&&service.playRequested());String name=song==null?"选择一首喜欢的歌":song.title;String subtitle=service.loading()?"正在加载…":!service.error().isEmpty()?service.error():song==null?"让音乐陪着你":song.artist;
        if(song!=null&&!favoriteTrackKey.equals(song.key())){favoriteTrackKey=song.key();syncCloud(false);}
        if(playerArtwork!=null)artworkLoader.bind(playerArtwork,artworkPlaceholder,song);
        lyricsPanel.track(song);if(lyricPreview!=null)lyricPreview.setText(lyricsPanel.preview());
        if(!BuildConfig.TV&&miniCover!=null&&song!=null&&!song.key().equals(miniCoverKey)){miniCoverKey=song.key();miniCover.removeAllViews();miniCover.addView(HandheldHome.cover(this,artworkLoader,song,44),new FrameLayout.LayoutParams(-1,-1));}if(!BuildConfig.TV&&miniLike!=null){miniLike.setText(song!=null&&repo.favorite(song)?"♥":"♡");miniLike.setTextColor(song!=null&&repo.favorite(song)?0xffee6277:INK);}if(playbackPeek!=null&&peekTitle!=null){peekTitle.setText(HandheldHome.name(name));peekArtist.setText(song==null?"":song.artist);peekLine.setText(lyricsPanel.preview());peekPlay.setText(playing?"Ⅱ":"▶");peekLike.setText(song!=null&&repo.favorite(song)?"♥":"♡");peekLike.setTextColor(song!=null&&repo.favorite(song)?0xffee6277:INK);if(!peekProgress.isPressed()){peekProgress.setMax(Math.max(1,currentDuration()));peekProgress.setProgress(currentPosition());}peekTime.setText(time(currentPosition())+" / "+time(currentDuration()));artworkLoader.bind(peekArt,peekPlaceholder,song);}
        if(miniTitle!=null){miniTitle.setText(HandheldHome.name(name));miniSubtitle.setText(subtitle);miniPlay.setText(playing?"Ⅱ":"▶");miniPlay.setContentDescription(playing?"暂停":"播放");}
        if(playerTitle!=null){playerTitle.setText(BuildConfig.TV?name:HandheldHome.name(name));playerArtist.setText(song==null?"从音乐库或搜索中选择歌曲":song.artist);playerStatus.setText(service.loading()?"正在加载音频…":service.error());}if(playerPlay!=null){playerPlay.setText(BuildConfig.TV?(playing?"暂停":"播放"):(playing?"Ⅱ":"▶"));playerPlay.setContentDescription(playing?"暂停":"播放");playerLike.setText(BuildConfig.TV?(song!=null&&repo.favorite(song)?"已收藏":"收藏"):(song!=null&&repo.favorite(song)?"♥":song!=null&&!repo.favoriteKnown(song)?"♡·":"♡"));playerLike.setEnabled(!cloudMutation&&song!=null&&song.cloudIdentified());playerLike.setContentDescription("云端收藏");playerRepeat.setText(BuildConfig.TV?(service.repeat==0?"列表循环":service.repeat==1?"单曲循环":"随机播放"):(service.repeat==1?"↻₁":service.repeat==2?"⇄":"↻"));if(progress!=null&&!progress.isPressed()){progress.setMax(Math.max(service.duration(),1));progress.setProgress(BuildConfig.MGV2000?service.seekBarPosition():service.position());}playerTime.setText(String.format(Locale.ROOT,"%s / %s",time(service.position()),time(service.duration())));}
    }
    static String time(int ms){int s=Math.max(ms,0)/1000;return String.format(Locale.ROOT,"%d:%02d",s/60,s%60);}
    void hideKeyboard(View v){((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(v.getWindowToken(),0);v.clearFocus();}
    boolean handleTvSeekKey(KeyEvent event){
        if(!BuildConfig.MGV2000)return false;
        int key=event.getKeyCode();
        if(key!=KeyEvent.KEYCODE_MEDIA_FAST_FORWARD&&key!=KeyEvent.KEYCODE_MEDIA_REWIND)return false;
        if(event.getAction()==KeyEvent.ACTION_DOWN&&service!=null){
            service.seekRelative(key==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD?5000:-5000);
            updatePlayer();
        }
        return true;
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){if(handleTvSeekKey(event))return true;if(BuildConfig.TV&&event.getAction()==KeyEvent.ACTION_DOWN){switch(event.getKeyCode()){case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:toggle();return true;case KeyEvent.KEYCODE_MEDIA_NEXT:nextTrack();return true;case KeyEvent.KEYCODE_MEDIA_PREVIOUS:previousTrack();return true;default:break;}}return super.dispatchKeyEvent(event);}
    @Override public void onBackPressed(){if(BuildConfig.TV){if(page!=2){++request;busy=false;page=2;render();}else super.onBackPressed();return;}if(batchMode&&playlistView!=null){playlistView.setBatch(false);return;}if(isPlaylistPage()||page==10||page==14){backFromTracks();return;}if(page==6||page==8||page==9){++request;busy=false;page=2;render();}else if(page!=0){++request;busy=false;page=0;render();}else super.onBackPressed();}
}

