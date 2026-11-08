package cc.echomusic.android;

import android.content.SharedPreferences;
import android.net.Uri;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.net.ssl.*;

/** Direct transport for the KitKat box, using the TV service's upstream protocol. */
final class Mgv2000MusicApi {
    private static final String GATEWAY = "https://gateway.kugou.com";
    private static final String LOGIN = "https://login-user.kugou.com";
    private static final String LYRICS = "https://lyrics.kugou.com";
    private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String APP_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    private final String mid, device;

    Mgv2000MusicApi(SharedPreferences prefs) throws Exception {
        String guid;
        synchronized (Mgv2000MusicApi.class) {
            guid = prefs.getString("mgv_guid", "");
            if (guid.isEmpty()) {
                guid = UUID.randomUUID().toString().replace("-", "");
                prefs.edit().putString("mgv_guid", guid).apply();
            }
        }
        device = guid;
        mid = new BigInteger(md5(guid), 16).toString();
    }

    AuthRequestRunner.Response<AuthStore.Scope> get(String route, Map<String,String> input,
            AuthStore.Scope scope, boolean skipAuth) throws Exception {
        Map<String,String> account = new HashMap<>();
        if (!skipAuth) for (String pair : scope.authorization.split(";")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) account.put(kv[0].trim(), kv[1]);
        }
        String user = value(account, "userid", "0");
        String token = value(account, "token", "");
        Map<String,String> query = new TreeMap<>();
        put(query, "dfid", "-", "mid", mid, "uuid", "-", "appid", "3116",
            "clientver", "11440", "clienttime", String.valueOf(System.currentTimeMillis()/1000));
        if (!token.isEmpty()) query.put("token", token);
        if (!"0".equals(user)) query.put("userid", user);
        String origin = GATEWAY;
        String path;
        String router = "";
        String salt = APP_SALT;
        JSONObject post = null;
        switch (route) {
            case "/login/qr/key":
                origin = LOGIN; path = "/v2/qrcode"; salt = WEB_SALT;
                put(query, "appid", "1001", "type", "1", "plat", "4", "srcappid", "2919",
                    "qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&");
                break;
            case "/login/qr/check":
                origin = LOGIN; path = "/v2/get_userinfo_qrcode"; salt = WEB_SALT;
                put(query, "plat", "4", "srcappid", "2919", "qrcode", value(input,"key",""), "dev", device);
                break;
            case "/search":
                path = "/v2/search/song"; router = "complexsearch.kugou.com";
                put(query, "keyword", value(input,"keywords",""), "page", value(input,"page","1"),
                    "pagesize", value(input,"pagesize","30"), "platform", "AndroidFilter",
                    "iscorrection", "1", "privilegefilter", "0", "area_code", "1", "dopicfull", "1", "clientver", "201");
                break;
            case "/user/playlist":
                path = "/v7/get_all_list"; router = "cloudlist.service.kugou.com";
                put(query, "plat", "1", "userid", user, "token", token);
                post = new JSONObject().put("userid",user).put("token",token).put("total_ver",0)
                    .put("type",2).put("page",value(input,"page","1")).put("pagesize",value(input,"pagesize","30"));
                break;
            case "/playlist/track/all/new":
                path = "/v4/get_list_all_file_v3"; router = "cloudlist.service.kugou.com";
                post = new JSONObject().put("listid",value(input,"listid","")).put("userid",value(input,"userid",user))
                    .put("token",token).put("type",number(input,"type",0)).put("page",number(input,"page",1))
                    .put("pagesize",number(input,"pagesize",300)).put("area_code",1).put("allplatform",1).put("show_cover",1);
                break;
            case "/playlist/track/all":
                path = "/pubsongs/v2/get_other_list_file_nofilt";
                int pageSize = number(input,"pagesize",30);
                put(query, "area_code","1","begin_idx",String.valueOf((number(input,"page",1)-1)*pageSize),
                    "plat","1","type","1","mode","1","personal_switch","1","extend_fields","abtags,hot_cmt,popularization",
                    "pagesize",String.valueOf(pageSize),"global_collection_id",value(input,"id",""));
                break;
            case "/playlist/tracks/add":
                path = "/cloudlist.service/v6/add_song";
                put(query,"last_time",query.get("clienttime"),"last_area","gztx","userid",user,"token",token);
                JSONArray songs = new JSONArray();
                for (String entry : value(input,"data","").split(",")) {
                    String[] fields = entry.split("[|]", -1);
                    if (fields.length < 2 || fields[1].isEmpty()) throw new IOException("缺少歌曲标识");
                    songs.put(new JSONObject().put("number",1).put("name",fields[0]).put("hash",fields[1])
                        .put("size",0).put("sort",0).put("timelen",0).put("bitrate",0)
                        .put("album_id",fields.length>2?identifier(fields[2]):0)
                        .put("mixsongid",fields.length>3?identifier(fields[3]):0));
                }
                post = new JSONObject().put("userid",user).put("token",token).put("listid",value(input,"listid",""))
                    .put("list_ver",0).put("type",0).put("slow_upload",1).put("scene","false;null").put("data",songs);
                break;
            case "/playlist/tracks/del":
                path = "/v4/delete_songs"; router = "cloudlist.service.kugou.com";
                JSONArray ids = new JSONArray();
                for (String id : value(input,"fileids","").split(",")) {
                    long fileId=identifier(id);
                    if(fileId<=0)throw new IOException("缺少歌单歌曲标识");
                    ids.put(new JSONObject().put("fileid",fileId));
                }
                post = new JSONObject().put("listid",value(input,"listid","")).put("userid",user)
                    .put("data",ids).put("type",0).put("token",token).put("list_ver",0);
                break;
            case "/playlist/add":
                path = "/cloudlist.service/v5/add_list";
                put(query,"last_time",query.get("clienttime"),"last_area","gztx","userid",user,"token",token);
                post = new JSONObject().put("userid",user).put("token",token).put("total_ver",0)
                    .put("name",value(input,"name","")).put("type",0).put("source",1).put("is_pri",0)
                    .put("list_create_userid",value(input,"list_create_userid",user))
                    .put("list_create_gid","").put("from_shupinmv",0);
                break;
            case "/images":
                origin = "https://expendablekmr.kugou.com"; path = "/container/v2/image"; query.clear();
                JSONArray items = new JSONArray().put(new JSONObject().put("hash",value(input,"hash",""))
                    .put("album_id",value(input,"album_id","0")).put("album_audio_id",value(input,"album_audio_id","0")));
                put(query,"album_image_type","-3","appid","3116","clientver","11440","author_image_type","3,4,5",
                    "count","5","data",items.toString(),"isCdn","1","publish_time","1");
                break;
            case "/audio/accompany/matching":
                origin = "https://nsongacsing.kugou.com"; path = "/sing7/accompanywan/json/v2/cdn/optimal_matching_accompany_2_listen.do";
                query.clear(); salt = null;
                put(query,"isteen","0","mixId",value(input,"mixId","0"),"usemkv","1","platform","2",
                    "fileName",value(input,"fileName",""),"hash",value(input,"hash",""),"version","12375","appid","3116");
                StringBuilder signed = new StringBuilder();
                for(Map.Entry<String,String> entry:query.entrySet()){
                    if(signed.length()>0)signed.append('&');
                    signed.append(entry.getKey()).append('=').append(entry.getValue());
                }
                query.put("sign",md5(signed.append("*s&iN#G70*").toString()).substring(8,24));
                break;
            case "/__qr":
                String text = value(input,"text","");
                if(text.isEmpty()||text.length()>512)throw new IOException("二维码内容无效");
                android.graphics.Bitmap bitmap=Mgv2000Input.qr(text);
                ByteArrayOutputStream image=new ByteArrayOutputStream();
                try { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,image); }
                finally { bitmap.recycle(); }
                String base64=android.util.Base64.encodeToString(image.toByteArray(),android.util.Base64.NO_WRAP);
                return new AuthRequestRunner.Response<>(200,new JSONObject().put("status",1)
                    .put("image","data:image/png;base64,"+base64),"",scope);
            case "/user/verify":
                path = "/v1/user_verify"; router = "trackercdngz.kugou.com";
                put(query,"module_id","51");
                break;
            case "/song/url/auth/merge":
                Map<String,String> authorization = new TreeMap<>(query);
                String hash = value(input,"hash","").toLowerCase(Locale.ROOT);
                String mix = value(input,"album_audio_id","0");
                put(authorization,"authorization",value(input,"auth",""),"module_id","51","album_audio_id",mix,
                    "clientver","11561","hash",hash);
                AuthRequestRunner.Response<AuthStore.Scope> result = call(GATEWAY,"/v1/authorization","trackercdngz.kugou.com",authorization,APP_SALT,null,scope);
                if (result.status >= 400 || AuthProtocol.failed(result.body)) return result;
                JSONObject grant = result.body.optJSONObject("data");
                if (grant == null || grant.optString("auth","").isEmpty()) {
                    throw new IOException("未取得歌曲播放授权");
                }
                path = "/tracker/v5/url";
                String quality = value(input,"quality","128");
                if ("acappella".equals(quality)) quality = "magic_acappella";
                put(query,"album_id",value(input,"album_id","0"),"area_code","1","module","","hash",hash,
                    "need_m","0","ssa_flag","is_fromtrack","version","11430","open_time",grant.optString("open_time","0"),
                    "ptype","0","need_ogg","1","page_id","967177915","auth",grant.optString("auth"),"mtype","0",
                    "quality",quality,"album_audio_id",mix,"behavior","play","pid","411","module_id","51","cmd","26",
                    "ppage_id","356753938,823673182,967485191","clientver","11561","pidversion","3001","cdnBackup","1",
                    "key",md5(hash+"185672dd44712f60bb1736df5a377e82"+"3116"+mid+user));
                break;
            case "/user/cloud/url":
                path = "/bsstrackercdngz/v2/query_musicclound_url";
                String cloudHash = value(input,"hash","").toLowerCase(Locale.ROOT);
                put(query,"hash",cloudHash,"ssa_flag","is_fromtrack","version","20102","ssl","0",
                    "album_audio_id",value(input,"album_audio_id","0"),"pid","20026",
                    "audio_id",value(input,"audio_id","0"),"kv_id","2","bucket","musicclound",
                    "name",value(input,"name",""),"with_res_tag","0",
                    "key",md5("musicclound"+cloudHash+"20026"+"ebd1ac3134c880bda6a2194537843caa0162e2e7"));
                break;
            case "/search/lyric":
                origin = LYRICS; path = "/search"; salt = null; query.clear();
                put(query,"ver","1","client","pc","duration",value(input,"duration","0"),"hash",value(input,"hash",""),
                    "keyword",value(input,"keywords",""),"lrctxt","1","man",value(input,"man","no"));
                break;
            case "/lyric":
                origin = LYRICS; path = "/download"; salt = null; query.clear();
                put(query,"ver","1","client","pc","id",value(input,"id",""),"accesskey",value(input,"accesskey",""),
                    "fmt",value(input,"fmt","krc"),"charset","utf8");
                break;
            default:
                throw new IOException("机顶盒直连暂不支持此操作");
        }
        return call(origin,path,router,query,salt,post,scope);
    }

    private AuthRequestRunner.Response<AuthStore.Scope> call(String origin,String path,String router,
            Map<String,String> query,String salt,JSONObject post,AuthStore.Scope scope) throws Exception {
        String payload = post == null ? "" : post.toString();
        if (salt != null) {
            StringBuilder signed = new StringBuilder(salt);
            for (Map.Entry<String,String> item : query.entrySet()) {
                signed.append(item.getKey()).append('=').append(item.getValue());
            }
            query.put("signature",md5(signed.append(payload).append(salt).toString()));
        }
        Uri.Builder url = Uri.parse(origin+path).buildUpon();
        for (Map.Entry<String,String> item : query.entrySet()) url.appendQueryParameter(item.getKey(),item.getValue());
        HttpsURLConnection connection = (HttpsURLConnection)new URL(url.build().toString()).openConnection();
        connection.setSSLSocketFactory(Tls12.FACTORY);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept","application/json");
        connection.setRequestProperty("User-Agent","Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
        connection.setRequestProperty("dfid","-");
        connection.setRequestProperty("mid",mid);
        connection.setRequestProperty("clienttime",value(query,"clienttime",String.valueOf(System.currentTimeMillis()/1000)));
        if (!router.isEmpty()) connection.setRequestProperty("x-router",router);
        try {
            if (post != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type","application/json;charset=utf-8");
                byte[] data = payload.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(data); }
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) throw new IOException("酷狗接口未返回数据（HTTP "+code+"）");
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = in.read(buffer)) != -1;) {
                    out.write(buffer,0,count);
                    if (out.size() > 4*1024*1024) throw new IOException("接口响应过大");
                }
                String response = new String(out.toByteArray(),StandardCharsets.UTF_8).trim();
                if (response.isEmpty()) throw new IOException("酷狗接口返回空内容（HTTP "+code+"）");
                JSONObject body = new JSONObject(response);
                return new AuthRequestRunner.Response<>(code,body,AuthProtocol.event(body,connection.getHeaderField("ssa-code")),scope);
            }
        } finally { connection.disconnect(); }
    }

    private static void put(Map<String,String> map,String... entries) {
        for (int i=0;i<entries.length;i+=2) map.put(entries[i],entries[i+1]);
    }
    private static String value(Map<String,String> map,String key,String fallback) {
        String value=map.get(key);
        return value==null||value.isEmpty()?fallback:value;
    }
    private static long identifier(String text) {
        try { return Long.parseLong(text); }
        catch (NumberFormatException ignored) { return 0; }
    }
    private static int number(Map<String,String> map,String key,int fallback) {
        try { return Integer.parseInt(value(map,key,String.valueOf(fallback))); }
        catch (NumberFormatException ignored) { return fallback; }
    }
    private static String md5(String text) throws Exception {
        byte[] digest=MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder result=new StringBuilder(32);
        for (byte b:digest) result.append(String.format(Locale.ROOT,"%02x",b&255));
        return result.toString();
    }

    /** Enable TLS 1.2 on KitKat while retaining normal certificate and hostname checks. */
    static final class Tls12 extends SSLSocketFactory {
        static final SSLSocketFactory FACTORY=new Tls12();
        private final SSLSocketFactory delegate=HttpsURLConnection.getDefaultSSLSocketFactory();
        private Socket configure(Socket socket) {
            if (socket instanceof SSLSocket) {
                SSLSocket tls=(SSLSocket)socket;
                if (Arrays.asList(tls.getSupportedProtocols()).contains("TLSv1.2")) {
                    tls.setEnabledProtocols(new String[]{"TLSv1.2"});
                }
            }
            return socket;
        }
        public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
        public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
        public Socket createSocket() throws IOException{return configure(delegate.createSocket());}
        public Socket createSocket(Socket s,String h,int p,boolean close)throws IOException{return configure(delegate.createSocket(s,h,p,close));}
        public Socket createSocket(String h,int p)throws IOException{return configure(delegate.createSocket(h,p));}
        public Socket createSocket(String h,int p,InetAddress l,int lp)throws IOException{return configure(delegate.createSocket(h,p,l,lp));}
        public Socket createSocket(InetAddress h,int p)throws IOException{return configure(delegate.createSocket(h,p));}
        public Socket createSocket(InetAddress h,int p,InetAddress l,int lp)throws IOException{return configure(delegate.createSocket(h,p,l,lp));}
    }
}
