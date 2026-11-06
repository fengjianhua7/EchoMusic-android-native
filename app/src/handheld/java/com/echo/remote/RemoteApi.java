package com.echo.remote;
import cc.echomusic.android.R;

import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

class RemoteApi {
    final String base;
    private MusicLibrary library;
    void configureLibrary(android.content.Context context) { library=new MusicLibrary(context,this); }
    boolean localLibrary() { return library!=null && library.isLocal(); }
    RemoteApi(String address) {
        URI uri = URI.create(address.trim());
        String host = uri.getHost();
        if (!"http".equals(uri.getScheme()) || host == null || uri.getRawUserInfo() != null ||
            uri.getRawQuery() != null || uri.getRawFragment() != null ||
            !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException("请输入 http://电脑局域网IP:1987");
        // Restrict cleartext requests to literal LAN addresses. No Internet hostnames.
        if (!(host.matches("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") ||
              host.matches("192\\.168\\.\\d{1,3}\\.\\d{1,3}") ||
              host.matches("172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}")))
            throw new IllegalArgumentException("仅支持局域网 IPv4 地址");
        for (String part : host.split("\\.")) if (Integer.parseInt(part) > 255) throw new IllegalArgumentException("IP 地址无效");
        if (uri.getPort() < 1 || uri.getPort() > 65535) throw new IllegalArgumentException("请填写端口");
        base = "http://" + host + ":" + uri.getPort();
    }
    JSONObject get(String path) throws Exception {
        if(library!=null && "/api/library".equals(path)) return library.view();
        JSONObject data=request(path,null);
        if(library!=null && (path.startsWith("/api/search?") || path.startsWith("/api/queue?")))library.remember(data.optJSONArray("songs"));
        return data;
    }
    JSONObject command(JSONObject body) throws Exception {
        if(library!=null && MusicLibrary.handles(body.optString("action")))return library.command(body);
        return request("/api/command",body);
    }
    static class ApiException extends IOException {
        final int status;
        ApiException(int status,String message){super(message);this.status=status;}
        boolean unsupported() { String m=getMessage();return status==404||status==501||m.contains("未知操作")||m.toLowerCase(java.util.Locale.ROOT).contains("unknown action"); }
    }
    android.graphics.Bitmap cover() throws Exception {
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/api/cover").openConnection();
        c.setConnectTimeout(2500); c.setReadTimeout(3000); c.setInstanceFollowRedirects(false);
        try {
            if(c.getResponseCode()!=200) return null;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()) {
                byte[] buffer=new byte[8192]; int n;
                while((n=in.read(buffer))!=-1) { bytes.write(buffer,0,n); if(bytes.size()>8*1024*1024) return null; }
            }
            byte[] data=bytes.toByteArray();
            android.graphics.BitmapFactory.Options o=new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds=true; android.graphics.BitmapFactory.decodeByteArray(data,0,data.length,o);
            if(o.outWidth<=0 || o.outHeight<=0) return null;
            o.inSampleSize=1; while(Math.max(o.outWidth,o.outHeight)/o.inSampleSize>768) o.inSampleSize*=2;
            o.inJustDecodeBounds=false;
            return android.graphics.BitmapFactory.decodeByteArray(data,0,data.length,o);
        } finally { c.disconnect(); }
    }
    JSONObject request(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(base + path).openConnection();
        c.setConnectTimeout(3000); c.setReadTimeout(path.equals("/api/ktv/start")?25000:4000); c.setInstanceFollowRedirects(false);
        try {
            if (body != null) {
                c.setRequestMethod("POST"); c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = c.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            }
            if (c.getResponseCode() != 200) {
                String error="电脑响应 "+c.getResponseCode()+"，请检查插件版本";
                InputStream stream=c.getErrorStream();
                if(stream!=null)try(InputStream in=stream){
                    ByteArrayOutputStream errorBytes=new ByteArrayOutputStream();
                    byte[] buffer=new byte[4096];int count;
                    while(errorBytes.size()<32768 && (count=in.read(buffer,0,Math.min(buffer.length,32768-errorBytes.size())))!=-1)
                        errorBytes.write(buffer,0,count);
                    byte[] data=errorBytes.toByteArray();
                    try{error=new JSONObject(new String(data,StandardCharsets.UTF_8)).optString("error",error);}catch(JSONException ignored){}
                }
                throw new ApiException(c.getResponseCode(),error);
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) { bytes.write(buf, 0, n); if (bytes.size() > 4 * 1024 * 1024) throw new IOException("响应过大"); }
            }
            JSONObject result = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if (result.has("ok") && !result.optBoolean("ok")) throw new ApiException(200,result.optString("error", "操作失败"));
            return result;
        } finally { c.disconnect(); }
    }
    static JSONObject body(String action, Object... fields) {
        JSONObject o = new JSONObject();
        try { o.put("action", action); for (int i=0; i<fields.length; i+=2) o.put((String)fields[i], fields[i+1]); }
        catch (JSONException e) { throw new IllegalArgumentException(e); }
        return o;
    }
    static String lyric(JSONObject state, JSONObject lyrics) {
        JSONObject pb = state.optJSONObject("playback");
        if (pb == null) return "暂无播放";
        double t = pb.optDouble("currentTime", 0) + lyrics.optDouble("timeOffset", 0)/1000;
        JSONArray lines = lyrics.optJSONArray("lines");
        String text = pb.optString("title");
        if (lines != null) for (int i=0; i<lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line != null && line.optDouble("time") <= t) text = line.optString("text");
        }
        return text;
    }
}
