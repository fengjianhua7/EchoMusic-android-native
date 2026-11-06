package cc.echomusic.android;

import android.content.Context;
import android.view.*;
import android.widget.LinearLayout;

/** Horizontal track gesture; ordinary child taps and vertical motion remain untouched. */
final class SwipePlayerBar extends LinearLayout {
    private final Runnable previous,next;private final float slop,threshold;
    private float downX,downY;private boolean dragging;
    SwipePlayerBar(Context context,Runnable previous,Runnable next){super(context);setClickable(true);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);this.previous=previous;this.next=next;float density=getResources().getDisplayMetrics().density;slop=Math.max(ViewConfiguration.get(context).getScaledTouchSlop(),16*density);threshold=56*density;}
    @Override public boolean onInterceptTouchEvent(android.view.MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){downX=event.getX();downY=event.getY();dragging=false;}
        if(event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getX()-downX,dy=event.getY()-downY;if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.4f){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);return true;}}
        return super.onInterceptTouchEvent(event);
    }
    @Override public boolean onTouchEvent(android.view.MotionEvent event){
        if(!dragging&&event.getActionMasked()==MotionEvent.ACTION_MOVE){float dx=event.getX()-downX,dy=event.getY()-downY;if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)*1.4f){dragging=true;getParent().requestDisallowInterceptTouchEvent(true);}}
        if(!dragging)return super.onTouchEvent(event);
        if(event.getActionMasked()==MotionEvent.ACTION_UP){float dx=event.getX()-downX;if(Math.abs(dx)>=threshold){if(dx<0)next.run();else previous.run();performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);}dragging=false;return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){dragging=false;return true;}return true;
    }
}
