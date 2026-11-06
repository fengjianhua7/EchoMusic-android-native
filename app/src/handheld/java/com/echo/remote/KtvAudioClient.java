package com.echo.remote;
import cc.echomusic.android.R;

import android.media.*;
import android.media.audiofx.*;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

/** Dedicated PCM lanes: accompaniment downlink; microphone uplink. No microphone loopback. */
final class KtvAudioClient implements AutoCloseable {
    static final int RATE=48000, MUSIC_BYTES=3840, MIC_BYTES=1920;
    private final String host,token;private final int port;
    private final java.util.function.Consumer<String> failure,micFailure;
    private volatile boolean closed,micEnabled,micFaulted,voiceMonitor;
    private volatile int micEpoch;
    private volatile Socket monitorSocket,micSocket;
    private volatile AudioTrack output,voiceOutput;
    private volatile AudioRecord recorder;
    private volatile float monitorVolume=0.65f;
    private static boolean headphones(AudioTrack track){if(track==null)return false;AudioDeviceInfo device=track.getRoutedDevice();if(device==null)return false;switch(device.getType()){case AudioDeviceInfo.TYPE_WIRED_HEADSET:case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:case AudioDeviceInfo.TYPE_USB_HEADSET:case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:return true;default:return false;}}
    private float safeMonitor(){return (micEnabled||micFaulted)&&!headphones(output)?0:monitorVolume;}
    KtvAudioClient(String base,JSONObject session,java.util.function.Consumer<String> failure)throws Exception{this(base,session,failure,failure);}
    KtvAudioClient(String base,JSONObject session,java.util.function.Consumer<String> failure,java.util.function.Consumer<String> micFailure)throws Exception{
        new RemoteApi(base);host=Uri.parse(base).getHost();port=session.getInt("mediaPort");token=session.getString("token");
        if(port<1||port>65535||token.length()<24||token.length()>256)throw new IOException("KTV 连接信息无效");this.failure=failure;this.micFailure=micFailure;
    }
    private Socket connect(String mode)throws Exception{
        Socket socket=new Socket();
        try{socket.connect(new InetSocketAddress(host,port),4000);socket.setTcpNoDelay(true);socket.setSoTimeout(5000);
            OutputStream stream=socket.getOutputStream();stream.write((new JSONObject().put("mode",mode).put("protocol",2).put("token",token).toString()+"\n").getBytes(StandardCharsets.UTF_8));stream.flush();
            StringBuilder reply=new StringBuilder();int c;while((c=socket.getInputStream().read())!=-1&&c!='\n'){reply.append((char)c);if(reply.length()>1024)throw new IOException("KTV 握手异常");}
            JSONObject response=new JSONObject(reply.toString());if(c<0||!response.optBoolean("ok"))throw new IOException("KTV 音频连接被拒绝");if("monitor".equals(mode)&&response.optInt("protocol")<2)throw new IOException("请更新电脑完整插件以同步 KTV 音频时间戳");return socket;
        }catch(Exception error){socket.close();throw error;}
    }
    void start(){new Thread(this::monitor,"ktv-monitor").start();}
    void setMonitor(float value){monitorVolume=Math.max(0,Math.min(1,value));AudioTrack track=output;if(track!=null)try{track.setVolume(safeMonitor());}catch(IllegalStateException ignored){}}
    private void monitor(){int attempts=0;while(!closed){long started=android.os.SystemClock.elapsedRealtime();try{monitorOnce();return;}catch(Exception error){if(closed)return;KtvService.CLOCK.clear();if(android.os.SystemClock.elapsedRealtime()-started>10000)attempts=0;if(++attempts>=3){failure.accept("KTV 监听重连失败："+error.getMessage());return;}try{Thread.sleep(attempts*500L);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return;}}}}
    private void monitorOnce() throws Exception{
        AudioTrack track=null;
        try{
            Socket socket=connect("monitor");monitorSocket=socket;if(closed){socket.close();return;}
            int minimum=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_STEREO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<0)throw new IOException("手机不支持 48kHz 监听");
            track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                .setBufferSizeInBytes(Math.max(minimum,MUSIC_BYTES*2)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();
            if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("监听输出初始化失败");output=track;track.setVolume(safeMonitor());track.play();
            DataInputStream input=new DataInputStream(socket.getInputStream());byte[] frame=new byte[MUSIC_BYTES+16];long written=0;
            while(!closed){
                int stale=Math.min(50,Math.max(0,input.available()/frame.length-2));for(int i=0;i<stale;i++)KtvFrameReader.read(input,frame,()->closed);
                KtvFrameReader.read(input,frame,()->closed);float audible=safeMonitor();KtvService.monitorAudible=audible>0.001f;track.setVolume(audible);for(int offset=0;offset<MUSIC_BYTES&&!closed;){int n=track.write(frame,16+offset,MUSIC_BYTES-offset,AudioTrack.WRITE_BLOCKING);if(n<=0)throw new IOException("监听播放中断");offset+=n;written+=n/4;}
                java.nio.ByteBuffer stamp=java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.LITTLE_ENDIAN);KtvService.CLOCK.update(stamp.getLong(0),stamp.getInt(8),stamp.getInt(12),written,track.getPlaybackHeadPosition()&0xffffffffL,android.os.SystemClock.elapsedRealtime());
            }
        }finally{KtvService.monitorAudible=false;output=null;if(track!=null){try{track.pause();track.flush();}catch(Exception ignored){}track.release();}closeSocket(monitorSocket);monitorSocket=null;}
    }
    void setVoiceMonitor(boolean enabled)throws IOException{if(enabled&&!micEnabled)throw new IOException("请先开启麦克风");if(enabled&&!headphones(output))throw new IOException("请连接耳机后开启人声监听，避免外放啸叫");voiceMonitor=enabled;AudioTrack track=voiceOutput;if(!enabled&&track!=null)try{track.setVolume(0);}catch(IllegalStateException ignored){}}
    private synchronized void monitorVoice(byte[] pcm){if(voiceMonitor&&!headphones(output)){voiceMonitor=false;KtvService.voiceMonitor=false;KtvService.status="耳机已断开，已关闭人声监听";}if(!voiceMonitor){AudioTrack old=voiceOutput;voiceOutput=null;if(old!=null){try{old.pause();old.flush();}catch(Exception ignored){}old.release();}return;}
        try{AudioTrack track=voiceOutput;if(track==null){int minimum=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);if(minimum<0)throw new IOException("手机不支持本地耳返");track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()).setBufferSizeInBytes(Math.max(minimum,MIC_BYTES*2)).setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).build();voiceOutput=track;track.setPreferredDevice(output.getRoutedDevice());track.setVolume(0);track.play();}boolean routed=headphones(track);track.setVolume(routed?1:0);if(routed)track.write(pcm,0,pcm.length,AudioTrack.WRITE_NON_BLOCKING);
        }catch(Exception error){voiceMonitor=false;KtvService.voiceMonitor=false;KtvService.status="人声监听不可用："+error.getMessage();}}
    synchronized void microphone(boolean enabled){
        if(closed||enabled==micEnabled)return;voiceMonitor=false;monitorVoice(new byte[0]);micEnabled=enabled;if(!enabled){voiceMonitor=false;KtvService.voiceMonitor=false;}micFaulted=false;int epoch=++micEpoch;setMonitor(monitorVolume);
        AudioRecord previous=recorder;if(previous!=null)try{previous.stop();}catch(Exception ignored){}closeSocket(micSocket);micSocket=null;
        if(enabled)new Thread(()->record(epoch),"ktv-microphone").start();
    }
    private int rawAudioSource(){AudioManager manager=KtvService.audioManager();return manager!=null&&"true".equalsIgnoreCase(manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))?MediaRecorder.AudioSource.UNPROCESSED:MediaRecorder.AudioSource.VOICE_RECOGNITION;}
    @android.annotation.SuppressLint("MissingPermission")
    private void record(int epoch){
        AudioRecord record=null;Socket socket=null;AcousticEchoCanceler echo=null;NoiseSuppressor noise=null;AutomaticGainControl gain=null;
        try{
            socket=connect("mic");if(closed||epoch!=micEpoch){socket.close();return;}micSocket=socket;
            int minimum=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<0)throw new IOException("手机不支持当前录音格式");
            record=new AudioRecord.Builder().setAudioSource(rawAudioSource())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(minimum,MIC_BYTES*3)).build();
            if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("麦克风初始化失败");
            try{if(AcousticEchoCanceler.isAvailable()){echo=AcousticEchoCanceler.create(record.getAudioSessionId());if(echo!=null)echo.setEnabled(false);}}catch(RuntimeException ignored){}try{if(NoiseSuppressor.isAvailable()){noise=NoiseSuppressor.create(record.getAudioSessionId());if(noise!=null)noise.setEnabled(false);}}catch(RuntimeException ignored){}try{if(AutomaticGainControl.isAvailable()){gain=AutomaticGainControl.create(record.getAudioSessionId());if(gain!=null)gain.setEnabled(false);}}catch(RuntimeException ignored){}
            if(closed||epoch!=micEpoch)return;recorder=record;record.startRecording();OutputStream stream=socket.getOutputStream();byte[] frame=new byte[MIC_BYTES];
            while(!closed&&micEnabled&&epoch==micEpoch){int offset=0;while(offset<frame.length&&epoch==micEpoch){int n=record.read(frame,offset,frame.length-offset,AudioRecord.READ_BLOCKING);if(n<=0)throw new IOException("录音中断");offset+=n;}if(epoch==micEpoch){monitorVoice(frame);stream.write(frame);}}
        }catch(Exception error){if(!closed&&epoch==micEpoch){micEnabled=false;micFaulted=true;micFailure.accept("麦克风已关闭："+error.getMessage());}}
        finally{synchronized(this){if(epoch==micEpoch){voiceMonitor=false;monitorVoice(new byte[0]);}}if(recorder==record)recorder=null;if(echo!=null)echo.release();if(noise!=null)noise.release();if(gain!=null)gain.release();if(record!=null){try{record.stop();}catch(Exception ignored){}record.release();}closeSocket(socket);}
    }
    private static void closeSocket(Socket socket){if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    @Override public void close(){closed=true;voiceMonitor=false;monitorVoice(new byte[0]);micEnabled=false;++micEpoch;closeSocket(monitorSocket);closeSocket(micSocket);AudioRecord record=recorder;if(record!=null)try{record.stop();}catch(Exception ignored){}AudioTrack track=output;if(track!=null)try{track.pause();track.flush();}catch(Exception ignored){}}
}


