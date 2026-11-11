package cc.echomusic.android;

import org.json.*;
import java.io.*;
import java.util.*;

/** Serialized, account-bound cloud operations. Read-only snapshots never wait for network locks. */
public final class CloudLibrary<S> {
    public static final int PAGE_SIZE=200,MAX_PAGES=500;
    private static final Object OPERATIONS=new Object();
    public static final class Session<S>{
        public final S scope;public final String endpoint,userid,key;
        public Session(S scope,String endpoint,String userid){this.scope=scope;this.endpoint=endpoint;this.userid=userid;key=CloudProtocol.accountKey(endpoint,userid);}
    }
    public interface Environment<S>{
        default boolean preferNamedFavorites(){return false;}
        Session<S> capture();boolean current(Session<S> session);
        JSONObject get(Session<S> session,String path,Map<String,String> params)throws Exception;
        String read(String key)throws Exception;void write(String key,String value)throws Exception;
    }
    public static final class Snapshot<S>{
        public final Session<S> session;public final List<CloudProtocol.Playlist> playlists;public final List<Song> favorites;
        public final boolean favoritesReady;public final long updatedAt;
        private final Set<String> keys;
        Snapshot(Session<S> session,List<CloudProtocol.Playlist> playlists,List<Song> favorites,boolean ready,long time){
            this.session=session;this.playlists=Collections.unmodifiableList(new ArrayList<>(playlists));this.favorites=Collections.unmodifiableList(new ArrayList<>(favorites));favoritesReady=ready;updatedAt=time;
            keys=new HashSet<>();for(Song song:favorites)keys.addAll(song.identities());
        }
        public boolean favorite(Song song){return !song.local()&&!Collections.disjoint(keys,song.identities());}
    }
    private final Environment<S> environment;
    private volatile Snapshot<S> state;
    public CloudLibrary(Environment<S> environment){this.environment=environment;}
    public Session<S> session(){return environment.capture();}
    public Snapshot<S> snapshot(){Snapshot<S> cached=state;if(cached!=null&&environment.current(cached.session))return cached;return new Snapshot<>(session(),Collections.emptyList(),Collections.emptyList(),false,0);}
    private void require(Session<S> session)throws IOException{if(!AuthProtocol.positiveId(session.userid))throw new IOException("请先登录云端账号");if(!environment.current(session))throw new IOException("账号已变化，请重新操作");}
    private JSONObject get(Session<S> session,String path,Map<String,String> params)throws Exception{require(session);JSONObject response=environment.get(session,path,params);require(session);CloudProtocol.success(response);return response;}
    private static Map<String,String> params(String... pairs){Map<String,String> result=new LinkedHashMap<>();for(int i=0;i<pairs.length;i+=2)result.put(pairs[i],pairs[i+1]);return result;}
    public void restore()throws Exception{restore(session());}
    public void restore(Session<S> session)throws Exception{synchronized(OPERATIONS){require(session);Snapshot<S> live=state;if(live!=null&&environment.current(live.session)&&live.updatedAt>0)return;String saved=environment.read(session.key+"/library");if(saved==null||saved.isEmpty())return;JSONObject o=new JSONObject(saved);List<CloudProtocol.Playlist> lists=parseLists(o.optJSONArray("playlists"));List<Song> songs=parseSongs(o.optJSONArray("favorites"));require(session);state=new Snapshot<>(session,lists,songs,o.optBoolean("favoritesReady",false),o.optLong("updatedAt",0));}}
    private void publish(Session<S> session,List<CloudProtocol.Playlist> lists,List<Song> favorites,boolean complete)throws Exception{
        require(session);Snapshot<S> next=new Snapshot<>(session,lists,favorites,complete,System.currentTimeMillis());
        JSONObject cache=new JSONObject();JSONArray p=new JSONArray(),f=new JSONArray();for(CloudProtocol.Playlist list:lists)p.put(list.json());for(Song song:favorites)f.put(song.json());cache.put("playlists",p);cache.put("favorites",f);cache.put("favoritesReady",complete);cache.put("updatedAt",next.updatedAt);
        require(session);state=next;
        // Cache failure does not turn an already confirmed remote mutation into an apparent cloud failure.
        try{environment.write(session.key+"/library",cache.toString());}catch(IOException ignored){}
    }
    private static List<CloudProtocol.Playlist> parseLists(JSONArray array){List<CloudProtocol.Playlist> result=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++){JSONObject o=array.optJSONObject(i);if(o!=null){CloudProtocol.Playlist list=new CloudProtocol.Playlist(o);if(AuthProtocol.positiveId(list.id))result.add(list);}}return result;}
    private static List<Song> parseSongs(JSONArray array){List<Song> result=new ArrayList<>();if(array!=null)for(int i=0;i<array.length();i++){JSONObject o=array.optJSONObject(i);if(o==null)continue;Song song=Song.fromJson(o);if(!song.local()&&song.cloudIdentified())result.add(song);}return result;}
    private List<CloudProtocol.Playlist> lists(Session<S> session)throws Exception{
        LinkedHashMap<String,CloudProtocol.Playlist> all=new LinkedHashMap<>();Set<String> pages=new HashSet<>();int loaded=0;
        for(int page=1;page<=MAX_PAGES;page++){
            JSONObject response=get(session,"/user/playlist",params("page",String.valueOf(page),"pagesize",String.valueOf(PAGE_SIZE)));
            JSONArray raw;try{raw=CloudProtocol.array(response,"info","list","lists","items");}catch(CloudProtocol.FormatException e){throw new IOException("账号歌单："+e.getMessage(),e);}List<CloudProtocol.Playlist> chunk=parseLists(raw);if(raw.length()>0&&chunk.isEmpty())throw new IOException("云端歌单缺少有效标识，已保留原数据");StringBuilder fingerprint=new StringBuilder();for(CloudProtocol.Playlist list:chunk){fingerprint.append(list.id).append(';');all.put(list.id,list);}loaded+=raw.length();
            if(raw.length()>0&&!pages.add(fingerprint.toString()))throw new IOException("云端重复返回同一页歌单，已保留原数据");
            if(!CloudProtocol.hasMore(response,raw.length(),loaded,PAGE_SIZE))return new ArrayList<>(all.values());
            if(raw.length()==0)throw new IOException("云端歌单分页不完整，请稍后刷新");
        }throw new IOException("云端歌单分页超出上限，请稍后重试");
    }
    private List<Song> tracks(Session<S> session,CloudProtocol.Playlist list)throws Exception{
        LinkedHashMap<String,Song> all=new LinkedHashMap<>();Set<String> pages=new HashSet<>();int loaded=0;
        boolean privateList=list.owned(session.userid)||list.defaultList||list.queryId.isEmpty();
        for(int page=1;page<=MAX_PAGES;page++){
            String route=privateList?"/playlist/track/all/new":"/playlist/track/all";
            JSONObject response=get(session,route,params(privateList?"listid":"id",privateList?list.id:list.queryId,"page",String.valueOf(page),"pagesize",String.valueOf(PAGE_SIZE)));
            JSONArray raw;try{raw=CloudProtocol.array(response,"info","songs","list","lists","songlist","items");}catch(CloudProtocol.FormatException error){
                if(privateList&&!list.queryId.isEmpty()){response=get(session,"/playlist/track/all",params("id",list.queryId,"page",String.valueOf(page),"pagesize",String.valueOf(PAGE_SIZE)));try{raw=CloudProtocol.array(response,"info","songs","list","lists","songlist","items");}catch(CloudProtocol.FormatException second){throw new IOException("歌单歌曲："+second.getMessage(),second);}}
                else throw new IOException("歌单歌曲："+error.getMessage(),error);
            }List<Song> chunk=parseSongs(raw);if(raw.length()>0&&chunk.isEmpty())throw new IOException("云端歌曲缺少有效标识，已保留原数据");StringBuilder fingerprint=new StringBuilder();for(Song song:chunk){fingerprint.append(song.key()).append('/').append(song.fileId).append(';');all.put(song.key(),song);}loaded+=raw.length();
            if(raw.length()>0&&!pages.add(fingerprint.toString()))throw new IOException("云端重复返回同一页歌曲，已保留原数据");
            if(!CloudProtocol.hasMore(response,raw.length(),loaded,PAGE_SIZE))return new ArrayList<>(all.values());
            if(raw.length()==0)throw new IOException("云端歌曲分页不完整，请刷新重试");
        }throw new IOException("云端歌曲分页超出上限，尚未完成同步");
    }
    public Snapshot<S> refresh()throws Exception{synchronized(OPERATIONS){return refreshInternal(session());}}
    public Snapshot<S> refresh(Session<S> session)throws Exception{synchronized(OPERATIONS){return refreshInternal(session);}}
    private Snapshot<S> refreshInternal(Session<S> session)throws Exception{
        require(session);List<CloudProtocol.Playlist> lists=lists(session);CloudProtocol.Playlist liked;
        try{liked=liked(session,lists);}catch(IOException error){publish(session,lists,snapshot().favorites,false);throw error;}
        List<Song> favorites;
        try{favorites=liked==null?snapshot().favorites:tracks(session,liked);}catch(Exception error){publish(session,lists,snapshot().favorites,false);throw error;}
        publish(session,lists,favorites,liked!=null);return snapshot();
    }
    private CloudProtocol.Playlist liked(Session<S> session,List<CloudProtocol.Playlist> lists)throws Exception{
        String selected=environment.read(session.key+"/selected-favorites");
        if(selected!=null)for(CloudProtocol.Playlist p:lists)if(p.id.equals(selected)&&p.source!=2&&(p.owner.isEmpty()||p.owner.equals(session.userid)))return p;
        return CloudProtocol.liked(lists,session.userid,environment.preferNamedFavorites());
    }
    public void selectFavorites(Session<S> session,String id)throws Exception{synchronized(OPERATIONS){
        require(session);List<CloudProtocol.Playlist> lists=lists(session);CloudProtocol.Playlist selected=null;
        for(CloudProtocol.Playlist p:lists)if(p.id.equals(id)&&p.source!=2&&(p.owner.isEmpty()||p.owner.equals(session.userid)))selected=p;
        if(selected==null)throw new IOException("请选择当前账号自己的收藏歌单");
        List<Song> songs=tracks(session,selected);require(session);environment.write(session.key+"/selected-favorites",selected.id);publish(session,lists,songs,true);
    }}
    private CloudProtocol.Playlist currentList(Session<S> session,String id,boolean editable)throws Exception{
        for(CloudProtocol.Playlist list:lists(session))if(list.id.equals(id)){if(editable&&!list.owned(session.userid))throw new IOException("只能修改自己创建的云端歌单");return list;}
        throw new IOException("云端歌单已不存在，请刷新列表");
    }
    public List<Song> loadPlaylist(String id)throws Exception{return loadPlaylist(session(),id);}
    public List<Song> loadPlaylist(Session<S> session,String id)throws Exception{synchronized(OPERATIONS){CloudProtocol.Playlist list=currentList(session,id,false);List<Song> songs=tracks(session,list);require(session);JSONArray data=new JSONArray();for(Song song:songs)data.put(song.json());try{environment.write(session.key+"/playlist/"+id,data.toString());}catch(IOException ignored){}return songs;}}
    public List<Song> cachedPlaylist(String id)throws Exception{Session<S> session=session();require(session);String data=environment.read(session.key+"/playlist/"+id);require(session);return data==null?Collections.emptyList():parseSongs(new JSONArray(data));}
    public boolean toggleFavorite(Song song)throws Exception{return toggleFavorite(session(),song);}
    public boolean toggleFavorite(Session<S> session,Song song)throws Exception{synchronized(OPERATIONS){Snapshot<S> current=refreshInternal(session);boolean desired=!current.favorite(song);setFavorite(session,song,desired,current);return desired;}}
    public void favorite(Song song,boolean desired)throws Exception{synchronized(OPERATIONS){Session<S> session=session();setFavorite(session,song,desired,refreshInternal(session));}}
    private void setFavorite(Session<S> session,Song song,boolean desired,Snapshot<S> current)throws Exception{
        if(song.local())throw new IOException("本地音频请使用设备收藏");
        CloudProtocol.Playlist liked=liked(session,current.playlists);if(liked==null)throw new IOException("未找到账号默认收藏歌单，请先在酷狗客户端确认");
        Song existing=find(current.favorites,song);if((existing!=null)==desired)return;
        if(desired)get(session,"/playlist/tracks/add",params("listid",liked.id,"data",CloudProtocol.trackPayload(song)));
        else get(session,"/playlist/tracks/del",params("listid",liked.id,"fileids",CloudProtocol.fileId(existing)));
        List<Song> confirmed;
        try{confirmed=tracks(session,liked);}catch(Exception e){throw new IOException("操作已提交，但云端结果暂未确认，请刷新查看",e);}
        publish(session,current.playlists,confirmed,true);
        if((find(confirmed,song)!=null)!=desired)throw new IOException("云端尚未确认收藏变化，请刷新查看，未自动重复提交");
    }
    public void add(String id,Song song)throws Exception{add(session(),id,song);}
    public void add(Session<S> session,String id,Song song)throws Exception{synchronized(OPERATIONS){CloudProtocol.Playlist list=currentList(session,id,true);List<Song> before=tracks(session,list);if(find(before,song)!=null)return;get(session,"/playlist/tracks/add",params("listid",id,"data",CloudProtocol.trackPayload(song)));confirmPlaylist(session,list,song,true);}}
    public void remove(String id,Song song)throws Exception{remove(session(),id,song);}
    public void remove(Session<S> session,String id,Song song)throws Exception{synchronized(OPERATIONS){CloudProtocol.Playlist list=currentList(session,id,true);List<Song> before=tracks(session,list);Song existing=find(before,song);if(existing==null)return;get(session,"/playlist/tracks/del",params("listid",id,"fileids",CloudProtocol.fileId(existing)));confirmPlaylist(session,list,song,false);}}
    int addAll(Session<S> session,String id,List<Song> selected)throws Exception{
        synchronized(OPERATIONS){
            CloudProtocol.Playlist list=currentList(session,id,true);
            List<Song> known=new ArrayList<>(tracks(session,list));
            List<Song> added=new ArrayList<>();
            List<String> payload=new ArrayList<>();
            for(Song song:selected){
                if(find(known,song)!=null)continue;
                payload.add(CloudProtocol.trackPayload(song));
                known.add(song);
                added.add(song);
            }
            if(payload.isEmpty())return 0;
            for(int start=0;start<payload.size();start+=25){
                get(session,"/playlist/tracks/add",params("listid",id,"data",String.join(",",payload.subList(start,Math.min(start+25,payload.size())))));
            }
            List<Song> confirmed=confirmedTracks(session,list);
            int count=0;
            for(Song song:added)if(find(confirmed,song)!=null)count++;
            if(count!=added.size())throw new IOException("已确认添加 "+count+" 首，其余结果请刷新歌单查看");
            return count;
        }
    }
    int removeAll(Session<S> session,String id,List<Song> selected)throws Exception{
        synchronized(OPERATIONS){
            CloudProtocol.Playlist list;
            if(id==null){
                list=liked(session,lists(session));
                if(list==null||list.source==2||(!list.owner.isEmpty()&&!list.owned(session.userid)))
                    throw new IOException("未找到当前账号可编辑的收藏歌单");
            }else list=currentList(session,id,true);
            Set<String> existing=new HashSet<>();
            for(Song song:tracks(session,list))existing.add(song.fileId);
            List<String> ids=new ArrayList<>();
            for(Song song:selected){
                String fileId=CloudProtocol.fileId(song);
                if(existing.remove(fileId))ids.add(fileId);
            }
            if(ids.isEmpty())return 0;
            for(int start=0;start<ids.size();start+=100){
                get(session,"/playlist/tracks/del",params("listid",list.id,"fileids",String.join(",",ids.subList(start,Math.min(start+100,ids.size())))));
            }
            Set<String> remaining=new HashSet<>();
            for(Song song:confirmedTracks(session,list))remaining.add(song.fileId);
            int count=0;
            for(String fileId:ids)if(!remaining.contains(fileId))count++;
            if(count!=ids.size())throw new IOException("已确认移除 "+count+" 首，其余结果请刷新歌单查看");
            return count;
        }
    }
    private void confirmPlaylist(Session<S> session,CloudProtocol.Playlist list,Song song,boolean present)throws Exception{
        if((find(confirmedTracks(session,list),song)!=null)!=present)
            throw new IOException("云端尚未确认歌单变化，请刷新查看，未自动重复提交");
    }
    private List<Song> confirmedTracks(Session<S> session,CloudProtocol.Playlist list)throws Exception{
        List<Song> after;try{after=tracks(session,list);}catch(Exception e){throw new IOException("操作已提交，但歌单结果暂未确认，请刷新查看",e);}
        JSONArray cache=new JSONArray();for(Song s:after)cache.put(s.json());require(session);try{environment.write(session.key+"/playlist/"+list.id,cache.toString());}catch(IOException ignored){}
        Snapshot<S> known=snapshot();CloudProtocol.Playlist liked=liked(session,known.playlists);
        if(liked!=null&&liked.id.equals(list.id))publish(session,known.playlists,after,true);
        return after;
    }
    public void create(String name)throws Exception{create(session(),name);}
    public void create(Session<S> session,String name)throws Exception{synchronized(OPERATIONS){require(session);String title=name.trim();if(title.isEmpty()||title.length()>80)throw new IOException("歌单名称需为 1–80 个字符");get(session,"/playlist/add",params("name",title,"type","0","source","1","is_pri","0","list_create_userid",session.userid));try{refreshInternal(session);}catch(Exception e){throw new IOException("歌单创建已提交，请刷新云端列表确认",e);}}}
    public int importFavorites(Session<S> expected,List<Song> incoming)throws Exception{synchronized(OPERATIONS){require(expected);Snapshot<S> before=refreshInternal(expected);CloudProtocol.Playlist liked=liked(expected,before.playlists);if(liked==null)throw new IOException("未找到账号默认收藏歌单");List<Song> added=new ArrayList<>();List<Song> known=new ArrayList<>(before.favorites);
        for(Song song:incoming){require(expected);if(song.local()||find(known,song)!=null)continue;try{get(expected,"/playlist/tracks/add",params("listid",liked.id,"data",CloudProtocol.trackPayload(song)));added.add(song);known.add(song);}catch(Exception e){throw new IOException("导入已停止，已有 "+added.size()+" 首提交云端；请刷新确认后再继续",e);}}
        List<Song> confirmed;try{confirmed=tracks(expected,liked);}catch(Exception e){throw new IOException("已提交 "+added.size()+" 首歌曲，云端结果待确认，请刷新查看",e);}publish(expected,before.playlists,confirmed,true);int count=0;for(Song song:added)if(find(confirmed,song)!=null)count++;if(count!=added.size())throw new IOException("已确认导入 "+count+" 首，其余结果待云端确认，请刷新查看");return count;
    }}
    private static Song find(List<Song> songs,Song wanted){for(Song song:songs)if(Song.same(song,wanted))return song;return null;}
}
