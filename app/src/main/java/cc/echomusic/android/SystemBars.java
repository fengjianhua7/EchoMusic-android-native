package cc.echomusic.android;

import android.os.Build;
import android.view.*;

final class SystemBars {
    static void apply(Window window){
        apply(window,BuildConfig.TV?0xffa5f2f3:0xfff3f5f7,true);
    }
    static void applyAlbum(Window window,int color){apply(window,color,false);}
    static void applyPlayer(Window window){
        apply(window,0xff213e52,false);
    }
    private static void apply(Window window,int color,boolean darkIcons){
        if(window==null||Build.VERSION.SDK_INT<21)return;
        // PhoneWindow.getInsetsController() dereferences its decor without creating it.
        // Install decor first, including callers which run before setContentView().
        View decor=window.getDecorView();
        window.setStatusBarColor(color);window.setNavigationBarColor(color);
        if(Build.VERSION.SDK_INT>=29){window.setStatusBarContrastEnforced(false);window.setNavigationBarContrastEnforced(false);}
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController controller=decor.getWindowInsetsController();if(controller!=null)controller.setSystemBarsAppearance(darkIcons?WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS:0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);}
        else decor.setSystemUiVisibility(darkIcons?View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|(Build.VERSION.SDK_INT>=26?View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:0):0);
    }
}
