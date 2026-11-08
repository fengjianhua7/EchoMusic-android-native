package cc.echomusic.android;

import android.content.Context;
import androidx.multidex.MultiDex;

public final class LegacyApplication extends EchoApplication {
    @Override protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        MultiDex.install(this);
    }
}
