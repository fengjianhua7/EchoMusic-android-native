package cc.echomusic.android;

import android.app.*;
import android.content.*;
import android.media.*;
import android.media.session.*;
import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns playback independently of the activity, including lock-screen controls. */
public class PlaybackService extends Service {
    public static final String CHANGED="cc.echomusic.android.PLAYBACK_CHANGED";
    public static final String TOGGLE="toggle", NEXT="next", PREVIOUS="previous", STOP="stop";
    private static final String CHANNEL="playback";
    private final LocalBinder binder=new LocalBinder();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private MusicRepository repository;
    private PlaybackCache cache;
    private Runnable cacheRequest;
    private ExecutorService notificationIo;
    private ArtworkLoader notificationArt;
    private android.graphics.Bitmap cover;
    private String coverKey="",notificationLyric="";
    private Future<?> coverJob;
    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusCompat focus;
    private final PlaybackQueue<Song> queue=new PlaybackQueue<>();
    private int generation=0;
    private boolean prepared=false, loading=false, resumeAfterFocus=false, wanted=false;
    private String error="";
    private String audioMode="original";private int switchPosition;private boolean switchPaused;
    public String audioMode(){return audioMode;}
    public void audioMode(String mode){if(!mode.equals("original")&&!mode.equals("accompaniment"))throw new IllegalArgumentException();if(mode.equals(audioMode))return;if(current()==null){error="先选择歌曲";publish();return;}switchPosition=position();switchPaused=!wanted;audioMode=mode;load();}
    private StatusLyrics statusLyrics;private LanRemote remote;private float rate=1;
    public int repeat=0; // 0: queue, 1: one, 2: shuffle
    public class LocalBinder extends Binder { PlaybackService service() { return PlaybackService.this; } }
    public IBinder onBind(Intent intent) { return binder; }
    @Override public void onCreate() {
        super.onCreate(); repository=new MusicRepository(this); audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        if(Build.VERSION.SDK_INT>=26)((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(new NotificationChannel(CHANNEL,"音乐播放",NotificationManager.IMPORTANCE_LOW));
        focus=new AudioFocusCompat(audio,Build.VERSION.SDK_INT>=26?attributes():null,change->{
                if(change==AudioManager.AUDIOFOCUS_GAIN) { if(player!=null)player.setVolume(1,1); if(resumeAfterFocus){resumeAfterFocus=false; resume();} }
                else if(change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) { if(player!=null)player.setVolume(.2f,.2f); }
                else { boolean wasPlaying=playing(); pause(); resumeAfterFocus=change==AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && wasPlaying; }
            },main);
        if(Build.VERSION.SDK_INT>=21){
        session=new MediaSession(this,"EchoMusic");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay(){resume();}
            @Override public void onPause(){pause();}
            @Override public void onSkipToNext(){next();}
            @Override public void onSkipToPrevious(){previous();}
            @Override public void onSeekTo(long pos){seek((int)pos);}
            @Override public void onStop(){stopPlayback();}
        });
        session.setActive(true);
        }
        IntentFilter filter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisy,filter,Context.RECEIVER_NOT_EXPORTED); else registerReceiver(noisy,filter);
        queue.replace(BuildConfig.TV?repository.saved("queue").stream().filter(song->!song.local()).collect(java.util.stream.Collectors.toList()):repository.saved("queue"),getSharedPreferences("echo",0).getInt("queue_index",0));
        if(!BuildConfig.TV){
            cache=PlaybackCache.get(this);
            notificationIo=Executors.newSingleThreadExecutor();
            notificationArt=new ArtworkLoader(this,repository,notificationIo);
            statusLyrics=new StatusLyrics(this,repository);
        }
        publish();if(getSharedPreferences("echo",0).getBoolean("remote_enabled_v1",false))setRemoteEnabled(true);
    }
    private final BroadcastReceiver noisy=new BroadcastReceiver(){public void onReceive(Context c,Intent i){pause();}};
    @android.annotation.TargetApi(21)
    private AudioAttributes attributes(){return new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();}
    @Override public int onStartCommand(Intent i,int flags,int id) {
        // A foreground start must publish its notification before resolving network audio.
        foreground();
        if(i!=null) {
            String a=i.getAction();
            if(TOGGLE.equals(a))toggle(); else if(NEXT.equals(a))next(); else if(PREVIOUS.equals(a))previous(); else if(STOP.equals(a))stopPlayback();
        }
        return START_NOT_STICKY;
    }
    private long sleepDeadline;
    private final Runnable sleepStop=()->{sleepDeadline=0;stopPlayback();};
    long sleepRemaining(){return Math.max(0,sleepDeadline-SystemClock.elapsedRealtime());}
    void sleepMinutes(int minutes){
        main.removeCallbacks(sleepStop);
        sleepDeadline=minutes>0?SystemClock.elapsedRealtime()+minutes*60000L:0;
        if(minutes>0)main.postDelayed(sleepStop,minutes*60000L);
    }
    void changeQuality(){
        Song song=current();
        if(!BuildConfig.TV&&song!=null&&!song.local()&&prepared){
            switchPosition=position();
            switchPaused=!wanted;
            load();
        }
    }
    public boolean remoteEnabled(){return remote!=null;}
    public String remoteAddresses(){return remote==null?"远控未开启":remote.addresses();}
    public String setRemoteEnabled(boolean enabled){if(enabled&&remote==null){try{remote=new LanRemote(this);if(Build.VERSION.SDK_INT>=26)startForegroundService(new Intent(this,PlaybackService.class));else startService(new Intent(this,PlaybackService.class));foreground();}catch(Exception e){if(remote!=null){remote.close();remote=null;}return "远控启动失败，端口 1987 可能已被占用";}}else if(!enabled&&remote!=null){remote.close();remote=null;if(!playing()&&!loading)finishForeground(1);}getSharedPreferences("echo",0).edit().putBoolean("remote_enabled_v1",enabled).apply();return enabled?"局域网远控已开启":"局域网远控已关闭";}
    public int remoteVolume(){int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);return max==0?0:Math.round(audio.getStreamVolume(AudioManager.STREAM_MUSIC)*100f/max);}
    public void remoteVolume(int volume){audio.setStreamVolume(AudioManager.STREAM_MUSIC,Math.round(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)*volume/100f),0);}
    public String remoteMode(){return repeat==1?"single":repeat==2?"random":repeat==3?"sequential":"list";}
    public void remoteMode(String mode){switch(mode){case "list":repeat=0;break;case "single":repeat=1;break;case "random":repeat=2;break;case "sequential":repeat=3;break;default:throw new IllegalArgumentException();}publish();}
    public float remoteRate(){return rate;}
    public void remoteRate(float value){if(Build.VERSION.SDK_INT<23&&value!=1)throw new IllegalArgumentException("此系统不支持变速");rate=value;if(Build.VERSION.SDK_INT>=23&&prepared&&player!=null){boolean running=playing();player.setPlaybackParams(player.getPlaybackParams().setSpeed(rate));if(!running)player.pause();}publish();}
    private void detach(){if(remote!=null)foreground();else finishForeground(2);}
    public List<Song> queue(){return queue.snapshot();}
    public Song current(){return queue.current();}
    public boolean playing(){return prepared && player!=null && player.isPlaying();}
    public boolean loading(){return loading;}
    public boolean playRequested(){return wanted;}
    public String error(){return error;}
    public int position(){return prepared&&player!=null?player.getCurrentPosition():0;}
    public int duration(){return prepared&&player!=null?player.getDuration():0;}
    public void play(List<Song> songs,int at) {
        if(songs.isEmpty()||at<0||at>=songs.size())return;if(BuildConfig.TV&&songs.stream().anyMatch(Song::local)){error="电视版仅播放在线歌曲";publish();return;}
        queue.replace(songs,at);saveQueue();load();
    }
    public void playAt(int at){if(at>=0&&at<queue.size()){queue.select(at);load();}}
    public long queueRevision(){return queue.revision();}
    public int queueIndex(){return queue.index();}
    public boolean playAt(int at,long expected){if(!queue.select(at,expected))return false;load();return true;}
    void appendAll(List<Song> songs){queue.appendAll(songs);saveQueue();publish();}
    public void append(Song s){queue.append(s);saveQueue();publish();}
    public void insertNext(Song song){queue.insertNext(song);saveQueue();publish();}
    private void saveQueue(){repository.save("queue",queue.snapshot());getSharedPreferences("echo",0).edit().putInt("queue_index",queue.index()).apply();}
    public boolean moveQueue(int from,int to,long revision){if(!queue.move(from,to,revision))return false;saveQueue();publish();return true;}
    public boolean removeQueue(int at,long revision){if(!queue.matches(revision)||at<0||at>=queue.size())return false;boolean running=playing()||loading;boolean selected=queue.remove(at,revision);saveQueue();if(selected){++generation;wanted=false;loading=false;releasePlayer();focus.abandon();if(running&&current()!=null)load();else{publish();detach();}}else publish();return true;}
    public boolean clearQueue(long revision){if(!queue.matches(revision))return false;queue.clear();saveQueue();stopPlayback();return true;}
    private void load() {
        Song song=current(); if(song==null)return;
        final boolean accompaniment=audioMode.equals("accompaniment");final int restorePosition=switchPosition;final boolean paused=switchPaused;switchPosition=0;switchPaused=false;int ticket=++generation; releasePlayer();loading=true;wanted=!paused;error="";
        getSharedPreferences("echo",0).edit().putInt("queue_index",queue.index()).apply();
        foreground();publish();
        io.execute(()->{
            try {
                CloudLibrary.Session<AuthStore.Scope> account=cache==null||song.local()||accompaniment?null:repository.cloud.session();
                String quality=account==null?"":cache.quality(song);
                MusicRepository.AudioSource resolved=resolveForPlayback(song,accompaniment,account,quality);
                String source=resolved.url;
                String cachedFallback=account==null||source.startsWith("file:")?"":cache.find(account.key,song,quality,true);
                main.post(()->{
                    if(ticket!=generation)return;
                    if(account!=null&&!repository.auth.current(account.scope)){fail("登录状态已变化，请重新播放");return;}
                    prepareTrack(song,resolved,account,quality,cachedFallback,restorePosition,ticket);
                });
            }catch(Exception e){main.post(()->{if(ticket==generation)fail(e.getMessage());});}
        });
    }

    private MusicRepository.AudioSource resolveForPlayback(Song song,boolean accompaniment,
            CloudLibrary.Session<AuthStore.Scope> account,String quality)throws Exception{
        if(account==null)return repository.resolveAudio(song,accompaniment);
        String cached=cache.find(account.key,song,quality,false);
        if(!cached.isEmpty())return new MusicRepository.AudioSource(cached,false);
        try{return repository.resolveAudio(song,false);}
        catch(Exception unavailable){
            if(repository.auth.current(account.scope)){
                cached=cache.find(account.key,song,quality,true);
                if(!cached.isEmpty())return new MusicRepository.AudioSource(cached,false);
            }
            throw unavailable;
        }
    }

    private void prepareTrack(Song song,MusicRepository.AudioSource resolved,CloudLibrary.Session<AuthStore.Scope> account,
                              String quality,String fallback,int restorePosition,int ticket){
        try {
                        MediaPlayer next=new MediaPlayer();player=next;
                        if(Build.VERSION.SDK_INT>=21)next.setAudioAttributes(attributes());else next.setAudioStreamType(AudioManager.STREAM_MUSIC);
                        next.setWakeMode(this,PowerManager.PARTIAL_WAKE_LOCK);
                        next.setDataSource(this,android.net.Uri.parse(resolved.url));
                        cacheRequest=account==null?null:()->cache.schedule(repository,account,song,quality,resolved.url);
                        next.setOnPreparedListener(p->{if(ticket!=generation)return;try{if(resolved.split){MediaPlayer.TrackInfo[] info=p.getTrackInfo();int audioCount=0,selected=-1;for(int i=0;i<info.length;i++)if(info[i].getTrackType()==MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO){if(selected<0)selected=i;audioCount++;}if(audioCount<2)throw new IllegalStateException("该歌曲没有双音轨伴奏，或电视不支持此格式，请切回原唱");p.selectTrack(selected);}prepared=true;loading=false;repository.remember(song);if(restorePosition>0){if(BuildConfig.MGV2000)seek(restorePosition);else p.seekTo(restorePosition);}}catch(Exception e){fail("伴奏不可用："+e.getMessage());return;}if(Build.VERSION.SDK_INT>=23&&rate!=1){p.setPlaybackParams(p.getPlaybackParams().setSpeed(rate));if(!wanted)p.pause();}if(wanted)resume();else {publish();detach();}});
                        if(BuildConfig.MGV2000)next.setOnSeekCompleteListener(this::seekCompleted);
                        next.setOnCompletionListener(p->{if(ticket!=generation)return;if(repeat==1)load();else if(repeat==3&&queue.index()==queue.size()-1)pause();else next();});
                        next.setOnErrorListener((p,what,extra)->{
                            if(ticket==generation&&!useCacheFallback(song,account,quality,fallback,restorePosition,ticket))
                                fail("播放失败，请检查文件授权、网络或音频格式");
                            return true;
                        });
                        next.prepareAsync();
        }catch(Exception e){
            if(!useCacheFallback(song,account,quality,fallback,restorePosition,ticket))fail("无法打开音频："+e.getMessage());
        }
    }

    private boolean useCacheFallback(Song song,CloudLibrary.Session<AuthStore.Scope> account,
                                     String quality,String fallback,int initialPosition,int ticket){
        if(fallback.isEmpty()||account==null||!repository.auth.current(account.scope))return false;
        int at=prepared?position():initialPosition;
        releasePlayer();
        loading=true;
        prepareTrack(song,new MusicRepository.AudioSource(fallback,false),account,quality,"",at,ticket);
        return true;
    }

    void cacheCurrent(){if(playing()&&cacheRequest!=null)cacheRequest.run();}
    boolean hasPreparedAudio(){return prepared;}
    private void fail(String message){loading=false;wanted=false;error=message==null?"播放失败":message;releasePlayer();focus.abandon();publish();if(remote!=null)foreground();else finishForeground(1);}
    public void toggle(){if(playing() || (loading&&wanted))pause();else resume();}
    public void resume(){
        wanted=true;
        if(player==null){if(current()!=null)load();return;}
        if(prepared){if(focus.request()!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){wanted=false;error="未获得音频焦点，请稍后重试";publish();return;}player.start();
            cacheCurrent();
            if(BuildConfig.MGV2000&&reanchorOnResume){
                reanchorOnResume=false;
                seeking=true;
                activeSeek=position();
                reanchorAfterSeek(player);
            }
            foreground();}
        publish();
    }
    public void pause(){wanted=false;resumeAfterFocus=false;if(playing())player.pause();focus.abandon();publish();if(!loading)detach();}
    private boolean seeking,reanchorOnResume;
    private int activeSeek=-1,queuedSeek=-1;
    private long seekRevision;
    boolean seeking(){return seeking;}
    long seekRevision(){return seekRevision;}
    int seekBarPosition(){
        return seeking?(queuedSeek>=0?queuedSeek:activeSeek):position();
    }
    void seekRelative(int delta){
        if(prepared&&player!=null)seek(seekBarPosition()+delta);
    }
    public void seek(int ms){
        if(!prepared||player==null)return;
        int target=Math.max(0,Math.min(ms,duration()));
        if(!BuildConfig.MGV2000){player.seekTo(target);publish();return;}
        if(seeking){queuedSeek=target==activeSeek?-1:target;return;}
        seeking=true;
        reanchorOnResume=false;
        activeSeek=target;
        player.seekTo(target);
        publish();
    }
    private void seekCompleted(MediaPlayer completed){
        if(completed!=player||!seeking)return;
        if(queuedSeek<0&&completed.isPlaying()){
            reanchorAfterSeek(completed);
        }else{
            reanchorOnResume=queuedSeek<0;
            finishSeek();
        }
    }
    private void reanchorAfterSeek(MediaPlayer completed){
        // The MGV2000 reports a stale playback clock after seek until the audio sink is paused.
        main.postDelayed(()->{
            if(completed!=player||!seeking)return;
            try{
                if(queuedSeek<0&&completed.isPlaying()){
                    completed.pause();
                    completed.getCurrentPosition();
                    if(wanted)completed.start();
                }
                finishSeek();
            }catch(RuntimeException error){fail("跳转后无法恢复播放");}
        },160);
    }
    private void finishSeek(){
        seeking=false;
        activeSeek=-1;
        int target=queuedSeek;
        queuedSeek=-1;
        if(target>=0){seek(target);return;}
        ++seekRevision;
        publish();
    }
    public void next(){if(queue.size()==0)return;int index=queue.index();queue.select(repeat==2&&queue.size()>1?(index+1+new Random().nextInt(queue.size()-1))%queue.size():(index+1)%queue.size());load();}
    public void previous(){if(position()>3000){seek(0);return;}if(queue.size()>0){queue.select((queue.index()-1+queue.size())%queue.size());load();}}
    private void releasePlayer(){cacheRequest=null;notificationLyric="";prepared=false;seeking=false;reanchorOnResume=false;activeSeek=-1;queuedSeek=-1;if(player!=null){player.release();player=null;}}
    public void stopPlayback(){++generation;wanted=false;loading=false;releasePlayer();focus.abandon();publish();if(remote!=null)foreground();else{finishForeground(1);stopSelf();}}
    private void publish(){
        Song s=current();
        if(notificationArt!=null)loadNotificationCover(s);
        if(Build.VERSION.SDK_INT>=21&&session!=null){
        long actions=PlaybackState.ACTION_PLAY|PlaybackState.ACTION_PAUSE|PlaybackState.ACTION_PLAY_PAUSE|PlaybackState.ACTION_SKIP_TO_NEXT|PlaybackState.ACTION_SKIP_TO_PREVIOUS|PlaybackState.ACTION_SEEK_TO|PlaybackState.ACTION_STOP;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions).setState(loading?PlaybackState.STATE_BUFFERING:playing()?PlaybackState.STATE_PLAYING:PlaybackState.STATE_PAUSED,position(),playing()?1:0).build());
        publishMediaMetadata(s);
        }
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }
    private void loadNotificationCover(Song song){
        String key=song==null?"":song.key();
        if(key.equals(coverKey))return;
        coverKey=key;cover=null;notificationLyric="";
        if(coverJob!=null)coverJob.cancel(true);
        if(song==null)return;
        coverJob=notificationIo.submit(()->{
            try{
                android.graphics.Bitmap image=notificationArt.load(song);
                if(image==null)return;
                int edge=Math.max(image.getWidth(),image.getHeight());
                android.graphics.Bitmap sized=edge<=384?image:android.graphics.Bitmap.createScaledBitmap(
                    image,Math.max(1,image.getWidth()*384/edge),Math.max(1,image.getHeight()*384/edge),true);
                main.post(()->{
                    if(notificationIo.isShutdown()||!key.equals(coverKey))return;
                    cover=sized;
                    if(prepared||loading)refreshMediaNotification();
                });
            }catch(Exception ignored){}
        });
    }
    private void publishMediaMetadata(Song song){
        if(session==null)return;
        if(song==null){session.setMetadata(null);return;}
        String title=BuildConfig.TV?song.title:HandheldHome.name(song.title);
        MediaMetadata.Builder metadata=new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST,song.artist).putString(MediaMetadata.METADATA_KEY_ALBUM,song.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION,duration());
        if(!notificationLyric.isEmpty()){
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE,title);
            metadata.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,notificationLyric);
        }
        if(cover!=null)metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,cover)
            .putBitmap(MediaMetadata.METADATA_KEY_ART,cover).putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON,cover);
        session.setMetadata(metadata.build());
    }
    void updateNotificationLyric(String line){
        if(line.equals(notificationLyric))return;
        notificationLyric=line;
        if(prepared)refreshMediaNotification();
    }
    private void refreshMediaNotification(){
        publishMediaMetadata(current());
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(1,notification());
    }
    private PendingIntent action(String action){return PendingIntent.getService(this,action.hashCode(),new Intent(this,PlaybackService.class).setAction(action),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);}
    private Notification notification(){
        Song s=current();
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder=(Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CHANNEL):new Notification.Builder(this)).setSmallIcon(cc.echomusic.android.R.drawable.ic_music).setContentTitle(s==null?"EchoMusic":s.title).setContentText(error.isEmpty()?(s==null?"选择一首音乐开始播放":notificationLyric.isEmpty()?s.artist:notificationLyric):error)
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(playing()||loading)
            .addAction(android.R.drawable.ic_media_previous,"上一首",action(PREVIOUS))
            .addAction(playing()?android.R.drawable.ic_media_pause:android.R.drawable.ic_media_play,playing()?"暂停":"播放",action(TOGGLE))
            .addAction(android.R.drawable.ic_media_next,"下一首",action(NEXT));
            if(Build.VERSION.SDK_INT>=21&&session!=null)builder.setVisibility(Notification.VISIBILITY_PUBLIC).setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()).setShowActionsInCompactView(0,1,2));
        if(cover!=null)builder.setLargeIcon(cover);
        return builder.build();
    }
    private void finishForeground(int mode){if(Build.VERSION.SDK_INT>=24)super.stopForeground(mode);else super.stopForeground((mode&1)!=0);}
    private void foreground(){startForeground(1,notification());}
    @Override public void onDestroy(){main.removeCallbacks(sleepStop);if(statusLyrics!=null)statusLyrics.close();if(notificationIo!=null)notificationIo.shutdownNow();if(remote!=null){remote.close();remote=null;}++generation;releasePlayer();focus.abandon();if(Build.VERSION.SDK_INT>=21&&session!=null)session.release();unregisterReceiver(noisy);io.shutdownNow();super.onDestroy();}
}
