package com.echo.remote;
import cc.echomusic.android.R;

import java.util.concurrent.*;
import java.util.function.Consumer;

/** Sends during dragging, keeps only newest unsent value and drains the final value. */
final class LiveVolumeQueue implements AutoCloseable {
    interface Send {void accept(int value)throws Exception;}
    private final int maximum;private final Send send;private final Consumer<Exception> error;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Integer pending,last;private boolean draining,accepting=true;private long lastAt;
    LiveVolumeQueue(Send send,Consumer<Exception> error){this(100,send,error);}
    LiveVolumeQueue(int maximum,Send send,Consumer<Exception> error){this.maximum=maximum;this.send=send;this.error=error;}
    synchronized void submit(int value){if(!accepting)return;pending=Math.max(0,Math.min(maximum,value));if(!draining){draining=true;worker.execute(this::drain);}}
    private void drain(){for(;;){
        synchronized(this){if(pending==null){draining=false;return;}}
        long wait=80-(System.nanoTime()-lastAt)/1_000_000;try{if(wait>0)Thread.sleep(wait);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}
        int value;synchronized(this){value=pending;pending=null;}
        if(last!=null&&last==value)continue;lastAt=System.nanoTime();
        try{send.accept(value);last=value;}catch(Exception failure){error.accept(failure);}
    }}
    @Override public synchronized void close(){accepting=false;worker.shutdown();}
}
