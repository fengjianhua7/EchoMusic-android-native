package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.widget.ImageView;

/** Album image with a sampled, contrast-safe wash; no per-frame bitmap allocations. */
final class TabletBackdrop extends ImageView {
    private final Paint wash=new Paint();
    private int tint=0xff354c58;
    private boolean landscape,lyrics;java.util.function.IntConsumer tintChanged;
    TabletBackdrop(Context context,boolean landscape){super(context);this.landscape=landscape;setScaleType(ScaleType.CENTER_CROP);}
    void lyrics(boolean value){lyrics=value;invalidate();}
    int color(){return tint;}
    @Override public void setImageBitmap(Bitmap bitmap){super.setImageBitmap(bitmap);if(bitmap==null)return;float[] hsv=new float[3];long r=0,g=0,b=0;int count=0;for(int y=1;y<9;y++)for(int x=1;x<9;x++){int c=bitmap.getPixel(bitmap.getWidth()*x/10,bitmap.getHeight()*y/10);r+=Color.red(c);g+=Color.green(c);b+=Color.blue(c);count++;}Color.colorToHSV(Color.rgb((int)(r/count),(int)(g/count),(int)(b/count)),hsv);hsv[1]=Math.min(.65f,hsv[1]);hsv[2]=Math.max(.22f,Math.min(.44f,hsv[2]));tint=Color.HSVToColor(hsv);if(tintChanged!=null)tintChanged.accept(tint);invalidate();}
    @Override protected void onDraw(Canvas canvas){
        if(!lyrics)super.onDraw(canvas);else canvas.drawColor(tint);
        int w=getWidth(),h=getHeight();
        if(!lyrics){wash.setShader(landscape?new LinearGradient(w*.15f,0,w*.69f,0,new int[]{0x20000000,(tint&0xffffff)|0xe0000000,tint},null,Shader.TileMode.CLAMP):new LinearGradient(0,h*.16f,0,h*.82f,new int[]{0x18000000,(tint&0xffffff)|0x70000000,tint},null,Shader.TileMode.CLAMP));canvas.drawRect(0,0,w,h,wash);wash.setShader(null);}
    }
}
