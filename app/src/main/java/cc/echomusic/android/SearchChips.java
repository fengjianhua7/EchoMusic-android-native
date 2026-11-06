package cc.echomusic.android;

import android.content.Context;
import android.view.*;

/** Wrapping suggestions; long keywords are measured within the available width. */
final class SearchChips extends ViewGroup {
    private final int gap;
    SearchChips(Context context){super(context);gap=Math.round(8*getResources().getDisplayMetrics().density);}
    @Override protected void onMeasure(int widthSpec,int heightSpec){int width=MeasureSpec.getSize(widthSpec),available=Math.max(1,width-getPaddingLeft()-getPaddingRight()),x=0,y=0,line=0;for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child.getVisibility()==GONE)continue;LayoutParams params=child.getLayoutParams();child.measure(MeasureSpec.makeMeasureSpec(available,MeasureSpec.AT_MOST),params.height>=0?MeasureSpec.makeMeasureSpec(params.height,MeasureSpec.EXACTLY):MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));if(x>0&&x+child.getMeasuredWidth()>available){x=0;y+=line+gap;line=0;}x+=child.getMeasuredWidth()+gap;line=Math.max(line,child.getMeasuredHeight());}setMeasuredDimension(width,resolveSize(y+line+getPaddingTop()+getPaddingBottom(),heightSpec));}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){int x=getPaddingLeft(),y=getPaddingTop(),line=0,right=getWidth()-getPaddingRight();for(int i=0;i<getChildCount();i++){View child=getChildAt(i);if(child.getVisibility()==GONE)continue;if(x>getPaddingLeft()&&x+child.getMeasuredWidth()>right){x=getPaddingLeft();y+=line+gap;line=0;}child.layout(x,y,x+child.getMeasuredWidth(),y+child.getMeasuredHeight());x+=child.getMeasuredWidth()+gap;line=Math.max(line,child.getMeasuredHeight());}}
}
