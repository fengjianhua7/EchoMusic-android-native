package cc.echomusic.android;

import java.util.*;
import java.util.concurrent.*;

/** Single-result completion using primitives available before Android 6.0. */
final class Completion<T> {
    interface Listener<T>{void accept(T value,Throwable error);}
    private final CountDownLatch latch=new CountDownLatch(1);
    private final List<Listener<T>> listeners=new ArrayList<>();
    private volatile boolean done;private T value;private Throwable error;
    boolean isDone(){return done;}
    boolean complete(T value){return finish(value,null);}
    boolean completeExceptionally(Throwable error){if(error==null)throw new NullPointerException("error");return finish(null,error);}
    private boolean finish(T value,Throwable error){List<Listener<T>> notify;
        synchronized(this){if(done)return false;this.value=value;this.error=error;done=true;notify=new ArrayList<>(listeners);listeners.clear();latch.countDown();}
        for(Listener<T> listener:notify)notify(listener,value,error);return true;
    }
    void whenComplete(Listener<T> listener){T result;Throwable failure;synchronized(this){if(!done){listeners.add(listener);return;}result=value;failure=error;}notify(listener,result,failure);}
    private static <T>void notify(Listener<T> listener,T value,Throwable error){try{listener.accept(value,error);}catch(RuntimeException ignored){}}
    T get(long timeout,TimeUnit unit)throws InterruptedException,ExecutionException,TimeoutException{if(!latch.await(timeout,unit))throw new TimeoutException();if(error!=null)throw new ExecutionException(error);return value;}
}
