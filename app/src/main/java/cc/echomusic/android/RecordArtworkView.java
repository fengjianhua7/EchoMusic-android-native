package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;

/** Lightweight drawn record, with real artwork in the circular centre. No raster skin. */
final class RecordArtworkView extends FrameLayout {
    final ImageView image;final TextView placeholder;private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    RecordArtworkView(Context context){super(context);setWillNotDraw(false);image=new ImageView(context);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setOutlineProvider(new ViewOutlineProvider(){public void getOutline(View v,Outline out){out.setOval(0,0,v.getWidth(),v.getHeight());}});image.setClipToOutline(true);addView(image);placeholder=new TextView(context);placeholder.setText("♪");placeholder.setTextColor(0xff718087);placeholder.setGravity(Gravity.CENTER);addView(placeholder);}
    @Override protected void onMeasure(int width,int height){int size=Math.min(MeasureSpec.getSize(width),MeasureSpec.getSize(height));size=Math.min(size,Math.round(390*getResources().getDisplayMetrics().density));super.onMeasure(MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY));}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){int size=getWidth();int diameter=Math.round(size*.46f),left=(size-diameter)/2,top=(size-diameter)/2;image.layout(left,top,left+diameter,top+diameter);placeholder.layout(left,top,left+diameter,top+diameter);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float w=getWidth(),center=w/2,radius=w*.41f;
        paint.setColor(0xfffcfcfd);paint.setShader(null);canvas.drawRoundRect(0,0,w,w,w*.075f,w*.075f,paint);
        paint.setShader(new SweepGradient(center,center,new int[]{0xffa4a6aa,0xffe5e5e7,0xff94979b,0xffe8e8e9,0xffa4a6aa},null));canvas.drawCircle(center,center,radius,paint);paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(Math.max(1,w*.002f));for(int i=0;i<22;i++){paint.setColor(i%2==0?0x22ffffff:0x18808080);canvas.drawCircle(center,center,radius-i*w*.006f,paint);}paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xffd6d8da);canvas.drawCircle(w*.88f,w*.14f,w*.075f,paint);paint.setColor(0xfff8f8f9);canvas.drawCircle(w*.88f,w*.14f,w*.052f,paint);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);Path arm=new Path();arm.moveTo(w*.88f,w*.14f);arm.lineTo(w*.81f,w*.57f);arm.quadTo(w*.80f,w*.64f,w*.75f,w*.70f);arm.lineTo(w*.69f,w*.77f);paint.setColor(0xffa2a6aa);paint.setStrokeWidth(w*.025f);canvas.drawPath(arm,paint);paint.setColor(0xfff6f7f8);paint.setStrokeWidth(w*.012f);canvas.drawPath(arm,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(0xffe8e9eb);canvas.drawRoundRect(w*.65f,w*.73f,w*.73f,w*.82f,w*.025f,w*.025f,paint);
    }
}
