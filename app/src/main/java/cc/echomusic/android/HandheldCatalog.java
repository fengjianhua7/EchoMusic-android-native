package cc.echomusic.android;

import java.util.*;
import org.json.*;

final class HandheldCatalog {
    static final int PAGE_SIZE=30;

    static final class Entry {
        final String id,name,cover;
        Entry(String id,String name,String cover){this.id=id;this.name=name;this.cover=cover;}
    }

    static JSONArray list(JSONObject response,String... keys){
        JSONObject data=response.optJSONObject("data");
        JSONObject info=response.optJSONObject("info");
        JSONObject songs=data==null?null:data.optJSONObject("songs");
        for(JSONObject object:new JSONObject[]{data,songs,info,response}){
            if(object==null)continue;
            for(String key:keys){
                JSONArray array=object.optJSONArray(key);
                if(array!=null)return array;
            }
        }
        return MusicRepository.array(response,keys);
    }

    static List<Entry> entries(JSONObject response,String type){
        JSONArray items=list(response,"special_list","list","info","lists","rank_list","tags","songs");
        Map<String,Entry> result=new LinkedHashMap<>();
        for(int i=0;i<items.length();i++){
            JSONObject item=items.optJSONObject(i);
            if(item==null)continue;
            if(type.equals("tags")){
                JSONArray children=item.optJSONArray("son");
                if(children==null)continue;
                for(int c=0;c<children.length();c++){
                    JSONObject tag=children.optJSONObject(c);
                    add(result,Song.text(tag,"tag_id","id"),Song.text(tag,"tag_name","name"),"");
                }
                continue;
            }
            JSONObject extra=item.optJSONObject("extra");
            String id,name;
            if(type.equals("rank")){
                id=Song.text(item,"rankid","rank_id","id");
                name=Song.text(item,"rankname","rank_name","name","title");
            }else{
                id=Song.text(item,"list_create_gid","global_collection_id","gid");
                if(id.isEmpty())id=Song.text(extra,"global_collection_id","global_special_id","specialid");
                if(id.isEmpty())id=Song.text(item,"specialid","list_create_listid","listid","id");
                name=Song.text(item,"name","specialname","title","ip_name","special_name");
                if(name.isEmpty())name=Song.text(extra,"name","specialname","title");
            }
            add(result,id,name,Song.text(item,"pic","imgurl","cover","img","image"));
        }
        return new ArrayList<>(result.values());
    }

    private static void add(Map<String,Entry> result,String id,String name,String cover){
        if(!id.isEmpty()&&!id.equals("0")&&!name.isEmpty())result.putIfAbsent(id,new Entry(id,name,cover));
    }

    static final class TrackPage {
        final List<Song> songs;
        final int rawCount;
        final long total;
        final String signature;

        TrackPage(List<Song> songs,int rawCount,long total,String signature){
            this.songs=songs;this.rawCount=rawCount;this.total=total;this.signature=signature;
        }

        boolean hasNext(int page){
            return rawCount>0&&(total>=0?page*(long)PAGE_SIZE<total:rawCount>=PAGE_SIZE);
        }
    }

    static TrackPage tracks(JSONObject response){
        JSONArray raw=list(response,"song_list","songlist","songs","list","lists","info");
        JSONObject data=response.optJSONObject("data");
        JSONObject container=data==null?null:data.optJSONObject("songs");
        long total=-1;
        for(JSONObject object:new JSONObject[]{container,data,response}){
            if(object==null)continue;
            for(String key:new String[]{"total","total_count","totalcount","song_count","songcount"}){
                if(object.optLong(key,-1)>=0){total=object.optLong(key);break;}
            }
            if(total>=0)break;
        }
        String signature=UUID.nameUUIDFromBytes(raw.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
        List<Song> songs=new ArrayList<>();
        for(int i=0;i<raw.length();i++){
            JSONObject entry=raw.optJSONObject(i);
            if(entry==null)continue;
            JSONObject base=entry.optJSONObject("base");
            if(base!=null){
                try{
                    Iterator<String> keys=base.keys();
                    while(keys.hasNext()){String key=keys.next();if(entry.isNull(key))entry.put(key,base.opt(key));}
                }catch(JSONException ignored){}
            }
            Song song=Song.fromJson(entry);
            if(!song.hash.isEmpty())songs.add(song);
        }
        return new TrackPage(songs,raw.length(),total,signature);
    }
}
