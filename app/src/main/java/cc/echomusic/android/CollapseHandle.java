package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;

final class CollapseHandle extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    CollapseHandle(Context context,Runnable action){super(context);setContentDescription("收起播放页");setOnClickListener(v->action.run());paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setColor(0xff48565f);}
    CollapseHandle(Context context,int color,Runnable action){this(context,action);paint.setColor(color);}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);float d=getResources().getDisplayMetrics().density,cx=getWidth()/2f,cy=getHeight()/2f;paint.setStrokeWidth(1.6f*d);Path path=new Path();path.moveTo(cx-7*d,cy-3*d);path.lineTo(cx,cy+4*d);path.lineTo(cx+7*d,cy-3*d);canvas.drawPath(path,paint);}
}
