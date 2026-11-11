package cc.echomusic.android;

import org.json.*;
import java.util.*;

public final class Song {
    public final String id,title,artist,album,hash,albumId,source,mixId,fileId,cover;
    public final int durationMs;
    private String cloudDiskKey="", cloudDiskId="", cloudDiskName="", cloudDiskAudio="", cloudDiskAlbumAudio="";
    boolean cloudDisk(){return !cloudDiskKey.isEmpty();}
    String cloudDiskKey(){return cloudDiskKey;}
    String cloudDiskId(){return cloudDiskId;}
    String cloudDiskAudio(){return cloudDiskAudio;}
    String cloudDiskAlbumAudio(){return cloudDiskAlbumAudio;}
    String cloudDiskName(){return cloudDiskName;}
    static Song cloudDisk(JSONObject raw,String account){
        Song song=fromJson(raw);
        JSONObject audio=raw.optJSONObject("audio_info");
        song.cloudDiskId=positiveText(raw,"kv_id","kvid","cloud_file_id");
        song.cloudDiskKey=account;
        song.cloudDiskName=text(raw,"name","audio_name","filename","ori_audio_name");
        song.cloudDiskAudio=positiveText(raw,"audio_id","audioid");
        if(song.cloudDiskAudio.isEmpty())song.cloudDiskAudio=positiveText(audio,"audio_id","audioid");
        song.cloudDiskAlbumAudio=positiveText(raw,"album_audio_id","mixsongid");
        if(song.cloudDiskAlbumAudio.isEmpty())song.cloudDiskAlbumAudio=positiveText(audio,"album_audio_id","mixsongid");
        return song;
    }
    private final Set<String> identities=new LinkedHashSet<>();
    public Set<String> identities(){return Collections.unmodifiableSet(identities);}
    private void addHash(String value){if(value!=null&&value.matches("[a-fA-F0-9]{32}"))identities.add("h:"+value.toLowerCase(Locale.ROOT));}
    private void addMix(String value){if(value!=null&&value.matches("[0-9]+")){String canonical=value.replaceFirst("^0+(?!$)","");if(!canonical.equals("0"))identities.add("m:"+canonical);}}
    private static String positiveText(JSONObject object,String... keys){for(String key:keys){String value=text(object,key);if(value.matches("[0-9]+")&&!value.matches("0+"))return value;}return "";}
    public Song(String id,String title,String artist,String album,String hash,String albumId,String source){this(id,title,artist,album,hash,albumId,source,"","",0);}
    public Song(String id,String title,String artist,String album,String hash,String albumId,String source,String mixId,String fileId,int durationMs){
        this(id,title,artist,album,hash,albumId,source,mixId,fileId,durationMs,"");
    }
    public Song(String id,String title,String artist,String album,String hash,String albumId,String source,String mixId,String fileId,int durationMs,String cover){
        this.id=id;this.title=title;this.artist=artist;this.album=album;this.hash=hash;this.albumId=albumId;this.source=source;this.mixId=mixId;this.fileId=fileId;this.durationMs=durationMs;this.cover=cover;addHash(hash);addMix(mixId);
    }
    static String text(JSONObject o,String... keys){if(o==null)return "";for(String key:keys){Object value=o.opt(key);if(value!=null&&value!=JSONObject.NULL&&!(value instanceof JSONObject)&&!(value instanceof JSONArray)){String s=String.valueOf(value).trim();if(!s.isEmpty())return s.replaceAll("<[^>]*>","");}}return "";}
    public static Song fromJson(JSONObject o){
        JSONObject info=o.optJSONObject("audio_info"),albumInfo=o.optJSONObject("album_info");if(albumInfo==null)albumInfo=o.optJSONObject("albuminfo");
        String hash=text(o,"FileHash","hash","filehash","hash_128","Hash");if(hash.isEmpty())hash=text(info,"hash_128","hash");
        String source=text(o,"source");if(!source.startsWith("content://")&&!source.startsWith("file://"))source="";
        String title=text(o,"title","SongName","songname","filename","FileName","name","audio_name","ori_audio_name");if(title.isEmpty())title=text(info,"songname","filename","name");
        String artist=text(o,"artist","SingerName","singername","author_name","singer");if(artist.isEmpty())artist=text(info,"author_name","singername");
        if(artist.isEmpty()&&title.contains(" - ")){artist=title.substring(0,title.indexOf(" - "));title=title.substring(title.indexOf(" - ")+3);}
        String album=text(o,"album","AlbumName","album_name","albumname");if(album.isEmpty())album=text(albumInfo,"name","album_name");
        String albumId=text(o,"albumId","AlbumID","album_id","albumid");if(albumId.isEmpty())albumId=text(albumInfo,"id","album_id");
        String mix=positiveText(o,"mixId","mixSongId","MixSongID","MixSongId","mixsongid","mixsong_id","album_audio_id","Audioid","audio_id");if(mix.isEmpty())mix=positiveText(info,"mixsongid","album_audio_id","audio_id");
        String file=text(o,"fileId","fileid","file_id");
        String id=text(o,"id","MixSongID","mixsongid","audio_id","FileHash","hash");if(id.isEmpty())id=!mix.isEmpty()?mix:hash;
        long duration=o.optLong("durationMs",0);if(duration==0)duration=o.optLong("Duration",o.optLong("duration",0))*1000;
        if(duration==0)duration=o.optLong("timelen",o.optLong("timelength",0));if(duration==0&&info!=null)duration=info.optLong("timelength_128",info.optLong("timelength",0));
        String cover=text(o,"cover","Image","imgurl","sizable_cover","album_sizable_cover","pic");if(cover.isEmpty())cover=text(albumInfo,"sizable_cover","cover","imgurl","pic");
        Song song=new Song(id,title.isEmpty()?"未知歌曲":title,artist,album,hash,albumId,source,mix,file,(int)Math.min(Integer.MAX_VALUE,Math.max(0,duration)),cover);
        for(JSONObject object:new JSONObject[]{o,info})if(object!=null){for(String key:new String[]{"hash","Hash","FileHash","filehash","hash_128","hash_320","hash_flac","HQFileHash","SQFileHash","ResFileHash"})song.addHash(text(object,key));for(String key:new String[]{"mixId","mixSongId","MixSongID","MixSongId","mixsongid","mixsong_id","album_audio_id","audio_id"})song.addMix(positiveText(object,key));}
        JSONArray aliases=o.optJSONArray("cloudIdentityKeys");if(aliases!=null)for(int i=0;i<aliases.length();i++){String value=aliases.optString(i);if(value.startsWith("h:"))song.addHash(value.substring(2));if(value.startsWith("m:"))song.addMix(value.substring(2));}
        song.cloudDiskKey=text(o,"cloudDiskKey");song.cloudDiskId=text(o,"cloudDiskId");
        song.cloudDiskName=text(o,"cloudDiskName");song.cloudDiskAudio=text(o,"cloudDiskAudio");
        song.cloudDiskAlbumAudio=text(o,"cloudDiskAlbumAudio");
        return song;
    }
    public JSONObject json(){JSONObject o=new JSONObject();try{o.put("id",id);o.put("title",title);o.put("artist",artist);o.put("album",album);o.put("hash",hash);o.put("albumId",albumId);o.put("source",source);o.put("mixId",mixId);o.put("fileId",fileId);o.put("durationMs",durationMs);o.put("cover",cover);o.put("cloudIdentityKeys",new JSONArray(identities));if(cloudDisk()){o.put("cloudDiskKey",cloudDiskKey);o.put("cloudDiskId",cloudDiskId);o.put("cloudDiskName",cloudDiskName);o.put("cloudDiskAudio",cloudDiskAudio);o.put("cloudDiskAlbumAudio",cloudDiskAlbumAudio);}}catch(JSONException ignored){}return o;}
    public boolean cloudIdentified(){return !identities.isEmpty();}
    public Song cloudSong(){if(!local())return this;Song remote=new Song(id,title,artist,album,hash,albumId,"",mixId,fileId,durationMs,cover);remote.identities.addAll(identities);return remote;}
    public String key(){return !local()&&cloudDisk()?"disk:"+cloudDiskKey+":"+(cloudDiskId.isEmpty()?hash:cloudDiskId):source.isEmpty()?(hash.isEmpty()?(mixId.isEmpty()?"":"m:"+mixId):hash.toLowerCase(Locale.ROOT)):source;}
    public boolean local(){return !source.isEmpty();}
    public static boolean same(Song a,Song b){if(a.local()||b.local()||a.cloudDisk()||b.cloudDisk())return a.key().equals(b.key());return !Collections.disjoint(a.identities,b.identities);}
}
