package cc.echomusic.android;

import android.content.*;
import android.os.Looper;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.interop.*;
import com.caoccao.javet.node.modules.NodeModuleModule;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

/** Runs one private Node worker per app process, independent of activity recreation and playback UI. */
public final class EmbeddedApi {
    private static final EmbeddedApi INSTANCE=new EmbeddedApi();
    public static EmbeddedApi instance(){return INSTANCE;}
    public static final class Connection {
        public final String url,token;
        Connection(int port,String token){url="http://127.0.0.1:"+port;this.token=token;}
    }
    private Completion<Connection> ready;
    private volatile boolean failed=false;
    private volatile String state="正在准备音乐服务";
    private EmbeddedApi(){}
    public synchronized void start(Context context){
        if(ready!=null)return;
        if(android.os.Build.VERSION.SDK_INT<21){ready=new Completion<>();ready.completeExceptionally(new IOException("请在电视设置中配置外部音乐服务地址"));return;}
        Context app=context.getApplicationContext();ready=new Completion<>();Completion<Connection> completion=ready;
        Thread worker=new Thread(()->run(app,completion),"EchoMusic-API");worker.setDaemon(true);worker.start();
    }
    public String state(){return state;}
    public Connection await(Context context) throws IOException {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IOException("音乐服务正在准备，请稍候");
        if(android.os.Build.VERSION.SDK_INT<21)throw new IOException("请在电视设置中配置外部音乐服务地址");
        start(context);
        try{Connection connection=ready.get(45,TimeUnit.SECONDS);if(failed)throw new IOException("音乐服务已停止，请完全退出应用后重新打开");return connection;}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("操作已取消");}
        catch(TimeoutException e){throw new IOException("音乐服务准备时间较长，请稍后重试");}
        catch(ExecutionException e){throw new IOException("音乐服务启动失败，请完全退出应用后重新打开");}
    }
    private void run(Context app,Completion<Connection> completion){
        try{
            File directory=prepare(app);
            byte[] random=new byte[32];new SecureRandom().nextBytes(random);String token=hex(random);
            JSONObject config=new JSONObject();config.put("token",token);config.put("guid",identity(app,"api_guid"));config.put("dev",identity(app,"api_dev"));config.put("uuid",identity(app,"api_uuid"));
            if(BuildConfig.NODE_MOBILE){runMobile(directory,config,token,completion);return;}
            try(NodeRuntime runtime=V8Host.getNodeInstance().createV8Runtime()){
                runtime.getNodeModule(NodeModuleModule.class).setRequireRootDirectory(directory);
                // Credentials and request URLs must never enter a JavaScript console / logcat stream.
                runtime.getExecutor("globalThis.console={log(){},warn(){},error(){},info(){},debug(){}};globalThis.__echoPort=0;globalThis.__echoFailure=false;").executeVoid();
                String entry=new File(directory,"bootstrap.cjs").getAbsolutePath();
                runtime.getExecutor("require("+JSONObject.quote(entry)+").start("+config+").then(value=>{globalThis.__echoPort=value.port},()=>{globalThis.__echoFailure=true})").executeVoid();
                while(true){
                    runtime.await(V8AwaitMode.RunOnce);
                    if(runtime.getExecutor("globalThis.__echoFailure").executeBoolean())throw new IOException("Local startup failed");
                    int port=runtime.getExecutor("globalThis.__echoPort").executeInteger();
                    if(port>0){state="音乐服务已就绪";completion.complete(new Connection(port,token));break;}
                }
                // RunOnce sleeps in libuv until I/O/timers arrive; no polling timer or busy loop is needed.
                while(runtime.await(V8AwaitMode.RunOnce)){}
                throw new IOException("Local worker stopped");
            }
        }catch(Throwable failure){failed=true;state="音乐服务启动失败";completion.completeExceptionally(new IOException("Embedded API unavailable"));}
    }
    private void runMobile(File directory,JSONObject config,String token,Completion<Connection> completion)throws Exception{
        String suffix=UUID.randomUUID().toString();File readyFile=new File(directory,"node-ready-"+suffix+".json"),entry=new File(directory,"node-entry-"+suffix+".cjs");
        String script="globalThis.console={log(){},warn(){},error(){},info(){},debug(){}};process.chdir("+JSONObject.quote(directory.getAbsolutePath())+");require('./bootstrap.cjs').start("+config+").then(value=>{const fs=require('node:fs');const file="+JSONObject.quote(readyFile.getAbsolutePath())+";fs.writeFileSync(file+'.tmp',JSON.stringify({port:value.port}));fs.renameSync(file+'.tmp',file);},()=>{process.exitCode=1;});";
        try(OutputStream output=new FileOutputStream(entry)){output.write(script.getBytes(StandardCharsets.UTF_8));}
        java.util.concurrent.atomic.AtomicBoolean stopped=new java.util.concurrent.atomic.AtomicBoolean();
        Thread node=new Thread(()->{try{NativeNode.start(entry.getAbsolutePath());}catch(Throwable ignored){}finally{stopped.set(true);}},"EchoMusic-Node-ARMv7");node.setDaemon(true);node.start();
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        try{while(!readyFile.isFile()){if(stopped.get()||System.nanoTime()>until)throw new IOException("ARMv7 music service did not start");Thread.sleep(100);}
            JSONObject result;try(InputStream input=new FileInputStream(readyFile)){result=new JSONObject(read(input,4096));}int port=result.optInt("port",0);if(port<1||port>65535)throw new IOException("Invalid local port");state="音乐服务已就绪（ARMv7 兼容版）";completion.complete(new Connection(port,token));node.join();throw new IOException("ARMv7 music service stopped");
        }finally{readyFile.delete();entry.delete();}
    }
    private static String identity(Context context,String key){
        SharedPreferences preferences=context.getSharedPreferences("echo",Context.MODE_PRIVATE);
        String value=preferences.getString(key,"");if(value.isEmpty()){value=UUID.randomUUID().toString().replace("-","");preferences.edit().putString(key,value).apply();}return value;
    }
    private static String hex(byte[] bytes){StringBuilder text=new StringBuilder();for(byte value:bytes)text.append(String.format(Locale.ROOT,"%02x",value&255));return text.toString();}
    @android.annotation.TargetApi(21)
    private static File prepare(Context context) throws Exception {
        JSONObject manifest;
        try(InputStream input=context.getAssets().open("embedded-api.json")){manifest=new JSONObject(read(input,65536));}
        String expected=manifest.getString("sha256");if(!expected.matches("[a-f0-9]{64}"))throw new IOException("Invalid bundled manifest");
        File parent=new File(context.getNoBackupFilesDir(),"music-api");if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Cannot prepare private directory");
        File target=new File(parent,expected);
        if(new File(target,".ready").isFile()&&new File(target,"bootstrap.cjs").isFile())return target;
        if(target.exists())deleteStage(target);
        File archive=File.createTempFile("bundle-",".zip",parent);
        File stage=new File(parent,"stage-"+UUID.randomUUID());
        try{
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            try(InputStream input=context.getAssets().open("echo-api.zip");OutputStream output=new FileOutputStream(archive)){
                byte[] buffer=new byte[32768];int count;while((count=input.read(buffer))!=-1){digest.update(buffer,0,count);output.write(buffer,0,count);}
            }
            if(!hex(digest.digest()).equals(expected))throw new IOException("Bundled API checksum mismatch");
            if(!stage.mkdirs())throw new IOException("Cannot extract bundled API");String prefix=stage.getCanonicalPath()+File.separator;
            int files=0;long total=0;
            try(ZipInputStream zip=new ZipInputStream(new FileInputStream(archive))){ZipEntry entry;byte[] buffer=new byte[32768];while((entry=zip.getNextEntry())!=null){
                if(++files>20000)throw new IOException("Bundle contains too many files");File file=new File(stage,entry.getName());if(!file.getCanonicalPath().startsWith(prefix))throw new IOException("Invalid bundled file path");
                if(entry.isDirectory()){if(!file.isDirectory()&&!file.mkdirs())throw new IOException("Cannot create bundled directory");continue;}
                File owner=file.getParentFile();if(owner==null||(!owner.isDirectory()&&!owner.mkdirs()))throw new IOException("Cannot prepare bundled file");
                try(OutputStream output=new FileOutputStream(file)){int count;while((count=zip.read(buffer))!=-1){total+=count;if(total>128L*1024*1024)throw new IOException("Bundle is too large");output.write(buffer,0,count);}}
            }}
            if(!new File(stage,"bootstrap.cjs").isFile()||!new File(stage,"node_modules/axios/package.json").isFile())throw new IOException("Bundle is incomplete");
            try(OutputStream marker=new FileOutputStream(new File(stage,".ready"))){marker.write(expected.getBytes(StandardCharsets.UTF_8));}
            if(!stage.renameTo(target))throw new IOException("Cannot activate bundled API");return target;
        }finally{archive.delete();deleteStage(stage);}
    }
    private static String read(InputStream input,int limit) throws IOException {try(ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){bytes.write(buffer,0,count);if(bytes.size()>limit)throw new IOException("Manifest too large");}return new String(bytes.toByteArray(),StandardCharsets.UTF_8);}}
    private static void deleteStage(File directory){if(!directory.exists())return;File[] children=directory.listFiles();if(children!=null)for(File child:children)deleteStage(child);directory.delete();}
}
