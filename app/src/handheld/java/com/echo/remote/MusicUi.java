package com.echo.remote;
import cc.echomusic.android.R;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;

final class MusicUi {
    static final int BG=0xfff3f5f7, SURFACE=0xffffffff, MUTED=0xff718087, TEXT=0xff18252b, BLUE=0xff008a65;
    static int dp(Context c,float value) { return Math.round(value*c.getResources().getDisplayMetrics().density); }
    static GradientDrawable shape(int color,float radius,Context c) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radius)); return d;
    }
    static TextView text(Context c,String value,int sp,int color) {
        TextView t=new TextView(c); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setFontFeatureSettings("kern"); return t;
    }
    static Button pill(Context c,String title,Runnable action) {
        Button b=new Button(c); b.setText(title); b.setAllCaps(false); b.setTextColor(TEXT); b.setTextSize(14);
        b.setMinHeight(dp(c,48)); b.setMinimumHeight(dp(c,48)); b.setMinWidth(0); b.setMinimumWidth(0);
        b.setPadding(dp(c,12),0,dp(c,12),0); b.setBackground(shape(SURFACE,14,c));
        b.setBackgroundTintList(ColorStateList.valueOf(SURFACE)); b.setOnClickListener(v->action.run()); return b;
    }
    static final class Cover extends ImageView {
        private final Path clip=new Path();
        private final float radius;
        public Cover(Context context) { this(context,20); }
        Cover(Context context,int radius) { super(context); this.radius=dp(context,radius); }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) {
            super.onSizeChanged(w,h,oldw,oldh);clip.reset();
            clip.addRoundRect(0,0,w,h,radius,radius,Path.Direction.CW);
        }
        @Override protected void onDraw(Canvas canvas) {
            int count=canvas.save();canvas.clipPath(clip);super.onDraw(canvas);canvas.restoreToCount(count);
        }
    }
    static final class Icon extends View {
        String glyph;
        int color=TEXT;
        private boolean filled;
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path=new Path();
        private final Path heart=new Path();
        private static final float[] PLAY={8,4,20,12,8,20}, PREV={19,5,7,12,19,19}, NEXT={5,5,17,12,5,19}, VOLUME={3,9,7,9,12,4,12,20,7,15,3,15};
        public Icon(Context c) { this(c,"music","音乐",()->{}); }
        Icon(Context c,String glyph,String description,Runnable click) {
            super(c); this.glyph=glyph; setContentDescription(description);
            setMinimumWidth(dp(c,48)); setMinimumHeight(dp(c,48)); setFocusable(true);
            setOnClickListener(v->click.run());
            android.util.TypedValue value=new android.util.TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless,value,true);
            if(value.resourceId!=0) setBackgroundResource(value.resourceId);
            heart.moveTo(12,21); heart.cubicTo(9,18,2,13,2,8);
            heart.cubicTo(2,2,9,1,12,6); heart.cubicTo(15,1,22,2,22,8); heart.cubicTo(22,13,15,18,12,21);
        }
        void glyph(String value,String description) { glyph=value; setContentDescription(description); invalidate(); }
        void color(int value) { color=value; invalidate(); }
        void favorite(boolean value){filled=value;color=value?0xffff688d:TEXT;invalidate();}
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            c.save(); float size=Math.min(getWidth(),getHeight())*0.49f;
            c.translate((getWidth()-size)/2,(getHeight()-size)/2); c.scale(size/24,size/24);
            p.setColor(color); p.setStrokeWidth(1.8f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);
            switch(glyph) {
                case "mic": c.drawRoundRect(8,2,16,15,4,4,p);c.drawArc(5,6,19,19,0,180,false,p);c.drawLine(12,19,12,23,p);c.drawLine(8,23,16,23,p);break;
                case "play": p.setStyle(Paint.Style.FILL); path(c,PLAY); break;
                case "pause": p.setStyle(Paint.Style.FILL); c.drawRoundRect(6,4,10,20,1,1,p); c.drawRoundRect(14,4,18,20,1,1,p); break;
                case "prev": c.drawLine(5,5,5,19,p); path(c,PREV); break;
                case "next": c.drawLine(19,5,19,19,p); path(c,NEXT); break;
                case "heart": {
                    if(filled)p.setStyle(Paint.Style.FILL);
                    c.drawPath(heart,p); break;
                }
                case "queue": c.drawLine(3,5,21,5,p); c.drawLine(3,11,21,11,p); c.drawLine(3,17,14,17,p); c.drawCircle(20,18,1,p); break;
                case "music": c.drawLine(10,18,10,5,p); c.drawLine(10,5,20,3,p); c.drawLine(20,3,20,16,p);
                    c.drawOval(3,16,10,21,p); c.drawOval(13,14,20,19,p); break;
                case "search": c.drawCircle(10,10,7,p); c.drawLine(15,15,22,22,p); break;
                case "computer": c.drawRoundRect(2,3,22,17,2,2,p); c.drawLine(12,17,12,22,p); c.drawLine(7,22,17,22,p); break;
                case "plus": c.drawLine(12,4,12,20,p); c.drawLine(4,12,20,12,p); break;
                case "more": p.setStyle(Paint.Style.FILL); c.drawCircle(4,12,1.7f,p); c.drawCircle(12,12,1.7f,p); c.drawCircle(20,12,1.7f,p); break;
                case "back": c.drawLine(15,4,7,12,p); c.drawLine(7,12,15,20,p); break;
                case "close": c.drawLine(5,5,19,19,p); c.drawLine(5,19,19,5,p); break;
                case "lyric": c.drawRoundRect(4,2,20,22,2,2,p); c.drawLine(8,7,16,7,p); c.drawLine(8,12,16,12,p); c.drawLine(8,17,13,17,p); break;
                case "volume": path(c,VOLUME); c.drawArc(9,5,21,19,-55,110,false,p); break;
                default: c.drawCircle(12,12,9,p); c.drawLine(12,7,12,17,p);
            }
            c.restore();
        }
        private void path(Canvas c,float[] points) {
            path.reset(); path.moveTo(points[0],points[1]); for(int i=2;i<points.length;i+=2) path.lineTo(points[i],points[i+1]);
            path.close(); c.drawPath(path,p);
        }
    }
}
