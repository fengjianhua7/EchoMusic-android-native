package cc.echomusic.android;

import org.json.JSONObject;
import java.io.IOException;

/** One bounded challenge/retry cycle; kept free of Android UI and transport for contract tests. */
public final class AuthRequestRunner<S> {
    public interface Current<S>{boolean matches(S scope);}
    public interface Transport<S>{Response<S> exchange(S scope,boolean skipUserAuth) throws Exception;}
    public interface Verifier<S>{S verify(String event,S scope,boolean skipUserAuth) throws Exception;}
    public static final class Response<S>{
        public final int status;
        public final JSONObject body;
        public final String event;
        public final S scope;
        public Response(int status,JSONObject body,String event,S scope){this.status=status;this.body=body;this.event=event;this.scope=scope;}
        boolean challenge(){return !event.isEmpty()&&(status>=400||AuthProtocol.failed(body));}
    }
    private final Current<S> current;
    private final Transport<S> transport;
    private final Verifier<S> verifier;
    public AuthRequestRunner(Current<S> current,Transport<S> transport,Verifier<S> verifier){this.current=current;this.transport=transport;this.verifier=verifier;}
    private void requireCurrent(S scope) throws IOException{if(!current.matches(scope))throw new IOException("登录状态已变化，请重新操作");}
    private Response<S> exchange(S scope,boolean skip) throws Exception{requireCurrent(scope);Response<S> response=transport.exchange(scope,skip);requireCurrent(scope);return response;}
    public Response<S> run(S scope,boolean skipUserAuth,boolean businessResult,boolean allowVerification) throws Exception{
        return run(scope,skipUserAuth,businessResult,allowVerification,true);
    }
    public Response<S> run(S scope,boolean skipUserAuth,boolean businessResult,boolean allowVerification,boolean allowAccountChange) throws Exception{
        Response<S> response=exchange(scope,skipUserAuth);
        if(response.challenge()){
            if(!allowVerification)throw new AuthProtocol.ApiException(response.body,response.status,response.event);
            S retry=verifier.verify(response.event,scope,skipUserAuth);
            if(!allowAccountChange)requireCurrent(scope);
            requireCurrent(retry);response=exchange(retry,skipUserAuth);
            if(response.challenge())throw new IOException("验证完成后平台仍拒绝本次操作，请稍后重试；输入内容已保留");
        }
        boolean multiAccount=businessResult&&AuthProtocol.accounts(response.body).length()>0;
        if((response.status>=300&&!multiAccount)||(!businessResult&&AuthProtocol.failed(response.body)))throw new AuthProtocol.ApiException(response.body,response.status,response.event);
        return response;
    }
}
