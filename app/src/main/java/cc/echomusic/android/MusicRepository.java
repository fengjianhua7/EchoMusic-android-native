package cc.echomusic.android;

import android.content.*;
import android.net.Uri;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Uses the same KuGouMusicApi endpoint contract as the upstream Vue client. */
public final class MusicRepository {
    private final SharedPreferences prefs;
    private final Context context;
    public final AuthStore auth;
    public final CloudLibrary<AuthStore.Scope> cloud;
    private final PrivateJsonCache cache;
    private static final Object DEVICE_LOCK=new Object();
    private static final Map<String,Long> DEVICE_ATTEMPTS=new HashMap<>();
    public MusicRepository(Context c) { context=c.getApplicationContext();prefs=c.getSharedPreferences("echo",Context.MODE_PRIVATE);auth=new AuthStore(c);cache=new PrivateJsonCache(context);
        cloud=new CloudLibrary<>(new CloudLibrary.Environment<AuthStore.Scope>(){
            public boolean preferNamedFavorites(){return !BuildConfig.TV;}
            public CloudLibrary.Session<AuthStore.Scope> capture(){AuthStore.Scope scope=auth.scope();String user="";for(String field:scope.authorization.split(";")){String[] pair=field.trim().split("=",2);if(pair.length==2&&pair[0].equals("userid"))user=pair[1];}return new CloudLibrary.Session<>(scope,scope.endpoint,user);}
            public boolean current(CloudLibrary.Session<AuthStore.Scope> session){return auth.current(session.scope);}
            public JSONObject get(CloudLibrary.Session<AuthStore.Scope> session,String path,Map<String,String> params)throws Exception{
                AuthRequestRunner<AuthStore.Scope> runner=new AuthRequestRunner<>(auth::current,(scope,skip)->exchange(path,params,scope,skip),(event,scope,skip)->VerificationBroker.instance().await(event,MusicRepository.this,scope,skip));
                return runner.run(session.scope,false,false,true,false).body;
            }
            public String read(String key)throws Exception{return cache.read(key);}
            public void write(String key,String value)throws Exception{cache.write(key,value);}
        });
    }
    public static Map<String,String> params(String... pairs) { Map<String,String> out=new HashMap<>(); for(int i=0;i<pairs.length;i+=2)out.put(pairs[i],pairs[i+1]); return out; }
    public String endpoint() { return ApiEndpoint.selected(prefs.getString("endpoint","")); }
    public JSONObject get(String path,Map<String,String> params) throws Exception {return request(path,params,auth.scope(),false,false,true);}
    public JSONObject loginGet(AuthStore.Scope scope,String path,Map<String,String> params) throws Exception {
        if(ApiEndpoint.embedded(scope.endpoint)&&(path.equals("/login/qr/key")||path.equals("/login/qq/qr/create")||path.equals("/login/wx/create")||path.equals("/captcha/sent")))prepareLoginDevice(scope);
        return request(path,params,scope,true,true,true);
    }
    private void prepareLoginDevice(AuthStore.Scope scope) {
        synchronized(DEVICE_LOCK){
            String cookies=prefs.getString("device_cookies_"+scope.endpoint,"");
            if(cookies.matches("(?s)(?:.*;)?dfid=[^;]+(?:;.*)?"))return;
            long now=android.os.SystemClock.elapsedRealtime();
            if(now-DEVICE_ATTEMPTS.getOrDefault(scope.endpoint,-60000L)<30000)return;
            DEVICE_ATTEMPTS.put(scope.endpoint,now);
            // Best effort, without recursive verification: anonymous QR/SMS remains available if registration is unavailable.
            try{request("/register/dev",params(),scope,true,false,false);}catch(Exception ignored){}
        }
    }
    public JSONObject verificationGet(VerificationBroker.Challenge challenge,String path,Map<String,String> params) throws Exception {
        return request(path,params,challenge.scope,challenge.skipUserAuth,false,false);
    }
    private JSONObject request(String path,Map<String,String> params,AuthStore.Scope scope,boolean skip,boolean businessResult,boolean verify) throws Exception {
        return perform(path,params,scope,skip,businessResult,verify).body;
    }
    private AuthRequestRunner.Response<AuthStore.Scope> perform(String path,Map<String,String> params,AuthStore.Scope scope,boolean skip,boolean businessResult,boolean verify) throws Exception {
        AuthRequestRunner<AuthStore.Scope> runner=new AuthRequestRunner<>(auth::current,
            (session,skipUserAuth)->exchange(path,params,session,skipUserAuth),
            (event,session,skipUserAuth)->VerificationBroker.instance().await(event,this,session,skipUserAuth));
        return runner.run(scope,skip,businessResult,verify);
    }
    private AuthRequestRunner.Response<AuthStore.Scope> exchange(String path,Map<String,String> params,AuthStore.Scope scope,boolean skip) throws Exception {
        return exchange(path,params,scope,skip,null);
    }
    private AuthRequestRunner.Response<AuthStore.Scope> exchange(String path,Map<String,String> params,AuthStore.Scope scope,boolean skip,byte[] audio) throws Exception {
        if(!auth.current(scope))throw new IOException("登录状态已变化，请重新操作");
        String base=scope.endpoint;
        if(BuildConfig.MGV2000&&ApiEndpoint.embedded(base)){
            AuthRequestRunner.Response<AuthStore.Scope> result=new Mgv2000MusicApi(prefs).get(path,params,scope,skip);
            if(!auth.current(scope))throw new IOException("登录状态已变化，请重新操作");
            return new AuthRequestRunner.Response<>(result.status,AuthProtocol.normalize(path,result.body),result.event,scope);
        }
        EmbeddedApi.Connection local=null;
        if(ApiEndpoint.embedded(base)){local=EmbeddedApi.instance().await(context);base=local.url;}
        if(!auth.current(scope))throw new IOException("登录状态已变化，请重新操作");
        URI parsed=new URI(base);
        if(!"https".equals(parsed.getScheme())&&!"http".equals(parsed.getScheme()))throw new IOException("服务地址必须使用 http:// 或 https://");
        Uri.Builder builder=Uri.parse(base+path).buildUpon();
        for(Map.Entry<String,String> e:params.entrySet())builder.appendQueryParameter(e.getKey(),e.getValue());
        HttpURLConnection connection=(HttpURLConnection)new URL(builder.build().toString()).openConnection();
        connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(path.equals("/register/dev")?5000:12000);connection.setReadTimeout(path.equals("/register/dev")?8000:path.equals("/user/cloud/upload")?180000:25000);
        connection.setRequestProperty("Accept","application/json");
        if(local!=null)connection.setRequestProperty("X-Echo-Token",local.token);
        String cookieKey="device_cookies_"+scope.endpoint;
        String deviceCookies=prefs.getString(cookieKey,"");
        if(!deviceCookies.isEmpty())connection.setRequestProperty("Cookie",deviceCookies);
        if(!skip&&!scope.authorization.isEmpty())connection.setRequestProperty("Authorization",scope.authorization);
        try {
            if(audio!=null){
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/octet-stream");
                connection.setFixedLengthStreamingMode(audio.length);
                try(OutputStream output=connection.getOutputStream()){output.write(audio);}
            }
            int code=connection.getResponseCode();
            if(code>=300&&code<400)throw new IOException("接口地址发生重定向，请在设置中填写最终服务地址");
            InputStream stream=code<400?connection.getInputStream():connection.getErrorStream();
            if(stream==null)throw new IOException("服务无响应（HTTP "+code+"）");
            String content;
            try(stream;ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[8192];int n;
                while((n=stream.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>4*1024*1024)throw new IOException("响应过大");}
                content=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
            }
            JSONObject body;
            try{body=AuthProtocol.normalize(path,new JSONObject(content));}catch(JSONException e){throw new IOException("接口返回了无效数据（HTTP "+code+"）");}
            if(!auth.current(scope))throw new IOException("登录状态已变化，请重新操作");
            // Only device identity is accepted from Set-Cookie. User tokens require an explicit successful login.
            synchronized(MusicRepository.class){
                Map<String,String> cookies=new LinkedHashMap<>();
                for(String part:prefs.getString(cookieKey,"").split(";")){String[] kv=part.trim().split("=",2);if(kv.length==2)cookies.put(kv[0],kv[1]);}
                for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet()){
                    if(header.getKey()!=null&&header.getKey().equalsIgnoreCase("Set-Cookie"))for(String raw:header.getValue()){
                        String[] kv=raw.split(";",2)[0].split("=",2);
                        if(kv.length==2&&Arrays.asList("dfid","mid","uuid","KUGOU_API_MID","KUGOU_API_GUID","KUGOU_API_DEV","KUGOU_API_MAC").contains(kv[0]))cookies.put(kv[0],kv[1]);
                    }
                }
                StringBuilder value=new StringBuilder();for(Map.Entry<String,String> cookie:cookies.entrySet()){if(value.length()>0)value.append(';');value.append(cookie.getKey()).append('=').append(cookie.getValue());}
                prefs.edit().putString(cookieKey,value.toString()).apply();
            }
            return new AuthRequestRunner.Response<>(code,body,AuthProtocol.event(body,connection.getHeaderField("ssa-code")),scope);
        }finally{connection.disconnect();}
    }
    static JSONArray array(JSONObject o,String... keys) {
        for(String key:keys) { JSONArray a=o.optJSONArray(key); if(a!=null) return a; }
        JSONObject d=o.optJSONObject("data");
        if(d!=null) return array(d,keys);
        JSONArray dArray=o.optJSONArray("data");
        return dArray!=null?dArray:new JSONArray();
    }
    List<Song> recognize(byte[] audio,java.util.function.BooleanSupplier cancelled)throws Exception{
        AuthStore.Scope scope=auth.scope();
        JSONObject response=exchange("/audio/match",params(),scope,false,audio).body;
        if(AuthProtocol.failed(response))throw new IOException(AuthProtocol.message(response,200));
        JSONArray data=response.optJSONArray("data");
        List<JSONObject> ranked=new ArrayList<>();
        if(data!=null)for(int i=0;i<data.length();i++){JSONObject match=data.optJSONObject(i);if(match!=null)ranked.add(match);}
        ranked.sort(Comparator.comparingDouble(match->match.optDouble("dist",1)));
        List<Song> matches=new ArrayList<>();
        List<Song> checked=new ArrayList<>();
        for(JSONObject match:ranked){
            if(cancelled.getAsBoolean())return Collections.emptyList();
            if(!auth.current(scope))throw new IOException("账号已变化，请重新识曲");
            Song detected=Song.fromJson(match);
            if(!detected.cloudIdentified()&&detected.artist.isEmpty())continue;
            if(checked.stream().anyMatch(song->Song.same(song,detected)))continue;
            checked.add(detected);
            Song selected=detected;
            try{
                List<Song> results=checked.size()<=5?search(detected.title+" "+detected.artist,1):Collections.emptyList();
                for(Song result:results){
                    if(Song.same(result,detected)||(detected.hash.isEmpty()&&!detected.artist.isEmpty()
                        &&LyricChoice.normalized(result.title).equals(LyricChoice.normalized(detected.title))
                        &&LyricChoice.normalized(result.artist).equals(LyricChoice.normalized(detected.artist)))){
                        selected=result;break;
                    }
                }
            }catch(IOException unavailable){if(detected.hash.isEmpty())throw unavailable;}
            if(!auth.current(scope))throw new IOException("账号已变化，请重新识曲");
            Song candidate=selected;
            if(!candidate.hash.isEmpty()&&matches.stream().noneMatch(song->Song.same(song,candidate)))matches.add(candidate);
            if(matches.size()>=5)break;
        }
        return matches;
    }
    public List<Song> search(String query,int page) throws Exception {
        JSONObject json=get("/search",params("keywords",query,"type","song","page",String.valueOf(page),"pagesize","30"));
        return songs(array(json,"lists","songs","list","info"));
    }
    public List<Song> playlist(String id,int page) throws Exception {
        return songs(array(get("/playlist/track/all",params("id",id,"page",String.valueOf(page),"pagesize","30")),"songs","lists","info","list"));
    }
    static List<Song> songs(JSONArray arr) {
        List<Song> out=new ArrayList<>();
        for(int i=0;i<arr.length();i++) { JSONObject o=arr.optJSONObject(i); if(o==null) continue; Song s=Song.fromJson(o); if(!s.key().isEmpty()) out.add(s); }
        return out;
    }
    public String resolveDownload(Song song,AuthStore.Scope scope)throws Exception{
        if(song.local())throw new IOException("这首歌已经在本地");
        if(!BuildConfig.TV&&song.cloudDisk())return resolveDisk(song,scope);
        JSONObject verified=downloadGet("/user/verify",params(),scope);JSONObject data=verified.optJSONObject("data");String proof=AuthProtocol.text(data,"auth");
        if(proof.isEmpty())throw new IOException("未取得当前账号的下载地址，请先登录并确认歌曲权限");
        Map<String,String> query=params("hash",song.hash,"auth",proof);if(!song.albumId.isEmpty())query.put("album_id",song.albumId);
        String url=findUrl(downloadGet("/song/url/auth/merge",query,scope));if(url.isEmpty())throw new IOException("当前歌曲没有可用下载地址，可能受会员或版权限制");return url;
    }
    JSONObject diskGet(String path,Map<String,String> query,CloudLibrary.Session<AuthStore.Scope> session)throws Exception{
        if(BuildConfig.TV||!AuthProtocol.positiveId(session.userid))throw new IOException("请先登录云端账号");
        return downloadGet(path,query,session.scope);
    }
    void uploadDisk(String filename,String extension,byte[] bytes,CloudLibrary.Session<AuthStore.Scope> session)throws Exception{
        if(BuildConfig.TV||!AuthProtocol.positiveId(session.userid))throw new IOException("请先登录云端账号");
        AuthRequestRunner.Response<AuthStore.Scope> response=exchange("/user/cloud/upload",params("name",filename,"extendname",extension),session.scope,false,bytes);
        if(response.status<200||response.status>=300||AuthProtocol.failed(response.body))
            throw new IOException(AuthProtocol.message(response.body,response.status));
    }
    private String resolveDisk(Song song,AuthStore.Scope scope)throws Exception{
        CloudLibrary.Session<AuthStore.Scope> session=cloud.session();
        if(!auth.current(scope)||!session.key.equals(song.cloudDiskKey()))
            throw new IOException("请切换到此云盘文件所属账号");
        String url=findUrl(diskGet("/user/cloud/url",params("hash",song.hash,"name",song.cloudDiskName(),
            "audio_id",song.cloudDiskAudio(),"album_audio_id",song.cloudDiskAlbumAudio(),"kv_id",song.cloudDiskId()),session));
        if(url.isEmpty())throw new IOException("云盘暂未返回此文件的播放地址");
        return url;
    }
    private JSONObject downloadGet(String path,Map<String,String> params,AuthStore.Scope scope)throws Exception{
        AuthRequestRunner<AuthStore.Scope> runner=new AuthRequestRunner<>(auth::current,(session,skip)->exchange(path,params,session,skip),(event,session,skip)->VerificationBroker.instance().await(event,this,session,skip));
        return runner.run(scope,false,false,true,false).body;
    }
    static final class AudioSource {final String url;final boolean split;AudioSource(String url,boolean split){this.url=url;this.split=split;}}
    AudioSource resolveAudio(Song song,boolean accompaniment)throws Exception{
        if(!accompaniment)return new AudioSource(resolve(song),false);
        if(song.local())throw new IOException("本地歌曲没有在线伴奏");
        try{
            JSONObject match=get("/audio/accompany/matching",params("hash",song.hash,"mixId",song.mixId,"fileName",song.artist+" - "+song.title));
            JSONObject data=match.optJSONObject("data");String hash=Song.text(data,"hash");
            if(hash.matches("[a-fA-F0-9]{32}")&&!hash.equalsIgnoreCase(song.hash)){
                Song backing=new Song(song.id,song.title,song.artist,song.album,hash,"","",Song.text(data,"libSongId"),"",song.durationMs);
                return new AudioSource(resolve(backing),false);
            }
        }catch(Exception unavailable){/* Some recordings only have magic dual-track resources. */}
        return new AudioSource(resolve(song,true),true);
    }
    public String resolve(Song song) throws Exception {return resolve(song,false);}
    public String resolve(Song song,boolean accompaniment) throws Exception {
        if(!song.source.isEmpty()){if(accompaniment)throw new IOException("此歌曲没有在线伴奏");return song.source;}
        if(!BuildConfig.TV&&song.cloudDisk()){
            if(accompaniment)throw new IOException("云盘文件没有在线伴奏");
            return resolveDisk(song,auth.scope());
        }
        boolean directBox = BuildConfig.MGV2000 && ApiEndpoint.embedded(endpoint());
        try {
            return resolveCatalog(song,accompaniment);
        } catch (AuthProtocol.ApiException error) {
            if (!directBox || accompaniment || AuthProtocol.errorCode(error.body)!=20006
                    || !error.eventId.isEmpty() || !song.hash.matches("[a-fA-F0-9]{32}")) throw error;
            try {
                String url=findUrl(perform("/user/cloud/url",params("hash",song.hash,"album_audio_id",song.mixId,
                    "audio_id",song.fileId,"name",song.title),auth.scope(),false,false,true).body);
                if (!url.isEmpty()) return url;
            } catch (AuthProtocol.ApiException cloudError) {
                if (!cloudError.eventId.isEmpty()) throw cloudError;
            }
            throw new IOException("曲库和云盘均未返回这首歌的播放地址（20006）",error);
        }
    }
    private String resolveCatalog(Song song,boolean accompaniment)throws Exception{
        AuthRequestRunner.Response<AuthStore.Scope> verified=perform("/user/verify",params(),this.auth.scope(),false,false,true);
        JSONObject data=verified.body.optJSONObject("data");
        String auth=data==null?"":data.optString("auth","");
        if(auth.isEmpty()) throw new IOException("未获取到播放授权，请检查登录凭证及歌曲权限");
        Map<String,String> params=new HashMap<>(); params.put("hash",song.hash); params.put("auth",auth);
        if(!song.albumId.isEmpty()) params.put("album_id",song.albumId);
        if(!song.mixId.isEmpty()) params.put("album_audio_id",song.mixId);
        if(accompaniment)params.put("quality","acappella");
        else if(!BuildConfig.TV)params.put("quality",prefs.getString("playback_quality","128"));
        JSONObject r=perform("/song/url/auth/merge",params,verified.scope,false,false,true).body;
        String url=findUrl(r);
        if(url.isEmpty()) throw new IOException("当前歌曲无可用播放地址，可能需要登录或会员权限");
        return url;
    }
    static String findUrl(JSONObject o) {
        for(String key:new String[]{"url","play_url","playurl"}) {
            Object val=o.opt(key);
            if(val instanceof String && (((String)val).startsWith("https://") || ((String)val).startsWith("http://"))) return (String)val;
            if(val instanceof JSONArray) { JSONArray a=(JSONArray)val; for(int i=0;i<a.length();i++) { String s=a.optString(i); if(s.startsWith("http://")||s.startsWith("https://")) return s; } }
        }
        JSONObject d=o.optJSONObject("data"); return d==null?"":findUrl(d);
    }
    public List<LyricChoice> lyricChoices(Song song,int duration)throws Exception{
        Map<String,String> query=params("man","yes");if(song.hash.isEmpty())query.put("keywords",song.artist+" "+song.title);else query.put("hash",song.hash);
        if(duration>0)query.put("duration",String.valueOf(duration));if(!song.mixId.isEmpty())query.put("album_audio_id",song.mixId);
        String keyword=song.artist+" "+song.title;
        if(BuildConfig.MGV2000 && ApiEndpoint.embedded(endpoint())){
            String title=song.title.replaceFirst("(?i)\\.(mp3|flac|m4a|wav|aac|ogg|ape)$","");
            keyword=song.artist.isEmpty()?title:song.artist+" - "+title;
            query.put("keywords",keyword);
        }
        JSONArray candidates;
        try {
            candidates=array(get("/search/lyric",query),"candidates");
        } catch (AuthProtocol.ApiException error) {
            if (!BuildConfig.MGV2000 || song.hash.isEmpty() || AuthProtocol.errorCode(error.body)!=20006
                    || !error.eventId.isEmpty()) throw error;
            candidates=new JSONArray();
        }
        if(candidates.length()==0&&!song.hash.isEmpty()){
            candidates=array(get("/search/lyric",params("keywords",keyword.trim(),"man","yes")),"candidates");
        }
        List<LyricChoice> result=new ArrayList<>();for(int i=0;i<candidates.length();i++){JSONObject data=candidates.optJSONObject(i);if(data==null)continue;LyricChoice choice=new LyricChoice(data);if(!choice.id.isEmpty()&&!choice.key.isEmpty())result.add(choice);}
        result.sort((left,right)->Integer.compare(right.score(song,duration),left.score(song,duration)));return result;
    }
    public String cachedLyrics(Song song)throws Exception{String value=cache.read(BuildConfig.TV?"lyric/tv-current":"lyric/"+song.key());if(value==null)return "";JSONObject data=new JSONObject(value);if(BuildConfig.TV&&!song.key().equals(data.optString("song")))return "";return data.optString("raw","");}
    public void storeLyrics(Song song,String raw)throws Exception{JSONObject data=new JSONObject();data.put("raw",raw);if(BuildConfig.TV)data.put("song",song.key());cache.write(BuildConfig.TV?"lyric/tv-current":"lyric/"+song.key(),data.toString());}
    public String fetchLyrics(Song song,LyricChoice selected,int duration)throws Exception{
        LyricChoice choice=selected;if(choice==null){List<LyricChoice> candidates=lyricChoices(song,duration);if(candidates.isEmpty())throw new IOException("暂无匹配歌词，可导入本地歌词");choice=candidates.get(0);}
        Exception failure=null;
        for(String format:new String[]{"krc","lrc"}){
            try{JSONObject response=get("/lyric",params("id",choice.id,"accesskey",choice.key,"decode","true","fmt",format));String raw=AuthProtocol.text(response,"decodeContent","lyric");
                if(raw.isEmpty()){String encoded=AuthProtocol.text(response,"content");if(!encoded.isEmpty())raw=Lyrics.decodeFile((BuildConfig.MGV2000?android.util.Base64.decode(encoded,android.util.Base64.DEFAULT):java.util.Base64.getDecoder().decode(encoded)));}
                if(!raw.isEmpty()){try{storeLyrics(song,raw);}catch(IOException ignored){}return raw;}
            }catch(AuthProtocol.ApiException error){if(AuthProtocol.errorCode(error.body)==20018||!error.eventId.isEmpty())throw error;failure=error;}
            catch(IOException | IllegalArgumentException | JSONException error){if(!BuildConfig.MGV2000)throw error;failure=error;}
        }
        throw new IOException("歌词下载失败，请重试或更换歌词来源",failure);
    }
    int lyricSpeed(Song song){return prefs.getInt("lyric_speed_"+song.key(),100);}
    void lyricSpeed(Song song,int percent){prefs.edit().putInt("lyric_speed_"+song.key(),Math.max(80,Math.min(120,percent))).apply();}
    public int lyricOffset(Song song){return prefs.getInt("lyric_offset_"+song.key(),0);}
    public void lyricOffset(Song song,int milliseconds){prefs.edit().putInt("lyric_offset_"+song.key(),Math.max(-10000,Math.min(10000,milliseconds))).apply();}
    public String lyrics(Song song)throws Exception{return fetchLyrics(song,null,song.durationMs);}
    public List<Song> saved(String key) { try { return songs(new JSONArray(prefs.getString(key,"[]"))); } catch(JSONException e) { return new ArrayList<>(); } }
    public void save(String key,List<Song> songs) { JSONArray a=new JSONArray(); for(Song s:songs) a.put(s.json()); prefs.edit().putString(key,a.toString()).apply(); }
    public void remember(Song song) { List<Song> history=saved("history"); history.removeIf(s->s.key().equals(song.key())); history.add(0,song); if(history.size()>200) history=history.subList(0,200); save("history",history); }
    public boolean favoriteKnown(Song song){return favorite(song)||cloud.snapshot().favoritesReady;}
    public boolean favorite(Song song) { return song.cloudIdentified()&&cloud.snapshot().favorite(song.cloudSong()); }
    public void toggleFavorite(Song song) { if(!song.local())throw new IllegalStateException("在线歌曲收藏必须由云端确认");List<Song> list=saved("favorites"); boolean existed=list.removeIf(s->s.key().equals(song.key())); if(!existed)list.add(0,song); save("favorites",list); }
}

