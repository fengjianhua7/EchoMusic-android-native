package cc.echomusic.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Private offline copies of successfully started handheld playback. */
@android.annotation.TargetApi(26)
final class PlaybackCache {
    private static final String ENABLED="auto_cache_audio";
    private static final long MAX_TRACK_BYTES=512L*1024*1024;
    private static PlaybackCache instance;
    static synchronized PlaybackCache get(Context context){
        if(instance==null)instance=new PlaybackCache(context.getApplicationContext());
        return instance;
    }

    private final File root;
    private final SharedPreferences prefs;
    private final ExecutorService transfers=Executors.newSingleThreadExecutor();
    private final Set<File> pending=new HashSet<>();
    private volatile int revision;

    private PlaybackCache(Context context){
        root=new File(context.getNoBackupFilesDir(),"offline-audio");
        prefs=context.getSharedPreferences("echo",0);
        transfers.execute(this::removeInterruptedCopies);
    }

    private synchronized void removeInterruptedCopies(){
        File[] folders=root.listFiles(File::isDirectory);
        if(folders==null)return;
        for(File folder:folders){
            File[] files=folder.listFiles(File::isFile);
            if(files==null)continue;
            for(File file:files){
                String name=file.getName();
                if(name.endsWith(".part")||name.endsWith(".new")||name.endsWith(".audio")&&!complete(file))
                    file.delete();
            }
        }
    }

    boolean enabled(){return prefs.getBoolean(ENABLED,false);}
    synchronized void enabled(boolean value){
        if(enabled()!=value){++revision;prefs.edit().putBoolean(ENABLED,value).apply();}
    }
    String quality(Song song){return song.cloudDisk()?"disk":prefs.getString("playback_quality","128");}

    private static String digest(String value){
        try{
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result=new StringBuilder();
            for(byte b:bytes)result.append(Character.forDigit((b>>4)&15,16)).append(Character.forDigit(b&15,16));
            return result.toString();
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private File folder(String account){return new File(root,digest(account));}
    private File audio(String account,Song song,String quality){return new File(folder(account),digest(song.key()+"\n"+quality)+".audio");}
    private static File metadata(File audio){return new File(audio.getParentFile(),audio.getName()+".json");}
    private static boolean complete(File audio){return audio.isFile()&&audio.length()>0&&metadata(audio).isFile();}

    String find(String account,Song song,String quality,boolean fallback){
        if(song.local()||song.key().isEmpty()||song.cloudDisk()&&!account.equals(song.cloudDiskKey()))return "";
        File file=audio(account,song,quality);
        if(complete(file))return android.net.Uri.fromFile(file).toString();
        if(fallback&&!song.cloudDisk()){
            for(String alternative:new String[]{"flac","320","128"}){
                File other=audio(account,song,alternative);
                if(complete(other))return android.net.Uri.fromFile(other).toString();
            }
        }
        return "";
    }

    synchronized void schedule(MusicRepository repo,CloudLibrary.Session<AuthStore.Scope> session,Song song,String quality,String url){
        if(!enabled()||song.local()||song.key().isEmpty()||!repo.auth.current(session.scope)
            ||song.cloudDisk()&&!session.key.equals(song.cloudDiskKey())||!(url.startsWith("https://")||url.startsWith("http://")))return;
        File file=audio(session.key,song,quality);
        if(complete(file)||!pending.add(file))return;
        int ticket=revision;
        transfers.execute(()->{
            try{download(repo,session,song,url,file,ticket);}
            catch(Exception unavailable){android.util.Log.d("EchoMusic","Audio cache transfer did not complete");}
            finally{synchronized(this){pending.remove(file);}}
        });
    }

    private void requireActive(MusicRepository repo,AuthStore.Scope scope,int ticket)throws IOException{
        if(ticket!=revision||!enabled()||!repo.auth.current(scope))throw new IOException("Cache request ended");
    }
    private void download(MusicRepository repo,CloudLibrary.Session<AuthStore.Scope> session,Song song,
                          String url,File file,int ticket)throws Exception{
        File temporary;
        synchronized(this){
            requireActive(repo,session.scope,ticket);
            File folder=file.getParentFile();
            if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cache directory unavailable");
            temporary=File.createTempFile("download-",".part",folder);
        }
        try{
            HttpURLConnection connection=(HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept-Encoding","identity");
            try{
                if(connection.getResponseCode()!=200)throw new IOException("Incomplete audio response");
                String type=connection.getContentType();
                if(type!=null&&(type.startsWith("text/")||type.contains("json")||type.contains("mpegurl")))
                    throw new IOException("Response is not an audio file");
                long expected=connection.getContentLengthLong();
                if(expected>MAX_TRACK_BYTES||expected>temporary.getUsableSpace())throw new IOException("Audio exceeds available storage");
                long length=0;
                try(InputStream input=connection.getInputStream();FileOutputStream output=new FileOutputStream(temporary)){
                    byte[] buffer=new byte[32768];
                    int count;
                    while((count=input.read(buffer))!=-1){
                        requireActive(repo,session.scope,ticket);
                        length+=count;
                        if(length>MAX_TRACK_BYTES)throw new IOException("Audio file too large");
                        output.write(buffer,0,count);
                    }
                    if(length==0||expected>=0&&expected!=length)throw new IOException("Incomplete audio");
                    output.getFD().sync();
                }
            }finally{connection.disconnect();}
            synchronized(this){
                requireActive(repo,session.scope,ticket);
                Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                AtomicFile manifest=new AtomicFile(metadata(file));
                FileOutputStream out=null;
                try{
                    out=manifest.startWrite();
                    out.write(song.json().toString().getBytes(StandardCharsets.UTF_8));
                    manifest.finishWrite(out);
                }catch(IOException failure){
                    if(out!=null)manifest.failWrite(out);
                    file.delete();
                    throw failure;
                }
            }
        }finally{temporary.delete();}
    }

    List<Song> songs(String account){
        File[] files=folder(account).listFiles((dir,name)->name.endsWith(".audio"));
        if(files==null)return Collections.emptyList();
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        Map<String,Song> songs=new LinkedHashMap<>();
        for(File file:files){
            if(!complete(file))continue;
            try{
                byte[] data=Files.readAllBytes(metadata(file).toPath());
                Song song=Song.fromJson(new JSONObject(new String(data,StandardCharsets.UTF_8)));
                if(!song.key().isEmpty())songs.putIfAbsent(song.key(),song);
            }catch(Exception ignored){}
        }
        return new ArrayList<>(songs.values());
    }

    long bytes(){
        long bytes=0;
        File[] folders=root.listFiles(File::isDirectory);
        if(folders!=null)for(File folder:folders){
            File[] files=folder.listFiles(File::isFile);
            if(files!=null)for(File file:files)bytes+=file.length();
        }
        return bytes;
    }

    synchronized boolean clear(){
        ++revision;
        boolean success=true;
        File[] folders=root.listFiles(File::isDirectory);
        if(folders!=null)for(File folder:folders){
            File[] files=folder.listFiles(File::isFile);
            if(files!=null)for(File file:files)if(!file.delete()&&file.exists())success=false;
            folder.delete();
        }
        return success;
    }
}
