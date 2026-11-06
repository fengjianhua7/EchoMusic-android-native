package cc.echomusic.android;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class CompletionTest {
    static int checks;static void check(boolean value,String name){if(!value)throw new AssertionError(name);checks++;}
    public static void main(String[] args)throws Exception{
        Completion<String> result=new Completion<>();check(!result.isDone(),"initially pending");boolean timeout=false;try{result.get(1,TimeUnit.MILLISECONDS);}catch(TimeoutException e){timeout=true;}check(timeout,"bounded wait times out");AtomicInteger callbacks=new AtomicInteger();result.whenComplete((v,e)->{check(v.equals("ready")&&e==null,"listener value");callbacks.incrementAndGet();});check(result.complete("ready"),"first result accepted");check(result.get(1,TimeUnit.SECONDS).equals("ready"),"waiting reader receives value");check(!result.complete("wrong")&&callbacks.get()==1,"duplicate cannot overwrite or notify twice");result.whenComplete((v,e)->callbacks.incrementAndGet());check(callbacks.get()==2,"late listener is notified");
        Completion<String> error=new Completion<>();Exception cause=new Exception("cancelled");error.completeExceptionally(cause);boolean preserved=false;try{error.get(1,TimeUnit.SECONDS);}catch(ExecutionException e){preserved=e.getCause()==cause;}check(preserved,"verification cancellation preserves cause");
        Completion<Integer> race=new Completion<>();CountDownLatch start=new CountDownLatch(1);AtomicInteger accepted=new AtomicInteger();Thread a=new Thread(()->{try{start.await();if(race.complete(1))accepted.incrementAndGet();}catch(Exception ignored){}}),b=new Thread(()->{try{start.await();if(race.complete(2))accepted.incrementAndGet();}catch(Exception ignored){}});a.start();b.start();start.countDown();a.join();b.join();check(accepted.get()==1&&race.isDone(),"concurrent completion has one winner");
        Completion<Integer> listeners=new Completion<>();listeners.whenComplete((v,e)->{throw new IllegalStateException();});listeners.whenComplete((v,e)->callbacks.incrementAndGet());listeners.complete(1);check(callbacks.get()==3,"one listener cannot prevent later callbacks");
        System.out.println("PASS: "+checks+" API23 completion checks");
    }
}
