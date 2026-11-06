package com.echo.remote;
import cc.echomusic.android.R;

/** Position attached to PCM, compensated by the phone AudioTrack playback queue. */
final class KtvPlaybackClock {
    private volatile Sample sample;
    private static final class Sample {long position,updated;int track,flags;Sample(long p,long now,int t,int f){position=p;updated=now;track=t;flags=f;}}
    void update(long frameMs,int track,int flags,long writtenFrames,long playedFrames,long now){long queued=Math.max(0,writtenFrames-playedFrames);sample=new Sample(Math.max(0,frameMs+((flags&1)!=0?20:0)-queued*1000/48000),now,track,flags);}
    Double seconds(String track,long now){Sample s=sample;if(s==null||s.track!=track.hashCode()||(s.flags&2)==0||now-s.updated>500||now<s.updated)return null;return (s.position+((s.flags&1)!=0?Math.min(200,now-s.updated):0))/1000.0;}
    void clear(){sample=null;}
}


