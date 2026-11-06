package com.echo.remote;
import cc.echomusic.android.R;

/** Clipping safety stop, not an acoustic echo detector. 15 x 20ms clipped frames. */
final class KtvMicGuard {
    private int overloaded;
    boolean process(byte[] pcm){
        int clipped=0;double energy=0;int samples=pcm.length/2;
        for(int i=0;i<pcm.length;i+=2){int value=(short)((pcm[i]&255)|(pcm[i+1]<<8));energy+=(double)value*value;if(Math.abs(value)>32000)clipped++;}
        boolean high=clipped>samples*0.03&&Math.sqrt(energy/Math.max(1,samples))>11000;
        overloaded=high?overloaded+1:Math.max(0,overloaded-2);
        return overloaded<15;
    }
}
