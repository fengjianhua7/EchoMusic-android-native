package cc.echomusic.android;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Account state is encrypted, scoped to an API origin, and guarded against late responses. */
public final class AuthStore {
    private static final Object LOCK=new Object();
    private static final String KEY="echomusic.account.v1";
    private final SharedPreferences prefs;
    public static final class Scope {
        final String endpoint, authorization;
        final long version;
        Scope(String endpoint,String authorization,long version){this.endpoint=endpoint;this.authorization=authorization;this.version=version;}
    }
    public AuthStore(Context context){prefs=context.getSharedPreferences("echo",Context.MODE_PRIVATE);}
    public static String normalize(String endpoint){return endpoint.trim().replaceAll("/+$","");}
    public Scope scope(){synchronized(LOCK){return new Scope(ApiEndpoint.selected(prefs.getString("endpoint","")),authorization(),prefs.getLong("session_version",0));}}
    public boolean current(Scope scope){synchronized(LOCK){return scope.endpoint.equals(ApiEndpoint.selected(prefs.getString("endpoint","")))&&scope.version==prefs.getLong("session_version",0);}}
    public JSONObject account(){synchronized(LOCK){
        String base=ApiEndpoint.selected(prefs.getString("endpoint",""));
        String encoded=prefs.getString("account_"+base,"");
        try{if(!encoded.isEmpty())return new JSONObject(decrypt(encoded));}catch(Exception ignored){/* A missing/invalidated device key requires signing in again. */}
        return new JSONObject();
    }}
    public String authorization(){
        JSONObject account=account();String value=account.optString("authorization","");
        // Migrate the first build's manual header on first explicit configuration save or sign-in.
        return value.isEmpty()?prefs.getString("authorization",""):value;
    }
    public String displayName(){JSONObject account=account();return account.optString("nickname",authorization().isEmpty()?"尚未登录":"已配置账号凭证");}
    public void configure(String endpoint,String header) throws Exception {synchronized(LOCK){
        String old=ApiEndpoint.selected(prefs.getString("endpoint","")),next=ApiEndpoint.selected(endpoint);
        // Never silently carry the old account to a different service.
        if(!old.equals(next))header="";
        AuthProtocol.validateHeader(header);
        if(old.equals(next)&&header.equals(authorization())&&!prefs.contains("authorization"))return;
        JSONObject account=new JSONObject();account.put("authorization",header);
        if(!header.isEmpty())account.put("nickname","已配置账号凭证");
        String encrypted=encrypt(account.toString());
        prefs.edit().putString("endpoint",next).putString("account_"+next,encrypted).remove("authorization").putLong("session_version",prefs.getLong("session_version",0)+1).apply();
    }}
    public boolean install(Scope expected,JSONObject data) throws Exception {synchronized(LOCK){
        if(!current(expected))return false;
        String token=AuthProtocol.text(data,"token"),user=AuthProtocol.text(data,"userid");
        if(token.isEmpty()||!AuthProtocol.positiveId(user))throw new Exception("登录响应缺少有效账号凭证，请重新登录");
        StringBuilder header=new StringBuilder();
        for(String key:new String[]{"token","userid","t1","vip_type","vip_token"}){
            String value=AuthProtocol.text(data,key);if(value.isEmpty())continue;AuthProtocol.validateCookieValue(value);
            if(header.length()>0)header.append(';');header.append(key).append('=').append(value);
        }
        JSONObject account=new JSONObject();account.put("authorization",header.toString());account.put("userid",user);
        String nickname=AuthProtocol.text(data,"nickname","username","nick_name");account.put("nickname",nickname.isEmpty()?"酷狗用户 "+user:nickname);
        String encrypted=encrypt(account.toString());
        prefs.edit().putString("account_"+expected.endpoint,encrypted).remove("authorization").putLong("session_version",expected.version+1).apply();return true;
    }}
    public void logout(){synchronized(LOCK){String base=ApiEndpoint.selected(prefs.getString("endpoint",""));prefs.edit().remove("account_"+base).remove("authorization").putLong("session_version",prefs.getLong("session_version",0)+1).apply();}VerificationBroker.instance().cancelAll("账号已退出，请重新操作");}
    private static javax.crypto.SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(!store.containsAlias(KEY)){
            KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());generator.generateKey();
        }
        return (javax.crypto.SecretKey)store.getKey(KEY,null);
    }
    private static String encrypt(String text) throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        return Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)),Base64.NO_WRAP);
    }
    private static String decrypt(String encoded) throws Exception {
        String[] parts=encoded.split(":",2);if(parts.length!=2)throw new Exception("Invalid account state");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
