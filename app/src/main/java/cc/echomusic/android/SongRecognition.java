package cc.echomusic.android;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;

final class SongRecognition {
    @SuppressLint("MissingPermission")
    static byte[] capture(AtomicBoolean cancelled,IntConsumer progress)throws IOException{
        int rate=8000;
        int channel=AudioFormat.CHANNEL_IN_MONO,encoding=AudioFormat.ENCODING_PCM_16BIT;
        int minimum=AudioRecord.getMinBufferSize(rate,channel,encoding);
        if(minimum<=0){rate=16000;minimum=AudioRecord.getMinBufferSize(rate,channel,encoding);}
        if(minimum<=0)throw new IOException("设备不支持所需的麦克风格式");
        AudioRecord recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,channel,encoding,Math.max(minimum,3200));
        try{
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("无法开启麦克风");
            if(cancelled.get())return new byte[0];
            recorder.startRecording();
            byte[] pcm=new byte[rate*2*8];
            int offset=0,lastSecond=0;
            long deadline=SystemClock.elapsedRealtime()+11000;
            while(offset<pcm.length&&!cancelled.get()&&SystemClock.elapsedRealtime()<deadline){
                int count=recorder.read(pcm,offset,Math.min(3200,pcm.length-offset),AudioRecord.READ_NON_BLOCKING);
                if(count<0)throw new IOException("录音读取失败");
                offset+=count;
                int seconds=offset/(rate*2);
                if(seconds>lastSecond){progress.accept(seconds);lastSecond=seconds;}
                if(count==0)SystemClock.sleep(20);
            }
            if(cancelled.get())return new byte[0];
            if(offset<pcm.length)throw new IOException("录音未完成，请重试");
            if(rate==8000)return pcm;
            byte[] downsampled=new byte[pcm.length/2];
            for(int i=0,j=0;i<pcm.length;i+=4,j+=2){
                int left=(short)((pcm[i]&255)|(pcm[i+1]<<8));
                int right=(short)((pcm[i+2]&255)|(pcm[i+3]<<8));
                int sample=(left+right)/2;
                downsampled[j]=(byte)sample;
                downsampled[j+1]=(byte)(sample>>8);
            }
            return downsampled;
        }finally{recorder.release();}
    }
}
