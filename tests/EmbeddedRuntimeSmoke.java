import com.caoccao.javet.interop.*;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.node.modules.NodeModuleModule;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class EmbeddedRuntimeSmoke {
  static final String TOKEN="a".repeat(64);
  static JSONObject get(int port,String route,boolean authenticated)throws Exception{
    HttpURLConnection c=(HttpURLConnection)new URL("http://127.0.0.1:"+port+route).openConnection();c.setConnectTimeout(5000);c.setReadTimeout(30000);c.setRequestProperty("Connection","close");if(authenticated)c.setRequestProperty("X-Echo-Token",TOKEN);
    try{int status=c.getResponseCode();try(InputStream in=status<400?c.getInputStream():c.getErrorStream()){JSONObject body=new JSONObject(new String(in.readAllBytes(),StandardCharsets.UTF_8));body.put("__http",status);return body;}}finally{c.disconnect();}
  }
  static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);System.out.println("PASS: "+message);}
  public static void main(String[] args)throws Exception{
    File directory=new File(args[0]).getCanonicalFile();CompletableFuture<Integer> port=new CompletableFuture<>();AtomicBoolean stop=new AtomicBoolean(false);AtomicReference<Throwable> error=new AtomicReference<>();
    Thread thread=new Thread(()->{try(NodeRuntime runtime=V8Host.getNodeInstance().createV8Runtime()){
      runtime.getNodeModule(NodeModuleModule.class).setRequireRootDirectory(directory);
      runtime.getExecutor("globalThis.console={log(){},info(){},warn(){},error(){},debug(){}};globalThis.__port=0;globalThis.__server=null;globalThis.__error='';require("+JSONObject.quote(new File(directory,"bootstrap.cjs").getAbsolutePath())+").start({token:'"+TOKEN+"',guid:'"+"b".repeat(32)+"',dev:'CCCCCCCCCC',uuid:'"+"d".repeat(32)+"'}).then(x=>{__port=x.port;__server=x.server},e=>{__error=String(e)});").executeVoid();
      while(!stop.get()){
        runtime.await(V8AwaitMode.RunNoWait);
        String failure=runtime.getExecutor("globalThis.__error").executeString();if(!failure.isEmpty())throw new IOException(failure);
        int p=runtime.getExecutor("globalThis.__port").executeInteger();if(p>0)port.complete(p);Thread.sleep(3);
      }
      runtime.getExecutor("__server.closeAllConnections();__server.close();require('http').globalAgent.destroy();require('https').globalAgent.destroy();").executeVoid();
      for(int i=0;i<500&&runtime.await(V8AwaitMode.RunNoWait);i++)Thread.sleep(2);
    }catch(Throwable t){error.set(t);port.completeExceptionally(t);}},"Javet-Smoke");
    thread.start();
    try{
      int p=port.get(30,TimeUnit.SECONDS);
      check(get(p,"/__health",false).getInt("__http")==403,"unauthenticated local access rejected");
      JSONObject health=get(p,"/__health",true);check(health.getInt("status")==1,"real embedded engine starts private server");System.out.println("Embedded Node version: "+health.getString("runtime"));
      check(get(p,"/package.json",true).getInt("__http")==404,"bundle files are not exposed");
      JSONObject qr=get(p,"/login/qr/create?key=fixture-test-key&qrimg=true",true);check(qr.getInt("__http")==200&&qr.getJSONObject("data").getString("base64").startsWith("data:image/png;base64,"),"real bundled QR module produces a PNG");
      if(args.length>1&&args[1].equals("--live-qr")){
        JSONObject live=get(p,"/login/qr/key",true);JSONObject data=live.optJSONObject("data");
        boolean obtained=live.optInt("status")==1&&data!=null&&(!data.optString("qrcode").isEmpty()||!data.optString("key").isEmpty());
        System.out.println("Live official QR key obtained: "+obtained+"; HTTP="+live.optInt("__http")+"; error_code="+live.optInt("error_code"));
      }
    }finally{stop.set(true);thread.join(15000);if(thread.isAlive())throw new AssertionError("Worker did not close");if(error.get()!=null)throw new RuntimeException(error.get());}
  }
}
