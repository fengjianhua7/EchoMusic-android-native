package cc.echomusic.android;

import android.os.*;
import java.util.*;
import java.util.concurrent.*;

/** Serializes foreground human-verification UI while workers await a single, bounded result. */
public final class VerificationBroker {
    public interface Host {void present(Challenge challenge);void dismiss(Challenge challenge);}
    public static final class Challenge {
        final String eventId,key;
        final MusicRepository repository;
        final AuthStore.Scope scope;
        final boolean skipUserAuth;
        final Completion<AuthStore.Scope> result=new Completion<>();
        Challenge(String id,MusicRepository repository,AuthStore.Scope scope,boolean skip){eventId=id;this.repository=repository;this.scope=scope;skipUserAuth=skip;key=scope.endpoint+"|"+scope.version+"|"+skip+"|"+id;}
        public boolean active(){return !result.isDone()&&repository.auth.current(scope);}
        public void succeed(AuthStore.Scope retryScope){result.complete(retryScope);}
        public void cancel(String reason){result.completeExceptionally(new java.io.IOException(reason));}
    }
    private static final VerificationBroker INSTANCE=new VerificationBroker();
    public static VerificationBroker instance(){return INSTANCE;}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Map<String,Challenge> pending=new LinkedHashMap<>();
    private Host host;private Challenge showing;
    public synchronized void attach(Host host){this.host=host;main.post(this::pump);}
    public synchronized void detach(Host host){if(this.host!=host)return;this.host=null;cancelAll("验证已暂停，请返回应用后重试；输入内容已保留");}
    public AuthStore.Scope await(String event,MusicRepository repo,AuthStore.Scope scope,boolean skip) throws Exception {
        Challenge item;
        synchronized(this){
            if(host==null)throw new java.io.IOException("需要安全验证，请打开应用并重试");
            Challenge candidate=new Challenge(event,repo,scope,skip);item=pending.get(candidate.key);
            if(item==null){item=candidate;pending.put(item.key,item);Challenge added=item;item.result.whenComplete((r,e)->main.post(()->finished(added)));main.post(this::pump);}
        }
        try{return item.result.get(5,TimeUnit.MINUTES);}
        catch(TimeoutException e){item.cancel("验证已超时，请重试；输入内容已保留");throw new java.io.IOException("安全验证已超时，请重试");}
        catch(InterruptedException e){Thread.currentThread().interrupt();item.cancel("已取消安全验证");throw e;}
        catch(ExecutionException e){Throwable cause=e.getCause();throw cause instanceof Exception?(Exception)cause:new java.io.IOException("安全验证失败");}
    }
    private synchronized void pump(){
        if(showing!=null||host==null)return;
        for(Challenge item:new ArrayList<>(pending.values())){
            if(!item.active()){item.cancel("登录状态已变化，请重新操作");continue;}
            showing=item;host.present(item);return;
        }
    }
    private synchronized void finished(Challenge item){pending.remove(item.key,item);if(showing==item){showing=null;if(host!=null)host.dismiss(item);}pump();}
    public synchronized void cancelAll(String reason){for(Challenge item:new ArrayList<>(pending.values()))item.cancel(reason);}
}
