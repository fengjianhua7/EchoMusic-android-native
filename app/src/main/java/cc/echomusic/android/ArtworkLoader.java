package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.util.LruCache;
import android.view.View;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.ExecutorService;

/** Decodes actual embedded or platform album art off the UI thread. */
final class ArtworkLoader {
    private final Context context;private final MusicRepository repo;private final ExecutorService io;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>((BuildConfig.TV?1:8*1024*1024)){protected int sizeOf(String key,Bitmap image){return BuildConfig.TV?1:image.getByteCount();}};
    ArtworkLoader(Context context,MusicRepository repo,ExecutorService io){this.context=context.getApplicationContext();this.repo=repo;this.io=io;}
    void bind(ImageView view,TextView placeholder,Song song){
        String key=song==null?"":song.key();if(key.equals(view.getTag()))return;view.setTag(key);view.setImageDrawable(null);placeholder.setText("♫");placeholder.setTextSize(72);placeholder.setVisibility(View.VISIBLE);
        if(song==null)return;Bitmap existing=cache.get(key);if(existing!=null){view.setImageBitmap(existing);placeholder.setVisibility(View.GONE);return;}
        io.execute(()->{Bitmap image=null;try{
            image=load(song);
        }catch(Exception ignored){}final Bitmap result=image;if(result!=null)cache.put(key,result);main.post(()->{if(!key.equals(view.getTag()))return;if(result!=null){view.setImageBitmap(result);placeholder.setVisibility(View.GONE);}else{placeholder.setText(song.local()?"♫\n音频未提供内嵌封面":"♫\n暂无可用封面");placeholder.setTextSize(18);}});});
    }
    void bindPlaylist(ImageView view,TextView placeholder,String id){String token="playlist:"+id;view.setTag(token);io.execute(()->{try{java.util.List<Song> songs=repo.cloud.cachedPlaylist(id);if(songs.isEmpty())return;Song first=songs.get(0);Bitmap bitmap=load(first);if(bitmap!=null)main.post(()->{if(token.equals(view.getTag())){view.setImageBitmap(bitmap);placeholder.setVisibility(View.GONE);}});}catch(Exception ignored){}});}
    void bindCatalog(ImageView view,TextView placeholder,String address){
        if(address.isEmpty())return;
        String key="catalog:"+address;
        view.setTag(key);
        Bitmap existing=cache.get(key);
        if(existing!=null){view.setImageBitmap(existing);placeholder.setVisibility(View.GONE);return;}
        io.execute(()->{
            try{
                Bitmap image=download(address);
                if(image==null)return;
                cache.put(key,image);
                main.post(()->{
                    if(key.equals(view.getTag())){view.setImageBitmap(image);placeholder.setVisibility(View.GONE);}
                });
            }catch(Exception ignored){}
        });
    }
    Bitmap load(Song song)throws Exception{Bitmap image=cache.get(song.key());if(image!=null)return image;
            if(song.local()){MediaMetadataRetriever reader=new MediaMetadataRetriever();try{reader.setDataSource(context,Uri.parse(song.source));byte[] bytes=reader.getEmbeddedPicture();if(bytes!=null)image=decode(bytes);}finally{reader.release();}}
            else{
                if(!song.cover.isEmpty()){
                    try{image=download(song.cover);}catch(IOException unavailable){if(!BuildConfig.MGV2000)throw unavailable;}
                }
                if(image==null){
                    String url=find(repo.get("/images",MusicRepository.params("hash",song.hash,"album_id",song.albumId,"album_audio_id",song.mixId)),0);
                    if(!url.isEmpty()&&!url.equals(song.cover))image=download(url);
                }
            }
        if(image!=null)cache.put(song.key(),image);return image;
    }
    private static Bitmap decode(byte[] bytes){if(bytes.length>8*1024*1024)return null;BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(options.outWidth<=0||options.outHeight<=0)return null;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>(BuildConfig.TV?720:900))options.inSampleSize*=2;options.inJustDecodeBounds=false;return BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);}
    private static Bitmap download(String address)throws Exception{
        address=address.replace("{size}","500").replace("{width}","500").replace("{height}","500");if(address.startsWith("//"))address="https:"+address;if(address.startsWith("http://"))address="https://"+address.substring(7);
        for(int redirects=0;redirects<4;redirects++){URL url=new URL(address);if(!url.getProtocol().equals("https"))return null;HttpURLConnection connection=(HttpURLConnection)url.openConnection();
            if(BuildConfig.MGV2000)((javax.net.ssl.HttpsURLConnection)connection).setSSLSocketFactory(Mgv2000MusicApi.Tls12.FACTORY);
            connection.setConnectTimeout(8000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            try{int status=connection.getResponseCode();if(status>=300&&status<400){String location=connection.getHeaderField("Location");if(location==null)return null;address=new URL(url,location).toString();continue;}if(status!=200)return null;try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>8*1024*1024)return null;output.write(buffer,0,n);}return decode(output.toByteArray());}}finally{connection.disconnect();}
        }return null;
    }
    static String find(Object value,int depth){if(depth>7)return "";if(value instanceof JSONObject){JSONObject object=(JSONObject)value;for(String key:new String[]{"album_sizable_cover","sizable_cover","cover","album_cover","imgurl","pic","url"}){String url=Song.text(object,key);if(url.startsWith("http://")||url.startsWith("https://")||url.startsWith("//"))return url;}for(String key:new String[]{"album","album_info","album_image","album_img","data","info","imgs","images"}){String url=find(object.opt(key),depth+1);if(!url.isEmpty())return url;}}else if(value instanceof JSONArray){JSONArray array=(JSONArray)value;for(int i=0;i<array.length();i++){String url=find(array.opt(i),depth+1);if(!url.isEmpty())return url;}}return "";}
}
