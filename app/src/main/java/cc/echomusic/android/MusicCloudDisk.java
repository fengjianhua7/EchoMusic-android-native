package cc.echomusic.android;

import java.io.IOException;
import java.util.*;
import org.json.*;

final class MusicCloudDisk {
    static final int PAGE_SIZE=60;
    static final int MAX_UPLOAD=20*1024*1024;
    final List<Song> songs;
    final int total,rawCount;
    final long capacity,available;

    MusicCloudDisk(JSONObject body,String account)throws IOException{
        JSONObject data=body.optJSONObject("data");
        if(data==null)data=body;
        total=data.optInt("list_count",data.optInt("count",-1));
        capacity=data.optLong("max_size",data.optLong("capacity",0));
        available=data.optLong("availble_size",data.optLong("available",-1));
        JSONArray raw=data.optJSONArray("list");
        if(raw==null&&total!=0)throw new IOException("未识别云盘文件列表，请刷新后重试");
        rawCount=raw==null?0:raw.length();
        List<Song> entries=new ArrayList<>();
        for(int i=0;i<rawCount;i++){
            JSONObject item=raw.optJSONObject(i);
            if(item==null)continue;
            Song song=Song.cloudDisk(item,account);
            if(!song.hash.isEmpty())entries.add(song);
        }
        if(rawCount>0&&entries.isEmpty())throw new IOException("云盘返回了无法识别的文件，请刷新后重试");
        songs=Collections.unmodifiableList(entries);
    }

    boolean more(int page){return rawCount>0&&(total>=0?page*PAGE_SIZE<total:rawCount>=PAGE_SIZE);}

    static int remove(MusicRepository repo,CloudLibrary.Session<AuthStore.Scope> expected,List<Song> selected)throws Exception{
        Map<String,String> files=new LinkedHashMap<>();
        for(Song song:selected){
            if(!expected.key.equals(song.cloudDiskKey())||!AuthProtocol.positiveId(song.cloudDiskId()))
                throw new IOException("文件标识已变化，请刷新云盘后重试");
            files.put(song.cloudDiskId(),song.cloudDiskAlbumAudio().isEmpty()?"0":song.cloudDiskAlbumAudio());
        }
        List<String> ids=new ArrayList<>(files.keySet()),albumIds=new ArrayList<>(files.values());
        for(int i=0;i<ids.size();i+=80){
            int end=Math.min(i+80,ids.size());
            JSONObject response=repo.diskGet("/user/cloud/del",MusicRepository.params(
                "fileids",String.join(",",ids.subList(i,end)),"album_audio_ids",String.join(",",albumIds.subList(i,end))),expected);
            CloudProtocol.success(response);
        }
        return ids.size();
    }
}
