package cc.echomusic.android;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.*;

/** Presents platform challenges and submits only results of a real user's verification. */
public final class VerificationDialog implements VerificationBroker.Host {
    private static final String CAPTCHA_ORIGIN="https://appassets.androidplatform.net/captcha/index.html";
    private final MainActivity activity;
    private final LoginDialog login;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private VerificationBroker.Challenge current;
    private Dialog dialog,captchaDialog;
    private WebView web;
    private TextView status;
    private LinearLayout content;
    private JSONObject info;
    private String smsDraft="",error="";
    private int generation=0,webGeneration=0;
    private boolean busy=false,closed=false;
    public VerificationDialog(MainActivity activity,LoginDialog login){this.activity=activity;this.login=login;}
    @Override public void present(VerificationBroker.Challenge challenge){
        if(closed){challenge.cancel("验证页面已关闭，请重试");return;}
        current=challenge;info=null;smsDraft="";error="正在获取安全验证信息…";busy=true;
        dialog=new Dialog(activity);dialog.setCanceledOnTouchOutside(false);dialog.setOnCancelListener(d->challenge.cancel("已取消安全验证；输入内容已保留"));render();dialog.show();if(BuildConfig.TV)TvFocus.install(dialog.getWindow().getDecorView());resize();loadInfo();
    }
    @Override public void dismiss(VerificationBroker.Challenge challenge){
        if(current!=challenge)return;++generation;destroyCaptcha();if(dialog!=null){dialog.dismiss();dialog=null;}current=null;
    }
    public void pause(){if(current!=null)dismiss(current);}
    public void close(){closed=true;++generation;destroyCaptcha();if(current!=null)current.cancel("安全验证已关闭；输入内容已保留");if(dialog!=null)dialog.dismiss();dialog=null;current=null;workers.shutdownNow();main.removeCallbacksAndMessages(null);}
    public void resize(){
        int width=activity.getResources().getConfiguration().screenWidthDp;
        if(dialog!=null&&dialog.isShowing())dialog.getWindow().setLayout(activity.dp(Math.min(560,width-24)),-2);
        if(captchaDialog!=null&&captchaDialog.isShowing())captchaDialog.getWindow().setLayout(activity.dp(Math.min(700,width-16)),Math.round(activity.getResources().getDisplayMetrics().heightPixels*.9f));
    }
    private boolean active(VerificationBroker.Challenge challenge,int serial){return !closed&&current==challenge&&generation==serial&&challenge.active();}
    private void render(){
        if(dialog==null||current==null)return;
        ScrollView scroll=new ScrollView(activity);LinearLayout panel=activity.col();activity.pad(panel,22);panel.setBackgroundColor(0xffa5f2f3);scroll.addView(panel);
        panel.addView(activity.title("安全验证",24));activity.gap(panel,12);status=activity.text(error,14,0xff07515d);panel.addView(status);activity.gap(panel,16);content=activity.col();panel.addView(content);
        if(info!=null){
            String provider=AuthProtocol.provider(info);String description=AuthProtocol.text(info.optJSONObject("show"),"msg");
            if(provider.equals("TX")){
                content.addView(activity.text("请手动完成腾讯图形验证码，验证通过后将继续刚才的操作。",15,0xff102e35));activity.gap(content,16);
                TextView start=activity.button("打开图形验证",this::startTencent);start.setEnabled(!busy);content.addView(start,activity.lp(-1,50));
            }else if(provider.equals("SMS")){
                content.addView(activity.text(description.isEmpty()?"请输入酷狗为本次安全验证下发的短信验证码。":description,15,0xff102e35));
                EditText code=new EditText(activity);code.setSingleLine();code.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);code.setHint("安全验证码");code.setText(smsDraft);code.setEnabled(!busy);code.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){smsDraft=s.toString().trim();}public void afterTextChanged(android.text.Editable e){}});content.addView(code,activity.lp(-1,56));
                TextView submit=activity.button(busy?"验证中…":"提交验证码",()->{if(!smsDraft.matches("[0-9]{4,8}")){setError("请输入短信中的安全验证码");return;}submit(smsDraft);});submit.setEnabled(!busy);content.addView(submit,activity.lp(-1,50));
            }else if(provider.equals("LOGIN")){
                content.addView(activity.text(description.isEmpty()?"平台要求重新登录以确认身份。完成登录后将继续原操作。":description,15,0xff102e35));activity.gap(content,16);content.addView(activity.button("前往登录确认",this::confirmLogin),activity.lp(-1,50));
            }else{
                String message;
                switch(provider){case "BIND_PHONE":message="当前账号需要绑定手机号。请前往酷狗客户端完成绑定，再返回重试。";break;case "REAL_NAME":message="当前账号需要实名认证。请前往酷狗客户端完成认证，再返回重试。";break;case "ACCOUNT_RISK":message="当前账号被平台风控限制。请在酷狗客户端完成申诉后重试。";break;default:message="平台要求 "+provider+" 验证，当前未提供可用的安卓验证码配置。请在酷狗客户端完成验证后返回重试。";}
                content.addView(activity.text(message,15,0xff102e35));activity.gap(content,12);content.addView(activity.button("打开酷狗客户端",this::openKugou),activity.lp(-1,48));
            }
        }
        activity.gap(panel,18);TextView refresh=activity.button("重新获取验证信息",this::loadInfo);refresh.setEnabled(!busy);panel.addView(refresh,activity.lp(-1,48));activity.gap(panel,10);
        panel.addView(activity.button("返回编辑（保留输入）",()->{if(current!=null)current.cancel("已取消安全验证；输入内容已保留");}),activity.lp(-1,48));dialog.setContentView(scroll);resize();
    }
    private void setError(String message){error=message==null?"安全验证失败，请重试":message;if(status!=null)status.setText(error);}
    private void loadInfo(){
        if(current==null||!current.active())return;
        VerificationBroker.Challenge challenge=current;int serial=++generation;busy=true;error="正在获取安全验证信息…";render();
        workers.execute(()->{try{
            JSONObject response=challenge.repository.verificationGet(challenge,"/get/verify/info",MusicRepository.params("eventid",challenge.eventId));
            if(response.optInt("status",0)!=1||AuthProtocol.errorCode(response)!=0)throw new AuthProtocol.ApiException(response,200,"");JSONObject data=response.optJSONObject("data");JSONObject loaded=data==null?response:data;
            main.post(()->{if(!active(challenge,serial))return;info=loaded;busy=false;error="请完成本次安全验证";render();});
        }catch(Exception e){main.post(()->{if(!active(challenge,serial))return;busy=false;info=null;error=e instanceof AuthProtocol.ApiException&&AuthProtocol.errorCode(((AuthProtocol.ApiException)e).body)==36001?"平台未能提供验证信息（36001）。可重新获取，或返回编辑稍后重试，输入内容会保留。":e.getMessage();render();});}});
    }
    private void submit(String code){
        if(busy||current==null||info==null||!current.active())return;
        String provider=AuthProtocol.provider(info);if(!provider.equals("TX")&&!provider.equals("SMS")){setError("当前验证类型不能提交验证码");return;}
        VerificationBroker.Challenge challenge=current;int serial=++generation;int type=info.optInt("v_type",23);busy=true;error="正在校验，请稍候…";render();
        workers.execute(()->{try{
            JSONObject response=challenge.repository.verificationGet(challenge,"/sidedt",MusicRepository.params("eventid",challenge.eventId,"v_type",String.valueOf(type),"verifycode",code));
            if(response.optInt("status",0)!=1||AuthProtocol.errorCode(response)!=0)throw new AuthProtocol.ApiException(response,200,"");
            main.post(()->{if(active(challenge,serial))challenge.succeed(challenge.scope);});
        }catch(Exception e){main.post(()->{if(!active(challenge,serial))return;busy=false;error="验证未通过："+e.getMessage()+"。请重新完成验证。";render();});}});
    }
    private void confirmLogin(){
        VerificationBroker.Challenge challenge=current;if(challenge==null)return;dialog.hide();
        login.open(installed->{if(current==challenge&&!challenge.result.isDone())challenge.succeed(installed);},()->{if(current==challenge&&!challenge.result.isDone()){dialog.show();if(BuildConfig.TV)TvFocus.install(dialog.getWindow().getDecorView());setError("登录确认已取消；可重新登录或返回编辑");resize();}});
    }
    private void openKugou(){Intent launch=activity.getPackageManager().getLaunchIntentForPackage("com.kugou.android");if(launch==null){activity.toast("请先安装酷狗客户端");return;}activity.startActivity(launch);}
    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    @android.annotation.TargetApi(21)
    private void startTencent(){
        if(android.os.Build.VERSION.SDK_INT<21){setError("此机顶盒请优先使用扫码登录");return;}
        if(current==null||info==null||busy||!current.active())return;
        String appid=AuthProtocol.text(info,"txappid");if(!appid.matches("[0-9]{1,32}")){setError("平台未提供有效的腾讯验证码配置，请重新获取验证信息");return;}
        destroyCaptcha();final int serial=webGeneration;final String nonce=UUID.randomUUID().toString();final VerificationBroker.Challenge challenge=current;
        captchaDialog=new Dialog(activity);LinearLayout panel=activity.col();activity.pad(panel,12);panel.setBackgroundColor(0xffa5f2f3);panel.addView(activity.title("完成图形验证",22));activity.gap(panel,8);
        WebView view=new WebView(activity);web=view;WebSettings settings=view.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setSupportMultipleWindows(false);settings.setSaveFormData(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(view,true);
        view.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest request){return request.isForMainFrame() || !"https".equals(request.getUrl().getScheme());}
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest request){String scheme=request.getUrl().getScheme();if(!"https".equals(scheme))return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));return null;}
            @Override public void onReceivedSslError(WebView v,android.webkit.SslErrorHandler handler,android.net.http.SslError error){handler.cancel();main.post(()->{if(serial==webGeneration)setError("验证码连接证书无效，请检查网络后重试");});}
        });
        // The bridge exposes no account/file/navigation operations. A per-view nonce binds callbacks to our wrapper.
        view.addJavascriptInterface(new Object(){
            @JavascriptInterface public void result(String receivedNonce,String payload){
                if(!nonce.equals(receivedNonce)||payload==null||payload.length()>16384)return;
                main.post(()->{if(serial!=webGeneration||current!=challenge||!challenge.active())return;try{String code=AuthProtocol.tencentCode(appid,new JSONObject(payload));destroyCaptcha();submit(code);}catch(Exception e){destroyCaptcha();setError(e.getMessage());}});
            }
            @JavascriptInterface public void failure(String receivedNonce,String ignored){if(!nonce.equals(receivedNonce))return;main.post(()->{if(serial==webGeneration&&current==challenge){destroyCaptcha();setError("腾讯验证码加载失败或超时，请检查网络后重新打开");}});}
        },"EchoCaptcha");
        panel.addView(view,new LinearLayout.LayoutParams(-1,0,1));activity.gap(panel,8);panel.addView(activity.button("返回安全验证",this::destroyCaptcha),activity.lp(-1,48));captchaDialog.setContentView(panel);captchaDialog.setOnDismissListener(d->destroyCaptcha());captchaDialog.show();resize();
        String config="const appid="+JSONObject.quote(appid)+", nonce="+JSONObject.quote(nonce)+";";
        String html="<!doctype html><html lang='zh-CN'><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=1'><style>body{margin:0;background:#f6f7f3;color:#357a65;font:16px sans-serif;text-align:center}p{padding:32px 12px}button{padding:16px;border:0;border-radius:12px;background:#dce8dc;color:#202b26}</style><p id='message'>正在加载腾讯验证码…</p><button id='retry' style='display:none'>重新打开验证</button><script>"+config+
            "let ready=false,cap;const fail=()=>EchoCaptcha.failure(nonce,'load');const timer=setTimeout(()=>{if(!ready)fail()},20000);const script=document.createElement('script');script.src='https://turing.captcha.qcloud.com/TCaptcha.js';script.onerror=fail;script.onload=()=>{clearTimeout(timer);if(!window.TencentCaptcha){fail();return}ready=true;document.getElementById('message').textContent='请手动完成图形验证';try{cap=new TencentCaptcha(appid,r=>EchoCaptcha.result(nonce,JSON.stringify(r)),{showHeader:true});document.getElementById('retry').style.display='inline-block';document.getElementById('retry').onclick=()=>cap.show();cap.show()}catch(e){fail()}};document.head.appendChild(script);</script></html>";
        view.loadDataWithBaseURL(CAPTCHA_ORIGIN,html,"text/html","UTF-8",null);
    }
    private void destroyCaptcha(){
        ++webGeneration;Dialog previous=captchaDialog;captchaDialog=null;WebView previousWeb=web;web=null;
        if(previous!=null){previous.setOnDismissListener(null);previous.dismiss();}
        if(previousWeb!=null){previousWeb.removeJavascriptInterface("EchoCaptcha");previousWeb.stopLoading();previousWeb.loadUrl("about:blank");previousWeb.destroy();}
    }
}
