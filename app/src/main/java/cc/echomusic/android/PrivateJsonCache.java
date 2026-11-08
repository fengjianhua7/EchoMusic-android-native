package cc.echomusic.android;

import android.content.Context;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Locale;

/** Disposable, account-scoped metadata cache; never contains auth credentials. */
public final class PrivateJsonCache {
    private final File root;
    public PrivateJsonCache(Context context){root=new File((android.os.Build.VERSION.SDK_INT>=21?context.getNoBackupFilesDir():context.getFilesDir()),BuildConfig.TV?"music-cache-tv-v5":"music-cache-v4");if(BuildConfig.TV){File legacy=new File((android.os.Build.VERSION.SDK_INT>=21?context.getNoBackupFilesDir():context.getFilesDir()),"music-cache-v4");File[] old=legacy.listFiles();if(old!=null)for(File entry:old)if(entry.isFile())entry.delete();legacy.delete();trim();}}
    private File path(String key)throws Exception{byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format(Locale.ROOT,"%02x",b&255));if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法保存本地缓存");return new File(root,name+".json");}
    public synchronized String read(String key)throws Exception{AtomicFile file=new AtomicFile(path(key));try(InputStream input=file.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>32*1024*1024)throw new IOException("缓存过大");}return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}catch(FileNotFoundException e){return null;}}
    public synchronized void write(String key,String text)throws Exception{AtomicFile file=new AtomicFile(path(key));FileOutputStream output=null;try{output=file.startWrite();output.write(text.getBytes(StandardCharsets.UTF_8));file.finishWrite(output);if(BuildConfig.TV)trim();}catch(Exception e){if(output!=null)file.failWrite(output);throw e;}}
    private void trim(){File[] files=root.listFiles((dir,name)->name.endsWith(".json"));if(files==null)return;java.util.Arrays.sort(files,java.util.Comparator.comparingLong(File::lastModified));long bytes=0;for(File file:files)bytes+=file.length();for(File file:files){if(bytes<=8L*1024*1024)break;long size=file.length();if(file.delete())bytes-=size;}}
}
