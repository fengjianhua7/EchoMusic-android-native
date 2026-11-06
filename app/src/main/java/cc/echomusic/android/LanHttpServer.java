package cc.echomusic.android;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Small bounded LAN-only HTTP transport, deliberately separate from the private account API. */
final class LanHttpServer implements AutoCloseable {
    interface Handler {Reply handle(String method,String path,Map<String,String> query,String body)throws Exception;}
    static final class Reply {final int status;final String type;final byte[] body;Reply(int status,String type,byte[] body){this.status=status;this.type=type;this.body=body;}static Reply text(int status,String type,String body){return new Reply(status,type,body.getBytes(StandardCharsets.UTF_8));}}
    private final Handler handler;private final String token;private final ServerSocket listener;
    private final Set<Socket> sockets=ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(0,3,20,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{Thread t=new Thread(r,"Echo-LAN-client");t.setDaemon(true);return t;});
    private volatile boolean running=true;private final Map<String,long[]> failures=new HashMap<>();
    LanHttpServer(int port,String token,Handler handler)throws IOException{this.token=token;this.handler=handler;listener=new ServerSocket();listener.setReuseAddress(true);listener.bind(new InetSocketAddress("0.0.0.0",port),8);Thread thread=new Thread(this::accept,"Echo-LAN");thread.setDaemon(true);thread.start();}
    int port(){return listener.getLocalPort();}
    private void accept(){while(running)try{Socket socket=listener.accept();if(!privatePeer(socket.getInetAddress())){socket.close();continue;}sockets.add(socket);try{workers.execute(()->serve(socket));}catch(RejectedExecutionException e){sockets.remove(socket);socket.close();}}catch(IOException e){if(!running)return;}}
    static boolean privatePeer(InetAddress address){byte[] b=address.getAddress();return address.isLoopbackAddress()||address.isSiteLocalAddress()||address.isLinkLocalAddress()||(b.length==16&&(b[0]&0xfe)==0xfc);}
    private static String line(InputStream input)throws IOException{ByteArrayOutputStream bytes=new ByteArrayOutputStream();int c;while((c=input.read())!=-1){if(c=='\n')break;if(c!='\r')bytes.write(c);if(bytes.size()>4096)throw new IOException("line too long");}return c==-1&&bytes.size()==0?null:bytes.toString(StandardCharsets.UTF_8.name());}
    private void serve(Socket socket){try(Socket owned=socket){socket.setSoTimeout(5000);InputStream input=socket.getInputStream();String first=line(input);if(first==null)return;String[] parts=first.split(" ");if(parts.length!=3)return;String method=parts[0];URI uri=new URI(parts[1]);if(uri.isAbsolute()||uri.getRawPath()==null)throw new IOException("target");Map<String,String> headers=new HashMap<>();int size=first.length();String header;while((header=line(input))!=null&&!header.isEmpty()){size+=header.length();if(size>16384)throw new IOException("headers");int split=header.indexOf(':');if(split<1)throw new IOException("header");String key=header.substring(0,split).toLowerCase(Locale.ROOT);if(headers.put(key,header.substring(split+1).trim())!=null)throw new IOException("duplicate");}
        String host=headers.getOrDefault("host","");if(!allowedHost(host)){send(socket,Reply.text(403,"text/plain","Invalid host"),null);return;}String origin=headers.get("origin");if(origin!=null&&!origin.equals("http://"+host)){send(socket,Reply.text(403,"text/plain","Origin rejected"),null);return;}
        if(headers.containsKey("transfer-encoding"))throw new IOException("unsupported encoding");int length=Integer.parseInt(headers.getOrDefault("content-length","0"));if(length<0||length>8192)throw new IOException("body");Map<String,String> query=query(uri.getRawQuery());
        String supplied=query.getOrDefault("token","");for(String cookie:headers.getOrDefault("cookie","").split(";")){String trimmed=cookie.trim();if(trimmed.startsWith("echo_session="))supplied=trimmed.substring(13);}
        String peer=socket.getInetAddress().getHostAddress();boolean directEntry=method.equals("GET")&&uri.getPath().equals("/");boolean authenticated=directEntry||MessageDigest.isEqual(token.getBytes(StandardCharsets.US_ASCII),supplied.getBytes(StandardCharsets.US_ASCII));
        if(!authenticated){boolean throttled; synchronized(failures){long now=System.currentTimeMillis();long[] entry=failures.get(peer);if(entry==null||now-entry[0]>60000){entry=new long[]{now,0};if(failures.size()>64)failures.clear();failures.put(peer,entry);}throttled=++entry[1]>10;}send(socket,Reply.text(throttled?429:403,"text/html; charset=utf-8","<!doctype html><meta name=viewport content='width=device-width'><h2>EchoMusic 远控配对</h2><p>请在电视或应用的远控设置页打开配对地址。</p>"),null);return;}
        if(!method.equals("GET")&&!method.equals("POST")){send(socket,Reply.text(405,"text/plain","Method not allowed"),null);return;}byte[] data=new byte[length];int offset=0;while(offset<length){int n=input.read(data,offset,length-offset);if(n<0)throw new IOException("body");offset+=n;}
        Reply reply=handler.handle(method,uri.getPath(),query,new String(data,StandardCharsets.UTF_8));send(socket,reply,"echo_session="+token+"; Path=/; HttpOnly; SameSite=Strict");
    }catch(Exception ignored){try{if(!socket.isClosed())send(socket,Reply.text(400,"text/plain","Request failed"),null);}catch(Exception ignoredAgain){}}finally{sockets.remove(socket);}}
    private boolean allowedHost(String host)throws SocketException{int split=host.lastIndexOf(':');if(split<0||!host.substring(split+1).equals(String.valueOf(port())))return false;String name=host.substring(0,split);if(name.equals("127.0.0.1")||name.equals("localhost"))return true;Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();while(interfaces.hasMoreElements()){Enumeration<InetAddress> addresses=interfaces.nextElement().getInetAddresses();while(addresses.hasMoreElements())if(name.equals(addresses.nextElement().getHostAddress()))return true;}return false;}
    static Map<String,String> query(String text)throws UnsupportedEncodingException{Map<String,String> out=new HashMap<>();if(text!=null)for(String part:text.split("&")){String[] pair=part.split("=",2);out.put(URLDecoder.decode(pair[0],"UTF-8"),pair.length==2?URLDecoder.decode(pair[1],"UTF-8"):"");}return out;}
    private static void send(Socket socket,Reply reply,String cookie)throws IOException{OutputStream out=socket.getOutputStream();String headers="HTTP/1.1 "+reply.status+" Result\r\nContent-Type: "+reply.type+"\r\nContent-Length: "+reply.body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\n"+(cookie==null?"":"Set-Cookie: "+cookie+"\r\n")+"\r\n";out.write(headers.getBytes(StandardCharsets.US_ASCII));out.write(reply.body);out.flush();}
    public void close(){running=false;try{listener.close();}catch(IOException ignored){}for(Socket socket:sockets)try{socket.close();}catch(IOException ignored){}workers.shutdownNow();}
}
