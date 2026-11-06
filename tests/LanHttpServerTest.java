package cc.echomusic.android;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class LanHttpServerTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    static String request(int port,String method,String path,String extra,String body)throws Exception{
        try(Socket socket=new Socket("127.0.0.1",port)){socket.setSoTimeout(6000);String text=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\n"+extra+"Content-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\n\r\n"+body;socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=socket.getInputStream().read(buffer))!=-1)output.write(buffer,0,n);return output.toString("UTF-8");}
    }
    public static void main(String[] args)throws Exception{
        String token="0123456789abcdef0123456789abcdef";AtomicInteger calls=new AtomicInteger();
        LanHttpServer server=new LanHttpServer(0,token,(method,path,query,body)->{calls.incrementAndGet();return LanHttpServer.Reply.text(200,"application/json","{\"ok\":true}");});int port=server.port();
        try{
            check(request(port,"GET","/api/state","","").startsWith("HTTP/1.1 403"),"unpaired API request rejected");check(calls.get()==0,"denied requests never enter application handler");
            String direct=request(port,"GET","/","","");check(direct.startsWith("HTTP/1.1 200")&&direct.contains("Set-Cookie: echo_session="+token),"IP and port bootstrap same-origin session");
            String stale=request(port,"GET","/","Cookie: echo_session=old-version-session\r\n","");check(stale.startsWith("HTTP/1.1 200")&&stale.contains("Set-Cookie: echo_session="+token),"homepage renews stale session after application update");
            check(request(port,"GET","/","Origin: http://evil.invalid\r\n","").startsWith("HTTP/1.1 403"),"cross-origin bootstrap rejected");
            check(request(port,"POST","/api/command","","{}").startsWith("HTTP/1.1 403"),"direct entry does not allow unauthenticated commands");
            String paired=request(port,"GET","/?token="+token,"","");check(paired.startsWith("HTTP/1.1 200"),"explicit pairing URL accepted");check(paired.contains("HttpOnly; SameSite=Strict"),"browser receives protected same-origin session");check(paired.contains("Referrer-Policy: no-referrer"),"pairing token not forwarded as a referrer");
            String cookie="Cookie: echo_session="+token+"\r\n";check(request(port,"GET","/api/state",cookie,"").startsWith("HTTP/1.1 200"),"paired cookie survives removal of URL token");
            check(request(port,"POST","/api/command",cookie+"Origin: http://evil.invalid\r\n","{}").startsWith("HTTP/1.1 403"),"cross-origin command rejected");
            check(request(port,"POST","/api/command",cookie+"Origin: http://127.0.0.1:"+port+"\r\n","{\"action\":\"toggle\"}").startsWith("HTTP/1.1 200"),"same-origin command forwarded");
            check(request(port,"DELETE","/api/state",cookie,"").startsWith("HTTP/1.1 405"),"unexpected HTTP methods refused");
            check(request(port,"GET","/api/state",cookie+"Host: evil.invalid\r\n","").isEmpty(),"duplicate host rejected before routing");
            check(LanHttpServer.privatePeer(InetAddress.getByName("192.168.1.7")),"private LAN allowed");check(!LanHttpServer.privatePeer(InetAddress.getByName("8.8.8.8")),"public clients rejected");
            check(LanHttpServer.query("keywords=%E9%9F%B3%E4%B9%90&page=2").get("keywords").equals("音乐"),"UTF-8 search query preserved");
            int before=calls.get();request(port,"POST","/api/command",cookie,"x".repeat(8193));check(calls.get()==before,"oversize command body rejected before execution");
        }finally{server.close();}
        boolean stopped=false;try(Socket ignored=new Socket("127.0.0.1",port)){}catch(IOException expected){stopped=true;}check(stopped,"disable closes the listener");
        System.out.println("PASS: "+checks+" LAN transport integration checks");
    }
}
