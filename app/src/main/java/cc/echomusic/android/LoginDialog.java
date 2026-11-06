package cc.echomusic.android;

import android.app.*;
import android.graphics.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Native SMS/account-choice and KuGou/QQ/WeChat QR sign-in with cancellable polling. */
public final class LoginDialog {
    private final MainActivity activity;
    private final MusicRepository repository;
    private final ExecutorService workers=Executors.newCachedThreadPool();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final List<Consumer<AuthStore.Scope>> successCallbacks=new ArrayList<>();
    private final List<Runnable> cancelCallbacks=new ArrayList<>();
    private final Map<String,Long> cooldowns=new HashMap<>();
    private final Set<String> pendingSmsPhones=new HashSet<>();
    private Dialog dialog;
    private LinearLayout body;
    private TextView message,sendButton;
    private EditText phoneField,codeField;
    private ImageView qrView;
    private AuthStore.Scope scope;
    private String mode="kugou",mobile="",code="",status="";
    private int attempt=0,qrVersion=0;
    private boolean foreground=true,closed=false,smsBusy=false,qrLoading=false,done=false;
    private QrSession qr;
    private JSONArray accountCandidates=new JSONArray();
    private String candidatesPhone="",candidatesCode="";
    private int candidatesAttempt=0;
    public LoginDialog(MainActivity activity,MusicRepository repository){this.activity=activity;this.repository=repository;}
    boolean showing(){return dialog!=null&&dialog.isShowing();}
    public void open(Consumer<AuthStore.Scope> success,Runnable cancel){
        if(success!=null)successCallbacks.add(success);if(cancel!=null)cancelCallbacks.add(cancel);
        if(showing()){++attempt;smsBusy=false;status="请完成登录以确认身份";render();return;}
        scope=repository.auth.scope();
        if(scope.endpoint.isEmpty()){finishCallbacks(false,null);activity.toast("音乐服务正在准备，请稍后重试");return;}
        done=false;status="";mode="kugou";qr=null;accountCandidates=new JSONArray();++attempt;++qrVersion;
        dialog=new Dialog(activity);dialog.setOnDismissListener(d->{++attempt;++qrVersion;qr=null;smsBusy=false;qrLoading=false;dialog=null;main.removeCallbacks(cooldownTick);if(!done)finishCallbacks(false,null);});
        render();dialog.show();if(BuildConfig.TV)TvFocus.install(dialog.getWindow().getDecorView());resize();main.post(cooldownTick);if(!mode.equals("sms"))loadQr();
    }
    private void finishCallbacks(boolean success,AuthStore.Scope installed){
        List<Consumer<AuthStore.Scope>> successes=new ArrayList<>(successCallbacks);List<Runnable> cancellations=new ArrayList<>(cancelCallbacks);successCallbacks.clear();cancelCallbacks.clear();
        if(success)for(Consumer<AuthStore.Scope> callback:successes)callback.accept(installed);else for(Runnable callback:cancellations)callback.run();
    }
    public void configurationChanged(){if(showing()){render();resize();}}
    public void resize(){if(!showing())return;int width=activity.getResources().getConfiguration().screenWidthDp;dialog.getWindow().setLayout(activity.dp(Math.min(620,width-24)),Math.round(activity.getResources().getDisplayMetrics().heightPixels*.88f));dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}
    public void foreground(boolean value){foreground=value;++qrVersion;if(value&&showing()&&!mode.equals("sms")){if(qr!=null)poll(qrVersion);else loadQr();}}
    public void close(){closed=true;++attempt;++qrVersion;if(dialog!=null)dialog.dismiss();main.removeCallbacksAndMessages(null);workers.shutdownNow();}
    private boolean valid(int serial){return !closed&&showing()&&serial==attempt&&repository.auth.current(scope);}
    private boolean activeQr(int serial){return !closed&&foreground&&showing()&&!mode.equals("sms")&&serial==qrVersion&&repository.auth.current(scope);}
    private void render(){
        if(dialog==null)return;
        LinearLayout outer=activity.col();activity.pad(outer,20);outer.setBackgroundColor(0xffa5f2f3);
        outer.addView(activity.title("登录 EchoMusic",26));activity.gap(outer,8);
        outer.addView(activity.text("短信登录，或使用另一台设备扫码确认。",14,0xff36575e));activity.gap(outer,16);
        HorizontalScrollView tabs=new HorizontalScrollView(activity);tabs.setHorizontalScrollBarEnabled(false);LinearLayout row=activity.row();tabs.addView(row);
        String[] modes={"sms","kugou","qq","wechat"},labels={"手机验证码","酷狗扫码","QQ 扫码","微信扫码"};
        for(int i=0;i<modes.length;i++){final String target=modes[i];TextView b=activity.button(labels[i],()->switchMode(target));if(mode.equals(target))b.setBackground(activity.shape(0xffd7e6d8,12));LinearLayout.LayoutParams p=activity.lp(-2,48);p.rightMargin=activity.dp(6);row.addView(b,p);}
        outer.addView(tabs,activity.lp(-1,52));activity.gap(outer,12);
        body=activity.col();outer.addView(body,activity.lp(-1,-2));
        message=activity.text(status,14,0xff07515d);body.addView(message);activity.gap(body,12);
        if(mode.equals("sms"))smsView();else qrView();
        activity.gap(outer,12);outer.addView(activity.button("关闭",()->dialog.dismiss()),activity.lp(-1,48));ScrollView page=new ScrollView(activity);page.setFillViewport(false);page.addView(outer);dialog.setContentView(page);resize();
    }
    private void switchMode(String target){
        if(mode.equals(target))return;mode=target;accountCandidates=new JSONArray();++attempt;++qrVersion;smsBusy=false;qrLoading=false;qr=null;status="";render();if(!mode.equals("sms"))loadQr();
    }
    private void smsView(){
        body.addView(activity.text("手机号",14,0xff102e35));
        phoneField=new EditText(activity);phoneField.setSingleLine();phoneField.setInputType(android.text.InputType.TYPE_CLASS_PHONE);phoneField.setHint("请输入 11 位手机号");phoneField.setText(mobile);phoneField.setEnabled(!smsBusy);body.addView(phoneField,activity.lp(-1,56));
        activity.gap(body,10);body.addView(activity.text("短信验证码",14,0xff102e35));
        LinearLayout row=activity.row();codeField=new EditText(activity);codeField.setSingleLine();codeField.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);codeField.setHint("验证码");codeField.setText(code);codeField.setEnabled(!smsBusy);row.addView(codeField,new LinearLayout.LayoutParams(0,activity.dp(56),1));sendButton=activity.button("获取验证码",this::sendSms);row.addView(sendButton,activity.lp(-2,48));body.addView(row);activity.gap(body,18);
        body.addView(activity.button(smsBusy?"处理中…":"登录",()->submitSms("")),activity.lp(-1,52));activity.gap(body,16);
        body.addView(activity.text("验证码由酷狗发送。若一个手机号对应多个账号，验证后可选择要登录的账号。",13,0xff36575e));
        watch(phoneField,value->{invalidateCandidates();mobile=value;code="";if(codeField!=null)codeField.setText("");});watch(codeField,value->{invalidateCandidates();code=value;});updateCooldown();renderCandidates();
    }
    private void watch(EditText edit,Consumer<String> update){edit.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){update.accept(s.toString().trim());}public void afterTextChanged(Editable e){}});}
    private void setStatus(String value){status=value;if(message!=null)message.setText(value);}
    private boolean checkPhone(){if(!mobile.matches("1[0-9]{10}")){setStatus("请输入正确的 11 位手机号");return false;}return true;}
    private void sendSms(){
        if(smsBusy||!checkPhone()||cooldown()>0||pendingSmsPhones.contains(mobile))return;
        accountCandidates=new JSONArray();final String phone=mobile;final int serial=++attempt;pendingSmsPhones.add(phone);smsBusy=true;cooldowns.put(phone,SystemClock.elapsedRealtime()+60000);status="正在发送验证码…";render();
        workers.execute(()->{try{JSONObject response=repository.loginGet(scope,"/captcha/sent",MusicRepository.params("mobile",phone));if(response.optInt("status",0)!=1||AuthProtocol.errorCode(response)!=0)throw new IOException(AuthProtocol.message(response,200));main.post(()->{pendingSmsPhones.remove(phone);cooldowns.put(phone,SystemClock.elapsedRealtime()+60000);updateCooldown();if(!valid(serial))return;smsBusy=false;status="验证码已发送，请查看短信";render();});}catch(Exception e){main.post(()->{pendingSmsPhones.remove(phone);updateCooldown();if(!valid(serial))return;smsBusy=false;status=e.getMessage();render();});}});
    }
    private void submitSms(String userid){
        if(smsBusy||!checkPhone())return;
        if(!code.matches("[0-9]{4,8}")){setStatus("请输入短信中的验证码");return;}
        accountCandidates=new JSONArray();final String phone=mobile,verification=code;final int serial=++attempt;
        smsBusy=true;status="正在登录…";render();
        workers.execute(()->{try{Map<String,String> params=MusicRepository.params("mobile",phone,"code",verification);if(!userid.isEmpty())params.put("userid",userid);
            JSONObject response=repository.loginGet(scope,"/login/cellphone",params);
            main.post(()->{if(!valid(serial))return;smsBusy=false;JSONArray candidates=AuthProtocol.accounts(response);if(candidates.length()>0){chooseAccount(candidates,serial,phone,verification);return;}finishLogin(response,serial);});
        }catch(Exception e){main.post(()->{if(!valid(serial))return;smsBusy=false;status=e.getMessage();render();});}});
    }
    private void invalidateCandidates(){if(accountCandidates.length()>0){++attempt;accountCandidates=new JSONArray();}}
    private void chooseAccount(JSONArray candidates,int serial,String phone,String verification){
        accountCandidates=candidates;candidatesAttempt=serial;candidatesPhone=phone;candidatesCode=verification;status="请选择要登录的账号";render();
    }
    private void renderCandidates(){
        if(accountCandidates.length()==0)return;
        final int serial=candidatesAttempt;final String phone=candidatesPhone,verification=candidatesCode;
        for(int i=0;i<accountCandidates.length();i++){
            JSONObject candidate=accountCandidates.optJSONObject(i);String id=AuthProtocol.text(candidate,"userid"),nickname=AuthProtocol.text(candidate,"nickname","username");
            TextView choice=activity.button((nickname.isEmpty()?"酷狗用户":nickname)+" · "+id,()->{if(valid(serial)&&mobile.equals(phone)&&code.equals(verification))submitSms(id);else setStatus("输入已改变，请重新登录");});body.addView(choice,activity.lp(-1,56));activity.gap(body,8);
        }
    }
    private void finishLogin(JSONObject response,int serial){
        if(!valid(serial))return;
        try{
            JSONObject data=AuthProtocol.loginData(response);
            boolean accepted=mode.equals("kugou")?AuthProtocol.kugouQrStatus(response)==4:response.optInt("status",0)==1||(mode.equals("wechat")&&response.optInt("code",0)==200);
            if(!accepted||AuthProtocol.errorCode(response)!=0||AuthProtocol.failed(response))throw new IOException(AuthProtocol.message(response,200));
            if(!repository.auth.install(scope,data))throw new IOException("登录状态已变化，请重新登录");
            done=true;++qrVersion;++attempt;AuthStore.Scope installed=repository.auth.scope();finishCallbacks(true,installed);if(dialog!=null)dialog.dismiss();activity.toast("登录成功");activity.render();
        }catch(Exception e){status=e.getMessage();smsBusy=false;render();}
    }
    private long cooldown(){return Math.max(0,(cooldowns.getOrDefault(mobile,0L)-SystemClock.elapsedRealtime()+999)/1000);}
    private void updateCooldown(){if(sendButton!=null&&mode.equals("sms")){long seconds=cooldown();boolean pending=pendingSmsPhones.contains(mobile);sendButton.setText(pending?"发送处理中…":seconds>0?seconds+" 秒后重发":"获取验证码");sendButton.setEnabled(!smsBusy&&!pending&&seconds==0);}}
    private final Runnable cooldownTick=new Runnable(){public void run(){if(showing()){updateCooldown();main.postDelayed(this,1000);}}};
    private static final class QrSession {
        final String provider,key;final Map<String,String> params;final Bitmap bitmap;final long deadline;
        QrSession(String provider,String key,Map<String,String> params,Bitmap bitmap){this.provider=provider;this.key=key;this.params=params;this.bitmap=bitmap;deadline=SystemClock.elapsedRealtime()+180000;}
    }
    private void qrView(){
        qrView=new ImageView(activity);qrView.setContentDescription("登录二维码");qrView.setScaleType(ImageView.ScaleType.FIT_CENTER);qrView.setBackgroundColor(android.graphics.Color.WHITE);qrView.setPadding(activity.dp(16),activity.dp(16),activity.dp(16),activity.dp(16));
        if(qr!=null)qrView.setImageBitmap(qr.bitmap);LinearLayout.LayoutParams size=activity.lp(-1,Math.min(Math.min(280,activity.getResources().getConfiguration().screenWidthDp-88),Math.max(120,Math.round(activity.getResources().getConfiguration().screenHeightDp*.88f)-64)));body.addView(qrView,size);activity.gap(body,12);
        body.addView(activity.text("使用"+(mode.equals("kugou")?"酷狗":mode.equals("qq")?"QQ":"微信")+"扫码，并在对应应用中确认。\n手机上也可使用系统截图后，在扫码工具中从相册识别。",14,0xff36575e));activity.gap(body,12);
        body.addView(activity.button(qrLoading?"正在生成…":"刷新二维码",()->{if(!qrLoading)loadQr();}),activity.lp(-1,48));
        if(qr!=null){activity.gap(body,8);body.addView(activity.button("保存二维码",()->activity.saveLoginQr(qr.bitmap)),activity.lp(-1,48));}
    }
    private void loadQr(){
        if(!foreground||mode.equals("sms")||!showing())return;
        int serial=++qrVersion;qrLoading=true;qr=null;String provider=mode;status=ApiEndpoint.embedded(scope.endpoint)?"正在准备登录二维码…":"正在生成二维码…";render();
        workers.execute(()->{try{QrSession created=createQr(provider);main.post(()->{if(!activeQr(serial))return;qrLoading=false;qr=created;status="等待扫码";render();poll(serial);});}catch(Exception e){main.post(()->{if(!activeQr(serial))return;qrLoading=false;status=e.getMessage();render();});}});
    }
    private QrSession createQr(String provider) throws Exception {
        String key,image;Map<String,String> params=new HashMap<>();
        if(provider.equals("kugou")){
            JSONObject response=repository.loginGet(scope,"/login/qr/key",MusicRepository.params());JSONObject data=response.optJSONObject("data");key=AuthProtocol.text(data,"qrcode","key");image=AuthProtocol.text(data,"qrcode_img");
            if(key.isEmpty())throw new IOException("二维码响应缺少 key");
            if(image.isEmpty())image=AuthProtocol.qrImage(repository.loginGet(scope,"/login/qr/create",MusicRepository.params("key",key,"qrimg","true")));
        }else if(provider.equals("qq")){
            JSONObject response=repository.loginGet(scope,"/login/qq/qr/create",MusicRepository.params());key=AuthProtocol.text(response,"qrsig");image=AuthProtocol.text(response,"qrcode");
            for(String field:new String[]{"cookie","qrsig","ptqrtoken","pt_login_sig","pt_openlogin_data","xlogin_url"}){String value=AuthProtocol.text(response,field);if(value.isEmpty()&&!field.equals("pt_login_sig"))throw new IOException("QQ 二维码会话不完整");params.put(field,value);}
        }else{
            JSONObject response=repository.loginGet(scope,"/login/wx/create",MusicRepository.params());key=AuthProtocol.text(response,"uuid");JSONObject qrcode=response.optJSONObject("qrcode");image=AuthProtocol.text(qrcode,"qrcodebase64");
            if(image.isEmpty())throw new IOException("微信接口未返回二维码图片，请刷新重试");
        }
        if(key.isEmpty()||image.isEmpty())throw new IOException("二维码响应不完整，请重试");
        return new QrSession(provider,key,params,decodeImage(image));
    }
    private static Bitmap decodeImage(String image) throws Exception {
        byte[] bytes;
        if(image.startsWith("https://")){
            URL url=new URL(image);String host=url.getHost().toLowerCase(Locale.ROOT);
            if(!(host.equals("kugou.com")||host.endsWith(".kugou.com")||host.endsWith(".qq.com")||host.endsWith(".weixin.qq.com")))throw new IOException("二维码图片地址不受支持");
            HttpURLConnection connection=(HttpURLConnection)url.openConnection();connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setInstanceFollowRedirects(false);
            try{if(connection.getResponseCode()!=200)throw new IOException("二维码图片加载失败");try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){output.write(buffer,0,n);if(output.size()>2*1024*1024)throw new IOException("二维码图片过大");}bytes=output.toByteArray();}}finally{connection.disconnect();}
        }else{if(image.startsWith("data:")){int comma=image.indexOf(',');if(comma<0)throw new IOException("二维码图片无效");image=image.substring(comma+1);}if(image.length()>3*1024*1024)throw new IOException("二维码图片过大");bytes=android.util.Base64.decode(image,android.util.Base64.DEFAULT);}
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>2048||bounds.outHeight>2048)throw new IOException("二维码图片无效");Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length);if(bitmap==null)throw new IOException("二维码图片解码失败");return bitmap;
    }
    private void poll(int serial){
        if(!activeQr(serial)||qr==null)return;
        QrSession current=qr;if(SystemClock.elapsedRealtime()>current.deadline){setStatus("二维码已过期，请刷新");return;}
        workers.execute(()->{try{
            JSONObject response;
            if(current.provider.equals("kugou"))response=repository.loginGet(scope,"/login/qr/check",MusicRepository.params("key",current.key));
            else if(current.provider.equals("qq")){Map<String,String> params=new HashMap<>(current.params);params.put("timestamp",String.valueOf(System.currentTimeMillis()));response=repository.loginGet(scope,"/login/qq/qr/check",params);}
            else{response=repository.loginGet(scope,"/login/wx/check",MusicRepository.params("uuid",current.key,"timestamp",String.valueOf(System.currentTimeMillis())));if(response.optInt("wx_errcode",response.optInt("status",0))==405){String wxCode=AuthProtocol.text(response,"wx_code");if(wxCode.isEmpty())throw new IOException("微信授权信息缺失");if(!activeQr(serial))return;response=repository.loginGet(scope,"/login/openplat",MusicRepository.params("code",wxCode,"plat","2"));}}
            JSONObject result=response;main.post(()->{if(!activeQr(serial))return;
                if(!AuthProtocol.text(AuthProtocol.loginData(result),"token").isEmpty()){
                    if(current.provider.equals("kugou")&&AuthProtocol.kugouQrStatus(result)!=4){setStatus("扫码授权状态异常，请刷新");return;}
                    finishLogin(result,attempt);return;
                }
                boolean repeat=true;
                if(current.provider.equals("kugou")){int value=AuthProtocol.kugouQrStatus(result);if(value==0){setStatus("二维码已过期，请刷新");repeat=false;}else if(value==2)setStatus("已扫码，请在酷狗中确认");else if(value==1)setStatus("等待酷狗扫码");else{setStatus(AuthProtocol.message(result,200));repeat=false;}}
                else if(current.provider.equals("qq")){String value=AuthProtocol.text(result,"status");if(value.equals("67"))setStatus("已扫码，请在 QQ 中确认");else if(value.equals("wait")||value.equals("66"))setStatus("等待 QQ 扫码");else{setStatus(value.equals("expired")||value.equals("65")?"二维码已过期，请刷新":AuthProtocol.message(result,200));repeat=false;}}
                else{int value=result.optInt("wx_errcode",result.optInt("status",0));if(value==404)setStatus("已扫码，请在微信中确认");else if(value==408)setStatus("等待微信扫码");else{setStatus(value==402||value==403?"二维码过期或授权已取消，请刷新":AuthProtocol.message(result,200));repeat=false;}}
                if(repeat)main.postDelayed(()->poll(serial),3000);
            });
        }catch(Exception e){main.post(()->{if(activeQr(serial))setStatus(e.getMessage());});}});
    }
}
