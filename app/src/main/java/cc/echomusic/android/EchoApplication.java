package cc.echomusic.android;

import android.app.Application;
import android.content.SharedPreferences;

public final class EchoApplication extends Application {
    @Override public void onCreate(){
        super.onCreate();
        SharedPreferences preferences=getSharedPreferences("echo",MODE_PRIVATE);
        if(!preferences.getBoolean("embedded_default_v3",false)){
            // Existing external accounts stay in their old namespace; they are never copied to the built-in API.
            preferences.edit().putString("endpoint",ApiEndpoint.LOCAL).remove("authorization")
                .putBoolean("embedded_default_v3",true).putLong("session_version",preferences.getLong("session_version",0)+1).apply();
        }
        if(!BuildConfig.TV)EmbeddedApi.instance().start(this);
    }
}
