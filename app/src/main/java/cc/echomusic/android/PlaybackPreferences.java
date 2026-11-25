package cc.echomusic.android;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 后台持续播放偏好。
 *
 * 开启后播放服务不向系统申请独占音频焦点，因此切到抖音、微信视频等会发出声音的应用时，
 * 本应用不会收到 AUDIOFOCUS_LOSS 而被动暂停，两路声音同时存在（由用户自行调节音量）。
 * 关闭后恢复系统标准行为：其他应用取走音频焦点即暂停。
 */
final class PlaybackPreferences {
    static final String KEY_BACKGROUND="background_playback_v1";
    private PlaybackPreferences(){}
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences("echo",0);}
    static boolean background(Context context){return prefs(context).getBoolean(KEY_BACKGROUND,true);}
    static void background(Context context,boolean enabled){prefs(context).edit().putBoolean(KEY_BACKGROUND,enabled).apply();}
}
