package cc.echomusic.android;

import android.app.DownloadManager;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import org.json.*;
import java.io.IOException;
import java.util.*;

/** Android owns the transfer, notification, retry and process-independent lifecycle. */
final class MusicDownloads {
    static final class Item {
        final long id,bytes,total;final Song song;final int status,reason;final Uri uri;
        Item(long id,Song song,int status,int reason,long bytes,long total,Uri uri){this.id=id;this.song=song;this.status=status;this.reason=reason;this.bytes=bytes;this.total=total;this.uri=uri;}
        boolean complete(){return status==DownloadManager.STATUS_SUCCESSFUL&&uri!=null;}
        Song local(){try{JSONObject data=song.json();data.put("id","download-"+id);data.put("source",uri.toString());return Song.fromJson(data);}catch(JSONException error){throw new IllegalStateException("下载歌曲信息无效",error);}}
        String label(){if(complete())return "已下载 · 可离线播放";if(status==DownloadManager.STATUS_FAILED)return "下载失败（"+reason+"），可移除后重试";if(status==0)return "文件或任务已被移除";String progress=total>0?Math.min(100,bytes*100/total)+"%":bytes/1024+" KB";return (status==DownloadManager.STATUS_PAUSED?"等待网络 / 重试 · ":status==DownloadManager.STATUS_PENDING?"等待下载 · ":"下载中 · ")+progress;}
    }
    private final Context context;private final SharedPreferences prefs;private final DownloadManager manager;
    MusicDownloads(Context c){context=c.getApplicationContext();prefs=context.getSharedPreferences("music-downloads",0);manager=(DownloadManager)context.getSystemService(Context.DOWNLOAD_SERVICE);}
    private JSONArray records(){try{return new JSONArray(prefs.getString("tasks","[]"));}catch(JSONException e){return new JSONArray();}}
    synchronized List<Item> items(){List<Item> result=new ArrayList<>();JSONArray saved=records();for(int i=0;i<saved.length();i++){JSONObject entry=saved.optJSONObject(i);if(entry==null||entry.optJSONObject("song")==null)continue;long id=entry.optLong("id",-1);Song song=Song.fromJson(entry.optJSONObject("song"));int status=0,reason=0;long bytes=0,total=0;Uri uri=null;try(Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id))){if(cursor!=null&&cursor.moveToFirst()){status=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));reason=cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));bytes=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));total=cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));if(status==DownloadManager.STATUS_SUCCESSFUL)uri=manager.getUriForDownloadedFile(id);}}catch(RuntimeException ignored){}result.add(new Item(id,song,status,reason,bytes,total,uri));}return result;}
    synchronized long enqueue(Song song,String address)throws Exception{
        if(song.local())throw new IOException("这首歌已经在本地");
        if(manager==null)throw new IOException("系统下载管理器不可用，请在系统设置中启用");
        for(Item item:items())if(Song.same(item.song,song)&&item.status!=DownloadManager.STATUS_FAILED&&item.status!=0)throw new IOException(item.complete()?"这首歌已下载，可在音乐库中离线播放":"这首歌已在下载列表中");
        Uri url=Uri.parse(address);if(!Arrays.asList("https","http").contains(url.getScheme()))throw new IOException("没有可用的下载地址");
        String filename=DownloadNames.filename(song.title,song.artist,url.getPath());
        DownloadManager.Request request=new DownloadManager.Request(url).setTitle(song.title).setDescription(song.artist).setAllowedOverMetered(true).setAllowedOverRoaming(false).setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        if(Build.VERSION.SDK_INT>=29)request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,"EchoMusic/"+filename);
        else request.setDestinationInExternalFilesDir(context,Environment.DIRECTORY_MUSIC,filename);
        long id=manager.enqueue(request);try{JSONArray saved=records();JSONObject record=new JSONObject();record.put("id",id);record.put("song",song.json());saved.put(record);if(!prefs.edit().putString("tasks",saved.toString()).commit())throw new IOException("无法保存下载任务");return id;}catch(Exception error){manager.remove(id);throw error;}
    }
    synchronized void remove(long id)throws IOException{manager.remove(id);JSONArray before=records(),after=new JSONArray();for(int i=0;i<before.length();i++){JSONObject record=before.optJSONObject(i);if(record!=null&&record.optLong("id",-1)!=id)after.put(record);}if(!prefs.edit().putString("tasks",after.toString()).commit())throw new IOException("下载记录保存失败，请刷新");}
}
