package cc.echomusic.android;
import org.json.*;
import java.util.*;
import java.io.*;

public final class CloudContractTest {
    static int checks=0;
    static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    interface Throwing{void run()throws Exception;}
    static void rejects(Throwing call,String label)throws Exception{boolean failed=false;try{call.run();}catch(Exception e){failed=true;}check(failed,label);}
    static JSONObject object(String source)throws Exception{return new JSONObject(source);}
    static Song song(int number){String hash=String.format(Locale.ROOT,"%032x",number);return Song.fromJson(new JSONObject().put("filename","Singer - Song "+number).put("audio_info",new JSONObject().put("hash_128",hash).put("audio_id",String.valueOf(number+10000))).put("fileid",String.valueOf(number+20000)).put("album_id",7));}
    static JSONObject playlist(String id,String name,String owner,boolean def)throws Exception{return new JSONObject().put("listid",id).put("name",name).put("list_create_userid",owner).put("is_def",def?1:0).put("source",1).put("song_count",0);}
    static final class Env implements CloudLibrary.Environment<String>{
        String version="A1",userid="101",endpoint="embedded://echomusic";List<JSONObject> playlists=new ArrayList<>();Map<String,List<Song>> songs=new HashMap<>();Map<String,String> cache=new HashMap<>();List<String> writes=new ArrayList<>();Map<String,String> last=new HashMap<>();boolean failRead=false,ignoreMutation=false,repeatPage=false,badArray=false,failCache=false;String changeOn="";int gets=0;
        Env()throws Exception{playlists.add(playlist("1","我喜欢的音乐","101",true));playlists.add(playlist("2","我的歌单","101",false));playlists.add(playlist("3","他人的收藏歌单","202",false).put("list_create_gid","public-abc"));songs.put("1",new ArrayList<>());songs.put("2",new ArrayList<>());songs.put("3",new ArrayList<>());}
        public CloudLibrary.Session<String> capture(){return new CloudLibrary.Session<>(version,endpoint,userid);}
        public boolean current(CloudLibrary.Session<String> s){return s.scope.equals(version)&&s.userid.equals(userid)&&s.endpoint.equals(endpoint);}
        public String read(String key){return cache.get(key);}public void write(String key,String value)throws Exception{if(failCache)throw new IOException("disk unavailable");cache.put(key,value);}
        public JSONObject get(CloudLibrary.Session<String> session,String route,Map<String,String> params)throws Exception{
            gets++;last=new HashMap<>(params);if(route.equals(changeOn)){version="B1";userid="202";}if(failRead&&route.contains("/track/all"))throw new IOException("offline");JSONObject response=new JSONObject().put("status",1);JSONObject data=new JSONObject();
            if(route.equals("/user/playlist")){JSONArray page=new JSONArray();int number=Integer.parseInt(params.get("page")),size=Integer.parseInt(params.get("pagesize"));for(int i=(number-1)*size;i<Math.min(number*size,playlists.size());i++)page.put(playlists.get(i));return response.put("data",data.put("info",page).put("total",playlists.size()));}
            if(route.contains("/track/all")){String id=params.getOrDefault("listid","3");List<Song> list=songs.getOrDefault(id,Collections.emptyList());if(badArray)return response.put("data",new JSONObject());JSONArray page=new JSONArray();int number=repeatPage?1:Integer.parseInt(params.get("page")),size=Integer.parseInt(params.get("pagesize"));for(int i=(number-1)*size;i<Math.min(number*size,list.size());i++)page.put(list.get(i).json());return response.put("data",data.put("info",page).put("total",list.size()));}
            writes.add(route);
            if(route.equals("/playlist/tracks/add")){
                List<Song> list=songs.get(params.get("listid"));
                if(!ignoreMutation)for(String entry:params.get("data").split(",")){
                    String[] fields=entry.split("\\|",-1);
                    list.add(new Song(fields[3],fields[0],"","",fields[1],fields[2],"",fields[3],String.valueOf(777777+list.size()),0));
                }
                return response;
            }
            if(route.equals("/playlist/tracks/del")){
                Set<String> ids=new HashSet<>(Arrays.asList(params.get("fileids").split(",")));
                if(!ignoreMutation)songs.get(params.get("listid")).removeIf(s->ids.contains(s.fileId));
                return response;
            }
            if(route.equals("/playlist/add")){String id=String.valueOf(playlists.size()+1);playlists.add(playlist(id,params.get("name"),session.userid,false));songs.put(id,new ArrayList<>());return response.put("data",new JSONObject().put("listid",id));}
            throw new IOException("Unexpected route "+route);
        }
    }
    public static void main(String[] args)throws Exception{
        Song nested=song(1);check(nested.hash.equals("00000000000000000000000000000001"),"nested hash");check(nested.fileId.equals("20001")&&nested.mixId.equals("10001"),"file ID distinct from mix ID");check(nested.title.equals("Song 1")&&nested.artist.equals("Singer"),"split legacy filename");check(Song.fromJson(nested.json()).fileId.equals(nested.fileId),"file ID survives cache and queue round trip");
        Song numeric=Song.fromJson(object("{hash:'0123456789abcdef0123456789abcdef',source:1,name:'online'}"));check(!numeric.local(),"numeric cloud source is not treated as a local URI");
        Song local=new Song("local","local","","","","","content://audio/1");rejects(()->CloudProtocol.trackPayload(local),"local audio cannot be uploaded as a catalog song");
        Song delimiters=new Song("a","one,two|three","","",nested.hash,"7","","10001","",0);check(CloudProtocol.trackPayload(delimiters).split("\\|",-1).length==4&&!CloudProtocol.trackPayload(delimiters).contains(","),"song names cannot inject extra tracks");
        rejects(()->CloudProtocol.fileId(new Song("a","a","","",nested.hash,"","","10001","",0)),"mix ID is never substituted for playlist file ID");
        check(CloudProtocol.liked(Arrays.asList(new CloudProtocol.Playlist(playlist("5","我喜欢的钢琴","101",false))),"101")==null,"fuzzy name must not select an arbitrary user's playlist");
        check(CloudProtocol.liked(Arrays.asList(new CloudProtocol.Playlist(playlist("5","我喜欢的音乐","202",true))),"101")==null,"other user's default list is not writable favorites");
        Env env=new Env();for(int i=1;i<=405;i++)env.songs.get("1").add(song(i));CloudLibrary<String> library=new CloudLibrary<>(env);CloudLibrary.Snapshot<String> first=library.refresh();check(first.favorites.size()==405&&first.favoritesReady,"all cloud favorite pages loaded");check(first.playlists.size()==3,"created and collected playlists both loaded");check(first.favorite(Song.fromJson(nested.json())),"favorite matches nested metadata");
        env.failRead=true;rejects(library::refresh,"refresh network error propagates");check(library.snapshot().favorites.size()==405,"failed refresh preserves complete previous favorites");env.failRead=false;
        env.badArray=true;rejects(library::refresh,"malformed page cannot masquerade as empty list");check(library.snapshot().favorites.size()==405,"malformed response does not clear cache");env.badArray=false;
        env.repeatPage=true;rejects(library::refresh,"repeated cloud pages terminate instead of silently truncating");env.repeatPage=false;
        Env many=new Env();for(int i=4;i<=405;i++)many.playlists.add(playlist(String.valueOf(i),"list"+i,"101",false));check(new CloudLibrary<>(many).refresh().playlists.size()==405,"playlist list itself is paginated");
        Env edit=new Env();CloudLibrary<String> edits=new CloudLibrary<>(edit);Song fresh=song(9);check(edits.toggleFavorite(fresh),"favorite writes to cloud");check(edits.snapshot().favorite(fresh),"heart only updates after cloud readback");check(edit.writes.size()==1&&edit.writes.get(0).equals("/playlist/tracks/add"),"single explicit add");
        edits.favorite(fresh,true);check(edit.writes.size()==1,"idempotent desired favorite does not duplicate a song");check(!edits.toggleFavorite(fresh),"unfavorite confirmed");check(edit.writes.get(1).equals("/playlist/tracks/del")&&edit.last.get("listid").equals("1"),"delete targets actual default list");check(!edits.snapshot().favorite(fresh),"heart removed after cloud confirmation");
        edit.ignoreMutation=true;rejects(()->edits.favorite(fresh,true),"upstream status 1 without membership change is not claimed as success");check(!edits.snapshot().favorite(fresh),"no optimistic false favorite");edit.ignoreMutation=false;
        edit.songs.get("1").add(new Song("x","x","","",fresh.hash,"","","different-mix","909090",0));edits.refresh();edits.favorite(fresh,false);check(edit.songs.get("1").isEmpty(),"unfavorite resolves the actual cached cloud file ID, not search metadata");
        edits.add("2",fresh);check(edit.songs.get("2").size()==1,"add to owned cloud playlist");int count=edit.writes.size();edits.add("2",fresh);check(edit.writes.size()==count,"duplicate playlist add is skipped");edits.remove("2",fresh);check(edit.songs.get("2").isEmpty(),"remove from cloud playlist by its own file ID");rejects(()->edits.add("3",fresh),"cannot mutate somebody else's collected playlist");
        edits.create("新歌单");check(edits.snapshot().playlists.stream().anyMatch(p->p.name.equals("新歌单")),"created cloud playlist is refetched");
        edit.failCache=true;edits.favorite(fresh,true);check(edits.snapshot().favorite(fresh),"cache disk error does not hide confirmed cloud success");edit.failCache=false;edits.refresh();
        CloudLibrary.Session<String> old=edit.capture();edit.version="B1";edit.userid="202";count=edit.writes.size();rejects(()->edits.add(old,"2",song(20)),"queued old-account mutation fails before writing");check(edit.writes.size()==count,"no write into the next account");check(edits.snapshot().favorites.isEmpty(),"previous account's favorites disappear immediately");
        edits.restore();check(edits.snapshot().favorites.isEmpty(),"disk cache is account-isolated");edit.version="A2";edit.userid="101";edits.restore();check(edits.snapshot().favorite(fresh),"same account cache survives token revision changes");
        Env changed=new Env();changed.changeOn="/playlist/track/all/new";CloudLibrary<String> changing=new CloudLibrary<>(changed);rejects(changing::refresh,"late response after account switch is rejected");check(changing.snapshot().favorites.isEmpty(),"late response cannot publish into new account");
        Env migration=new Env();CloudLibrary<String> migrate=new CloudLibrary<>(migration);int imported=migrate.importFavorites(migration.capture(),Arrays.asList(song(1),song(1),song(2),local));check(imported==2&&migration.songs.get("1").size()==2,"explicit legacy import deduplicates and excludes local audio");
        List<CloudProtocol.Playlist> named=Arrays.asList(new CloudProtocol.Playlist(playlist("41","默认收藏","101",true)),new CloudProtocol.Playlist(playlist("42","我喜欢","101",false)));
        check(CloudProtocol.liked(named,"101",true).id.equals("42"),"handheld prefers exact named favorites over generic default collection");
        check(CloudProtocol.liked(named,"101").id.equals("41"),"TV legacy favorites selection remains unchanged");
        Env noDefault=new Env();noDefault.playlists.remove(0);CloudLibrary<String> unknown=new CloudLibrary<>(noDefault);rejects(()->unknown.favorite(fresh,true),"missing favorites list is not automatically created or guessed");check(noDefault.writes.isEmpty(),"no writes to an unrecognized default list");
        check(CloudProtocol.liked(Arrays.asList(new CloudProtocol.Playlist(playlist("6","其它默认","101",true)),new CloudProtocol.Playlist(playlist("7","默认收藏","101",true))),"101").id.equals("7"),"reserved name takes priority over multiple default flags");
        Env ambiguous=new Env();ambiguous.playlists.get(0).put("name","收藏甲");ambiguous.playlists.get(1).put("is_def",2);CloudLibrary<String> ambiguousLibrary=new CloudLibrary<>(ambiguous);
        rejects(ambiguousLibrary::refresh,"ambiguous favorites needs manual selection");check(ambiguousLibrary.snapshot().playlists.size()==3,"ambiguity does not hide cloud playlists");
        ambiguousLibrary.selectFavorites(ambiguous.capture(),"2");ambiguousLibrary.refresh();check(ambiguousLibrary.snapshot().favoritesReady,"explicit account-scoped selection resolves ambiguity");
        rejects(()->ambiguousLibrary.selectFavorites(ambiguous.capture(),"3"),"cannot select another account's playlist as favorites");
        JSONArray files=new JSONArray().put(song(33).json());
        check(CloudProtocol.array(new JSONObject().put("data",new JSONObject().put("filelist",files)),"info").length()==1,"cloud filelist envelope supported");
        check(CloudProtocol.array(new JSONObject().put("body",new JSONObject().put("data",new JSONObject().put("files",files))),"info").length()==1,"nested body/data/files supported");
        check(CloudProtocol.array(new JSONObject().put("data",new JSONObject().put("file_list",new JSONObject().put("list",files))),"info").length()==1,"file_list/list envelope supported");
        check(CloudProtocol.array(new JSONObject().put("data",new JSONObject().put("info",files).toString()),"info").length()==1,"JSON-encoded data envelope supported");
        check(CloudProtocol.array(new JSONObject().put("result",new JSONObject().put("tracks",new JSONArray())),"info").length()==0,"explicit empty track list accepted");
        rejects(()->CloudProtocol.array(new JSONObject().put("data",new JSONObject().put("token","private-value")),"info"),"missing list never becomes empty success");
        try{CloudProtocol.array(new JSONObject().put("data",new JSONObject().put("token","private-value")),"info");}catch(java.io.IOException e){check(!e.getMessage().contains("private-value"),"format diagnostics omit field values");}
        Env trackFormat=new Env();CloudLibrary<String> partial=new CloudLibrary<>(trackFormat);partial.refresh();trackFormat.badArray=true;rejects(partial::refresh,"malformed tracks remain an explicit failure");check(partial.snapshot().playlists.size()==3,"successful playlist metadata survives track format failure");
        check(CloudProtocol.array(new JSONObject().put("data",new JSONObject().put("count",0)),"info").length()==0,"explicit zero-count response is a valid empty library");
        Song covered=Song.fromJson(new JSONObject().put("hash","0123456789abcdef0123456789abcdef").put("album_info",new JSONObject().put("sizable_cover","https://example.invalid/{size}.jpg")));
        check(Song.fromJson(covered.json()).cover.equals("https://example.invalid/{size}.jpg"),"album cover survives mapping and queue persistence");
        Song downloaded=new Song("download-1","song","artist","","0123456789abcdef0123456789abcdef","","content://downloads/1");
        check(downloaded.cloudIdentified()&&!downloaded.cloudSong().local(),"downloaded track keeps cloud identity for direct favorites");
        check(downloaded.local()&&downloaded.cloudSong().hash.equals(downloaded.hash),"cloud mutation conversion does not change local playback source");
        Song alternate=Song.fromJson(new JSONObject().put("hash","11111111111111111111111111111111").put("HQFileHash","22222222222222222222222222222222").put("mixSongId","00077"));
        Song quality=Song.fromJson(new JSONObject().put("hash","22222222222222222222222222222222"));
        check(Song.same(alternate,quality),"different audio qualities match known platform hashes");
        check(Song.same(Song.fromJson(alternate.json()),quality),"quality aliases survive disk cache");
        Song byMix=Song.fromJson(new JSONObject().put("MixSongId",77));check(Song.same(alternate,byMix),"mixed-case mix field and leading zero ID normalized");
        Song nestedId=Song.fromJson(new JSONObject().put("album_audio_id",0).put("audio_info",new JSONObject().put("audio_id",77)));check(Song.same(nestedId,byMix),"zero placeholder does not mask nested positive song ID");
        Env identityEnv=new Env();identityEnv.songs.get("1").add(alternate);CloudLibrary<String> identityLibrary=new CloudLibrary<>(identityEnv);identityLibrary.refresh();check(identityLibrary.snapshot().favorite(quality),"favorite index matches quality alias");check(identityLibrary.snapshot().favorite(byMix),"favorite index matches a mix-only song");
        check(!Song.same(Song.fromJson(new JSONObject().put("fileid",77)),byMix),"playlist file IDs never count as song identity");
        Env batchEnv=new Env();
        CloudLibrary<String> batch=new CloudLibrary<>(batchEnv);
        CloudLibrary.Session<String> session=batchEnv.capture();
        check(batch.addAll(session,"2",Arrays.asList(song(1),song(1),song(2)))==2,"batch add deduplicates and confirms each song");
        check(batchEnv.writes.equals(Collections.singletonList("/playlist/tracks/add")),"small batch sends one mutation");
        rejects(()->batch.removeAll(session,"3",new ArrayList<>(batchEnv.songs.get("2"))),"batch removal checks list ownership");
        check(batch.removeAll(session,"2",Arrays.asList(song(1),song(2)))==0,"batch removal never substitutes catalog IDs for selected playlist file IDs");
        check(batch.removeAll(session,"2",new ArrayList<>(batchEnv.songs.get("2")))==2,"batch removal confirms selected file IDs disappeared");
        JSONObject diskEntry=new JSONObject().put("hash",nested.hash).put("name","file.mp3")
            .put("kv_id",42).put("audio_id",12).put("album_audio_id",22);
        Song disk=Song.cloudDisk(diskEntry,"owner-a"), diskCopy=Song.fromJson(disk.json());
        check(diskCopy.cloudDiskId().equals("42")&&diskCopy.cloudDiskAudio().equals("12")
            &&diskCopy.cloudDiskAlbumAudio().equals("22"),"private cloud file IDs survive queue serialization separately");
        check(Song.same(disk,diskCopy)&&!Song.same(disk,Song.cloudDisk(diskEntry,"owner-b")),
            "cloud disk identity is isolated across accounts");
        System.out.println("PASS: "+checks+" cloud library contract checks");
    }
}
