package cc.echomusic.android;

import android.media.*;
import android.os.*;

/** No API26-only type in the service's field/method descriptors. */
final class AudioFocusCompat {
    private final AudioManager audio;
    private final AudioManager.OnAudioFocusChangeListener listener;
    private final Object modern;
    AudioFocusCompat(AudioManager audio,AudioAttributes attributes,AudioManager.OnAudioFocusChangeListener listener,Handler handler){this.audio=audio;this.listener=listener;modern=Build.VERSION.SDK_INT>=26?Api26.create(attributes,listener,handler):null;}
    @SuppressWarnings("deprecation") int request(){return Build.VERSION.SDK_INT>=26?Api26.request(audio,modern):audio.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);}
    @SuppressWarnings("deprecation") void abandon(){if(Build.VERSION.SDK_INT>=26)Api26.abandon(audio,modern);else audio.abandonAudioFocus(listener);}
    @android.annotation.TargetApi(26) private static final class Api26 {
        static Object create(AudioAttributes attributes,AudioManager.OnAudioFocusChangeListener listener,Handler handler){return new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attributes).setOnAudioFocusChangeListener(listener,handler).build();}
        static int request(AudioManager audio,Object focus){return audio.requestAudioFocus((AudioFocusRequest)focus);}
        static void abandon(AudioManager audio,Object focus){audio.abandonAudioFocusRequest((AudioFocusRequest)focus);}
    }
}
