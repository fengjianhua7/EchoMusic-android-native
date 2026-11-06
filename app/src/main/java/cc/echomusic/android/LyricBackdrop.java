package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Fixed contrast scrims keep bright covers from washing out the lyric column. */
final class LyricBackdrop extends View {
    private int tint=0xff213e52;
    void tint(int color){if(BuildConfig.TV)return;tint=color;onSizeChanged(getWidth(),getHeight(),0,0);invalidate();}
    private int shade(int alpha){return (alpha<<24)|(tint&0xffffff);}
    private final Paint paint=new Paint();private final boolean wide;private Shader horizontal,vertical;
    public LyricBackdrop(Context context){this(context,false);}
    LyricBackdrop(Context context,boolean wide){super(context);this.wide=wide;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);if(w<=0||h<=0)return;
        horizontal=new LinearGradient(0,0,w,0,wide?new int[]{shade(0x28),shade(0x55),shade(0xee),shade(0xff)}:new int[]{shade(0xd9),shade(0xe6)},wide?new float[]{0,.30f,.56f,1}:null,Shader.TileMode.CLAMP);
        vertical=new LinearGradient(0,0,0,h,new int[]{shade(0x70),shade(0x00),shade(0xf0)},new float[]{0,.35f,1},Shader.TileMode.CLAMP);
    }
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
        paint.setShader(horizontal);canvas.drawRect(0,0,getWidth(),getHeight(),paint);
        paint.setShader(vertical);canvas.drawRect(0,0,getWidth(),getHeight(),paint);paint.setShader(null);
    }
}
