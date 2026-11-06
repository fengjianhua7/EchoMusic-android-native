package cc.echomusic.android;

import org.json.*;
import java.util.concurrent.atomic.*;
import java.io.IOException;

public final class AuthContractTest {
    private static int checks=0;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Throwing{void run()throws Exception;}
    private static void rejects(Throwing action,String message)throws Exception{boolean thrown=false;try{action.run();}catch(Exception expected){thrown=true;}check(thrown,message);}
    private static JSONObject json(String source)throws Exception{return new JSONObject(source);}
    private static AuthRequestRunner.Response<String> response(int http,String body,String event,String scope)throws Exception{return new AuthRequestRunner.Response<>(http,json(body),event,scope);}
    public static void main(String[] args)throws Exception{
        check(AuthProtocol.errorCode(json("{err_code:30791}"))==30791,"alternate error code");
        check(AuthProtocol.failed(json("{status:0}")),"failed body under HTTP 200");
        check(AuthProtocol.failed(json("{status:1,error_code:20028}")),"challenge error despite status 1");
        check(!AuthProtocol.failed(json("{status:1}")),"successful body");
        check(AuthProtocol.event(json("{ssaCode:'event-a'}"),"header-b").equals("event-a"),"event body priority");
        check(AuthProtocol.event(json("{}")," event-b ").equals("event-b"),"event from header");
        check(AuthProtocol.event(json("{data:{event_id:'event-c'}}"),"").equals("event-c"),"nested event");
        check(AuthProtocol.event(json("{}"),null).isEmpty(),"no manufactured event");
        JSONArray accounts=AuthProtocol.accounts(json("{error_code:34175,data:{info_list:[{userid:123,nickname:'A'},{userid:'456'},null,{userid:0},{userid:'bad'},{userid:-7}]}}"));
        check(accounts.length()==2,"filter invalid account candidates without dropping valid accounts");
        check(accounts.getJSONObject(0).optString("userid").equals("123"),"numeric account ID");
        check(AuthProtocol.accounts(json("{error_code:0,data:{info_list:[{userid:123}]}}" )).length()==0,"only explicit multi-account error creates choice");
        check(AuthProtocol.qrImage(json("{status:1,data:{qrcode_img:'native-image'}}")).equals("native-image"),"desktop QR image shape");
        check(AuthProtocol.qrImage(json("{code:200,data:{base64:'api-image'}}")).equals("api-image"),"bundled API QR base64 shape");
        check(AuthProtocol.kugouQrStatus(json("{status:1,data:{status:2}}"))==2,"scanned status overrides wrapper");
        check(AuthProtocol.kugouQrStatus(json("{status:1,data:{status:0}}"))==0,"expiry overrides wrapper");
        check(AuthProtocol.kugouQrStatus(json("{status:1,data:{status:4,token:'t'}}"))==4,"authorized QR state");
        check(AuthProtocol.provider(json("{v_type:32,txappid:'123'}")).equals("SMS"),"SMS priority over captcha app ID");
        check(AuthProtocol.provider(json("{v_type:38}")).equals("LOGIN"),"login confirmation");
        check(AuthProtocol.provider(json("{v_type:34}")).equals("REAL_NAME"),"real-name guidance");
        check(AuthProtocol.provider(json("{v_type:36}")).equals("BIND_PHONE"),"bind-phone guidance");
        check(AuthProtocol.provider(json("{v_type:51}")).equals("ACCOUNT_RISK"),"risk appeal guidance");
        check(AuthProtocol.provider(json("{url:'KGCodeTX|{}'}")).equals("TX"),"Tencent tagged provider");
        check(AuthProtocol.provider(json("{url:'KGCodeGT|{}',txappid:'123'}")).equals("GT"),"unsupported provider is not disguised as Tencent");
        check(AuthProtocol.provider(json("{txappid:'123'}")).equals("TX"),"Tencent fallback app ID");
        check(AuthProtocol.provider(json("{}")).equals("UNKNOWN"),"unknown provider has no fabricated result");
        String ticket=AuthProtocol.tencentCode("123",json("{ret:0,ticket:'real-ticket',randstr:'real-random'}"));
        JSONObject ticketData=new JSONObject(ticket.substring(ticket.indexOf('|')+1));
        check(ticket.startsWith("KGCodeTX|")&&ticketData.getString("ticket").equals("real-ticket")&&ticketData.getString("txappid").equals("123"),"platform ticket payload");
        rejects(()->AuthProtocol.tencentCode("123",json("{ret:2}")),"cancelled captcha cannot become success");
        rejects(()->AuthProtocol.tencentCode("123",json("{ret:0,ticket:'t'}")),"incomplete captcha cannot become success");
        rejects(()->AuthProtocol.validateCookieValue("safe;userid=other"),"cookie field injection blocked");
        rejects(()->AuthProtocol.validateHeader("token=x\r\nX-Evil:y"),"header injection blocked");
        check(AuthProtocol.loginData(json("{body:{data:{token:'t'}}}")).optString("token").equals("t"),"nested login payload");
        AtomicInteger calls=new AtomicInteger(),verifications=new AtomicInteger();
        AuthRequestRunner<String> retry=new AuthRequestRunner<>(s->s.equals("session"),(s,skip)->{
            check(skip,"login retry excludes previous user auth");int n=calls.incrementAndGet();return n==1?response(502,"{status:0,error_code:20028}","event",s):response(200,"{status:1}","",s);
        },(event,s,skip)->{verifications.incrementAndGet();check(event.equals("event"),"real event retained");return s;});
        check(retry.run("session",true,false,true).body.optInt("status")==1,"one challenge then success");
        check(calls.get()==2&&verifications.get()==1,"exactly one verification and one retry");
        calls.set(0);verifications.set(0);
        AuthRequestRunner<String> repeated=new AuthRequestRunner<>(s->true,(s,skip)->{calls.incrementAndGet();return response(502,"{status:0,error_code:20028}","event",s);},(event,s,skip)->{verifications.incrementAndGet();return s;});
        rejects(()->repeated.run("s",false,false,true),"repeated challenge terminates");
        check(calls.get()==2&&verifications.get()==1,"no verification/retry loop");
        calls.set(0);
        AuthRequestRunner<String> cancelled=new AuthRequestRunner<>(s->true,(s,skip)->{calls.incrementAndGet();return response(502,"{status:0,error_code:20028}","event",s);},(event,s,skip)->{throw new IOException("cancelled");});
        rejects(()->cancelled.run("s",false,false,true),"cancel propagates to caller");check(calls.get()==1,"cancel never replays side effects");
        calls.set(0);verifications.set(0);
        AuthRequestRunner<String> stale=new AuthRequestRunner<>(s->false,(s,skip)->{calls.incrementAndGet();return response(200,"{status:1}","",s);},(event,s,skip)->{verifications.incrementAndGet();return s;});
        rejects(()->stale.run("old",false,false,true),"stale account fails before transport");check(calls.get()==0,"stale credentials are never sent");
        AtomicReference<String> session=new AtomicReference<>("old");calls.set(0);
        AuthRequestRunner<String> changedDuringFetch=new AuthRequestRunner<>(s->session.get().equals(s),(s,skip)->{calls.incrementAndGet();session.set("new");return response(200,"{status:1}","",s);},(event,s,skip)->s);
        rejects(()->changedDuringFetch.run("old",false,false,true),"late response is rejected after switching accounts");
        session.set("old");calls.set(0);
        AuthRequestRunner<String> loginConfirmation=new AuthRequestRunner<>(s->session.get().equals(s),(s,skip)->{int n=calls.incrementAndGet();return n==1?response(502,"{status:0,error_code:20028}","event",s):response(200,"{status:1}","",s);},(event,s,skip)->{session.set("new");return "new";});
        check(loginConfirmation.run("old",false,false,true).scope.equals("new"),"explicit login confirmation retries with the newly installed scope");
        check(calls.get()==2,"login confirmation makes exactly one retry");
        AuthRequestRunner<String> multi=new AuthRequestRunner<>(s->true,(s,skip)->response(502,"{status:0,error_code:34175,data:{info_list:[{userid:123}]}}","",s),(event,s,skip)->s);
        check(AuthProtocol.accounts(multi.run("s",true,true,true).body).length()==1,"HTTP 502 multi-account payload reaches selector");
        rejects(()->multi.run("s",false,false,true),"business errors are not suppressed for normal requests");
        calls.set(0);verifications.set(0);
        AuthRequestRunner<String> noRecursiveVerify=new AuthRequestRunner<>(s->true,(s,skip)->{calls.incrementAndGet();return response(502,"{status:0,error_code:20028}","event",s);},(event,s,skip)->{verifications.incrementAndGet();return s;});
        rejects(()->noRecursiveVerify.run("s",false,false,false),"verification endpoints do not recurse");check(calls.get()==1&&verifications.get()==0,"no UI recursion");
        AuthRequestRunner<String> ordinaryError=new AuthRequestRunner<>(s->true,(s,skip)->response(502,"{status:0,error_code:20018,msg:'session expired'}","",s),(event,s,skip)->s);
        boolean preserved=false;try{ordinaryError.run("s",false,false,true);}catch(AuthProtocol.ApiException e){preserved=e.status==502&&AuthProtocol.errorCode(e.body)==20018&&e.getMessage().equals("session expired");}check(preserved,"business body and HTTP status survive failure");
        session.set("old");calls.set(0);
        AuthRequestRunner<String> boundCloud=new AuthRequestRunner<>(s->session.get().equals(s),(s,skip)->{calls.incrementAndGet();return response(502,"{status:0,error_code:20028}","event",s);},(event,s,skip)->{session.set("new");return "new";});
        rejects(()->boundCloud.run("old",false,false,true,false),"cloud write cannot resume in a different account after login confirmation");check(calls.get()==1,"strict cloud request is never replayed into the new account");
        JSONObject lyricOk=new JSONObject("{status:200,error_code:200,content:'abc'}");
        check(!AuthProtocol.failed(AuthProtocol.normalize("/lyric",lyricOk)),"lyric 200 is a success with content");
        check(AuthProtocol.failed(AuthProtocol.normalize("/user/playlist",lyricOk)),"account error 200 is not suppressed");
        check(AuthProtocol.failed(AuthProtocol.normalize("/lyric",new JSONObject("{status:0,error_code:200,content:'abc'}"))),"lyric failure status remains failure");
        check(AuthProtocol.failed(AuthProtocol.normalize("/lyric",new JSONObject("{error_code:200}"))),"empty lyric error remains failure");
        System.out.println("PASS: "+checks+" authentication and verification contract checks");
    }
}
