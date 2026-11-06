package cc.echomusic.android;

import org.json.*;
import java.util.*;

/** Normalizes the desktop API's business errors without losing challenge/account-selection payloads. */
public final class AuthProtocol {
    public static String text(JSONObject o,String... keys){if(o==null)return "";for(String key:keys){Object v=o.opt(key);if(v!=null&&v!=JSONObject.NULL&&!(v instanceof JSONObject)&&!(v instanceof JSONArray)){String s=String.valueOf(v).trim();if(!s.isEmpty())return s;}}return "";}
    public static int errorCode(JSONObject o){for(String key:new String[]{"error_code","err_code","errcode"})if(o.has(key))return o.optInt(key,0);return 0;}
    public static String event(JSONObject o,String header){String id=text(o,"ssaCode");if(id.isEmpty())id=header==null?"":header.trim();if(id.isEmpty())id=text(o.optJSONObject("data"),"event_id");return id;}
    /** Lyrics service uses 200 as its own success code, unlike account APIs. */
    public static JSONObject normalize(String path,JSONObject body)throws JSONException{
        boolean lyric=path.equals("/lyric")||path.equals("/search/lyric");
        boolean payload=path.equals("/lyric")?(!text(body,"content","decodeContent","lyric").isEmpty()):body.optJSONArray("candidates")!=null;
        if(lyric&&payload&&errorCode(body)==200&&!"0".equals(text(body,"status"))){
            JSONObject copy=new JSONObject(body.toString());
            for(String key:new String[]{"error_code","err_code","errcode"})if(copy.optInt(key,0)==200)copy.put(key,0);
            return copy;
        }return body;
    }
    public static boolean failed(JSONObject o){return errorCode(o)!=0 || (o.has("status")&&"0".equals(text(o,"status")));}
    public static String message(JSONObject o,int http){String m=text(o,"msg","error","message");if(m.isEmpty())m="服务请求失败（HTTP "+http+"，代码 "+errorCode(o)+"）";return m;}
    public static boolean positiveId(String value){return value.matches("[1-9][0-9]{0,19}");}
    public static void validateCookieValue(String value){if(value.indexOf(';')>=0||value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException("账号凭证格式无效");}
    public static void validateHeader(String value){if(value.indexOf('\r')>=0||value.indexOf('\n')>=0)throw new IllegalArgumentException("账号凭证不能包含换行");}
    public static JSONArray accounts(JSONObject body){
        JSONArray valid=new JSONArray();if(errorCode(body)!=34175)return valid;
        JSONObject data=body.optJSONObject("data");JSONArray list=data==null?null:data.optJSONArray("info_list");
        if(list!=null)for(int i=0;i<list.length();i++){JSONObject account=list.optJSONObject(i);if(account!=null&&positiveId(text(account,"userid")))valid.put(account);}return valid;
    }
    public static JSONObject loginData(JSONObject body){JSONObject data=body.optJSONObject("data");if(data==null){JSONObject nested=body.optJSONObject("body");data=nested==null?body:nested.optJSONObject("data");}return data==null?new JSONObject():data;}
    public static String qrImage(JSONObject body){JSONObject data=body.optJSONObject("data");String image=text(data,"qrcode_img","base64");if(image.isEmpty())image=text(body,"qrcode_img","base64","qrcode");return image;}
    public static int kugouQrStatus(JSONObject body){JSONObject data=body.optJSONObject("data");return data==null?body.optInt("status",-1):data.optInt("status",body.optInt("status",-1));}
    public static String provider(JSONObject info){
        switch(info.optInt("v_type",23)){case 32:return "SMS";case 34:return "REAL_NAME";case 36:return "BIND_PHONE";case 38:return "LOGIN";case 51:return "ACCOUNT_RISK";}
        String url=text(info,"url").toUpperCase(Locale.ROOT);
        if(url.startsWith("KGCODE")){int end=url.indexOf('|');if(end>6)return url.substring(6,end);}
        return text(info,"txappid").isEmpty()?"UNKNOWN":"TX";
    }
    public static String tencentCode(String appid,JSONObject result) throws Exception {
        if(result.optInt("ret",-1)!=0)throw new Exception("已取消图形验证");
        String ticket=text(result,"ticket"),randstr=text(result,"randstr");if(ticket.isEmpty()||randstr.isEmpty()||appid.isEmpty())throw new Exception("验证码结果不完整，请重新验证");
        JSONObject code=new JSONObject();code.put("ticket",ticket);code.put("randstr",randstr);code.put("txappid",appid);return "KGCodeTX|"+code;
    }
    public static final class ApiException extends java.io.IOException {
        public final JSONObject body;public final int status;public final String eventId;
        ApiException(JSONObject body,int status,String event){super(message(body,status));this.body=body;this.status=status;this.eventId=event;}
    }
}
