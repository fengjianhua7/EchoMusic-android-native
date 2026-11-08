package cc.echomusic.android;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class SongRecognition {
    @SuppressLint("MissingPermission")
    static byte[] capture(AtomicBoolean cancelled)throws IOException{
        int rate=8000;
        int channel=AudioFormat.CHANNEL_IN_MONO,encoding=AudioFormat.ENCODING_PCM_16BIT;
        int minimum=AudioRecord.getMinBufferSize(rate,channel,encoding);
        if(minimum<=0)throw new IOException("设备不支持所需的麦克风格式");
        AudioRecord recorder=new AudioRecord(MediaRecorder.AudioSource.MIC,rate,channel,encoding,Math.max(minimum,3200));
        try{
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("无法开启麦克风");
            if(cancelled.get())return new byte[0];
            recorder.startRecording();
            byte[] pcm=new byte[rate*2*8];
            int offset=0;
            long deadline=SystemClock.elapsedRealtime()+11000;
            while(offset<pcm.length&&!cancelled.get()&&SystemClock.elapsedRealtime()<deadline){
                int count=recorder.read(pcm,offset,Math.min(3200,pcm.length-offset),AudioRecord.READ_NON_BLOCKING);
                if(count<0)throw new IOException("录音读取失败");
                offset+=count;
                if(count==0)SystemClock.sleep(20);
            }
            if(cancelled.get())return new byte[0];
            if(offset<pcm.length)throw new IOException("录音未完成，请重试");
            return pcm;
        }finally{recorder.release();}
    }
}
