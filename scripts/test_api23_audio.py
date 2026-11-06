"""Run the real audio-focus compatibility wrapper with deterministic API23/API26 platform stand-ins."""
from pathlib import Path
import tempfile,subprocess,os
root=Path(__file__).resolve().parents[1];java=Path(os.environ['JAVA_HOME'])/'bin'
sources={
'android/annotation/TargetApi.java':'package android.annotation;public @interface TargetApi{int value();}',
'android/os/Build.java':'package android.os;public class Build{public static class VERSION{public static int SDK_INT=23;}}',
'android/os/Handler.java':'package android.os;public class Handler{}',
'android/media/AudioAttributes.java':'package android.media;public class AudioAttributes{}',
'android/media/AudioFocusRequest.java':'''package android.media;public class AudioFocusRequest{public static int constructed;public static class Builder{public Builder(int gain){if(android.os.Build.VERSION.SDK_INT<26)throw new AssertionError("new audio focus on API23");constructed++;}public Builder setAudioAttributes(AudioAttributes a){return this;}public Builder setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener l,android.os.Handler h){return this;}public AudioFocusRequest build(){return new AudioFocusRequest();}}}''',
'android/media/AudioManager.java':'''package android.media;public class AudioManager{public interface OnAudioFocusChangeListener{void onAudioFocusChange(int value);}public static final int STREAM_MUSIC=3,AUDIOFOCUS_GAIN=1;public int legacyRequests,modernRequests,legacyAbandons,modernAbandons;public int requestAudioFocus(OnAudioFocusChangeListener l,int stream,int gain){legacyRequests++;return 1;}public int requestAudioFocus(AudioFocusRequest r){modernRequests++;return 1;}public void abandonAudioFocus(OnAudioFocusChangeListener l){legacyAbandons++;}public void abandonAudioFocusRequest(AudioFocusRequest r){modernAbandons++;}}''',
'cc/echomusic/android/AudioCompatTest.java':'''package cc.echomusic.android;import android.os.*;import android.media.*;public class AudioCompatTest{static int checks;static void check(boolean value){if(!value)throw new AssertionError();checks++;}public static void main(String[] args){AudioManager old=new AudioManager();Build.VERSION.SDK_INT=23;AudioFocusCompat focus=new AudioFocusCompat(old,new AudioAttributes(),n->{},new Handler());check(AudioFocusRequest.constructed==0);check(focus.request()==1&&old.legacyRequests==1);focus.abandon();check(old.legacyAbandons==1&&old.modernAbandons==0);check(old.modernRequests==0);for(int api:new int[]{26,35}){Build.VERSION.SDK_INT=api;AudioManager modern=new AudioManager();AudioFocusCompat current=new AudioFocusCompat(modern,new AudioAttributes(),n->{},new Handler());check(current.request()==1&&modern.modernRequests==1);current.abandon();check(modern.modernAbandons==1&&modern.legacyAbandons==0);}System.out.println("PASS: "+checks+" API23 audio focus branch checks");}}'''
}
with tempfile.TemporaryDirectory(prefix='echo-api23-audio-') as tmp:
 folder=Path(tmp)
 for name,text in sources.items():
  path=folder/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8')
 subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-d',str(folder/'classes'),*[str(p) for p in folder.rglob('*.java')],str(root/'app/src/main/java/cc/echomusic/android/AudioFocusCompat.java')],check=True)
 subprocess.run([str(java/'java.exe'),'-cp',str(folder/'classes'),'cc.echomusic.android.AudioCompatTest'],check=True)
