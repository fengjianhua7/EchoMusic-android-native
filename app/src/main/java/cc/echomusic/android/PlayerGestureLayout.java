package cc.echomusic.android;

import android.content.Context;
import android.view.*;
import android.widget.LinearLayout;
import java.util.function.BooleanSupplier;

/** Downward dismissal is separate from lyric scrolling and horizontal page swipes. */
final class PlayerGestureLayout extends LinearLayout {
    private final Runnable dismiss;private final BooleanSupplier lyrics;
    private final float density,slop;private float x,y;private boolean eligible,dragging;
    PlayerGestureLayout(Context context,BooleanSupplier lyrics,Runnable dismiss){super(context);setOrientation(VERTICAL);setClickable(true);this.lyrics=lyrics;this.dismiss=dismiss;density=getResources().getDisplayMetrics().density;slop=Math.max(20*density,ViewConfiguration.get(context).getScaledTouchSlop());}
    @Override public boolean onInterceptTouchEvent(MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){x=event.getX();y=event.getY();dragging=false;eligible=y<getHeight()-getPaddingBottom()-120*density&&(!lyrics.getAsBoolean()||y<getPaddingTop()+104*density);}
        if(eligible&&event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getX()-x,dy=event.getY()-y;if(dy>slop&&dy>Math.abs(dx)*1.5f){dragging=true;return true;}}
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(MotionEvent event){if(!dragging&&eligible&&event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getX()-x,dy=event.getY()-y;if(dy>slop&&dy>Math.abs(dx)*1.5f)dragging=true;}if(!dragging)return super.onTouchEvent(event);float distance=Math.max(0,event.getY()-y);if(event.getActionMasked()==MotionEvent.ACTION_MOVE){setTranslationY(Math.min(distance,getHeight()*.45f));return true;}if(event.getActionMasked()==MotionEvent.ACTION_UP){dragging=false;if(distance>100*density){animate().translationY(getHeight()).alpha(.6f).setDuration(180).withEndAction(dismiss).start();}else animate().translationY(0).setDuration(180).start();return true;}if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;animate().translationY(0).setDuration(180).start();return true;}return true;}
}
