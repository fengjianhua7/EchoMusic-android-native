package cc.echomusic.android;

import org.json.*;
import java.io.IOException;
import java.util.*;

/** Cloud playlist identity and write payloads, kept distinct from public playlist IDs and song mix IDs. */
public final class CloudProtocol {
    private CloudProtocol(){}
    public static final class Playlist {
        public final String id,name,owner,queryId;
        public final int count,type,source;
        public final boolean defaultList;
        public final JSONObject raw;
        public Playlist(JSONObject o){
            raw=o;id=AuthProtocol.text(o,"listid","specialid");name=AuthProtocol.text(o,"name","specialname","title");owner=AuthProtocol.text(o,"list_create_userid","userid");
            queryId=AuthProtocol.text(o,"list_create_gid","global_collection_id","gid");count=o.optInt("song_count",o.optInt("songcount",o.optInt("count",0)));type=o.optInt("type",0);source=o.optInt("source",1);
            defaultList=o.optInt("is_def",0)==1||o.optInt("is_def",0)==2||o.optInt("is_default",0)==1;
        }
        public boolean owned(String userid){return owner.equals(userid)&&source!=2;}
        public JSONObject json(){return raw;}
    }
    public static final class FormatException extends IOException{FormatException(String message){super(message);}}
    public static JSONArray array(JSONObject response,String... keys) throws IOException{
        LinkedHashSet<String> names=new LinkedHashSet<>(Arrays.asList(keys));
        names.addAll(Arrays.asList("info","list","lists","items","songs","songlist","tracks","file","files","file_list","filelist","playlists","playlist"));
        JSONArray found=findArray(response,names,0);if(found!=null)return found;
        if(explicitEmpty(response,0))return new JSONArray();
        // Never interpret missing fields as an empty account and erase cached music.
        throw new FormatException("云端列表结构未识别（"+shape(response,0)+"），已保留此前数据");
    }
    private static Object unwrap(Object value){if(value instanceof String){String text=((String)value).trim();if(text.length()<=4*1024*1024)try{if(text.startsWith("{"))return new JSONObject(text);if(text.startsWith("["))return new JSONArray(text);}catch(JSONException ignored){}}return value;}
    private static boolean explicitEmpty(Object value,int depth){if(depth>6)return false;value=unwrap(value);if(!(value instanceof JSONObject))return false;JSONObject object=(JSONObject)value;for(String name:new String[]{"total","total_count","file_count","song_count","songcount","count"})if(object.has(name)&&"0".equals(String.valueOf(object.opt(name))))return true;for(String wrapper:new String[]{"data","body","result","info"})if(explicitEmpty(object.opt(wrapper),depth+1))return true;return false;}
    private static JSONArray findArray(Object value,Set<String> names,int depth){
        if(depth>6)return null;value=unwrap(value);if(value instanceof JSONArray)return (JSONArray)value;if(!(value instanceof JSONObject))return null;JSONObject object=(JSONObject)value;
        for(String name:names){Object child=unwrap(object.opt(name));if(child instanceof JSONArray)return (JSONArray)child;}
        for(String wrapper:new String[]{"data","body","result","response","info","list","lists","files","file","file_list","filelist","playlist","playlists","tracks"}){
            Object child=object.opt(wrapper);if(child==null||child==JSONObject.NULL)continue;JSONArray result=findArray(child,names,depth+1);if(result!=null)return result;
        }return null;
    }
    private static String shape(JSONObject object,int depth){StringBuilder out=new StringBuilder();Iterator<String> keys=object.keys();int count=0;while(keys.hasNext()&&count++<10){String key=keys.next();if(out.length()>0)out.append(',');out.append(key.replaceAll("[^a-zA-Z0-9_]","?")).append(':');Object value=object.opt(key);out.append(value instanceof JSONArray?"array":value instanceof JSONObject?"object":value==null||value==JSONObject.NULL?"null":value instanceof String?"text":"number");if(depth<2&&value instanceof JSONObject&&Arrays.asList("data","body","result","info").contains(key))out.append('{').append(shape((JSONObject)value,depth+1)).append('}');}return out.toString();}
    public static Playlist liked(List<Playlist> lists,String userid) throws IOException{return liked(lists,userid,false);}
    public static Playlist liked(List<Playlist> lists,String userid,boolean preferNamedFavorites) throws IOException{
        List<Playlist> eligible=new ArrayList<>();
        for(Playlist p:lists)if(p.source!=2&&(p.owner.isEmpty()||p.owner.equals(userid)))eligible.add(p);
        // Names identify favorites more precisely than the broad is_def flag.
        for(String name:preferNamedFavorites?new String[]{"我喜欢的音乐","我喜欢","喜欢的音乐","默认收藏"}:new String[]{"我喜欢的音乐","默认收藏","我喜欢","喜欢的音乐"}){
            Playlist match=null;for(Playlist p:eligible)if(p.name.trim().equals(name)){if(match!=null)throw new IOException("收藏歌单存在同名项，请选择收藏歌单");match=p;}
            if(match!=null)return match;
        }
        List<Playlist> candidates=new ArrayList<>();for(Playlist p:eligible)if(p.type==1)candidates.add(p);
        if(candidates.size()==1)return candidates.get(0);
        if(candidates.isEmpty())for(Playlist p:eligible)if(p.defaultList)candidates.add(p);
        if(candidates.size()>1)throw new IOException("默认标记对应多个歌单，请选择收藏歌单");
        return candidates.isEmpty()?null:candidates.get(0);
    }
    public static void success(JSONObject response)throws IOException{if(response.optInt("status",0)!=1||AuthProtocol.errorCode(response)!=0)throw new IOException(AuthProtocol.message(response,200));}
    public static String trackPayload(Song song)throws IOException{
        if(song.local()||!song.hash.matches("[a-fA-F0-9]{32}"))throw new IOException("这首歌没有可用的云端歌曲标识，本地音频只能保存在设备收藏中");
        String name=song.title.replace('|','｜').replace(',','，').replace('\n',' ').replace('\r',' ');
        return name+"|"+song.hash+"|"+(song.albumId.matches("[0-9]+")?song.albumId:"0")+"|"+(song.mixId.matches("[0-9]+")?song.mixId:"0");
    }
    public static String fileId(Song song)throws IOException{if(!AuthProtocol.positiveId(song.fileId))throw new IOException("未取得云端歌曲文件标识，请刷新歌单后重试");return song.fileId;}
    public static boolean hasMore(JSONObject body,int rawCount,int loaded,int pageSize){
        JSONObject data=body.optJSONObject("data");long total=body.optLong("total",-1);if(data!=null)total=data.optLong("total",data.optLong("total_count",total));
        return total>0?loaded<total:rawCount>=pageSize;
    }
    public static String accountKey(String endpoint,String user){return endpoint+"\n"+user;}
}
