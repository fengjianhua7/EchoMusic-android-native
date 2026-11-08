package cc.echomusic.android;

import android.content.Context;
import android.graphics.Outline;
import android.view.*;
import android.widget.*;

@android.annotation.TargetApi(21)
final class RecordArtworkView extends FrameLayout {
    final ImageView image;
    final TextView placeholder;
    RecordArtworkView(Context context){
        super(context);
        setBackgroundColor(0xffdde4e8);
        setOutlineProvider(new ViewOutlineProvider(){
            @Override public void getOutline(View view,Outline outline){
                outline.setRoundRect(0,0,getWidth(),getHeight(),24*getResources().getDisplayMetrics().density);
            }
        });
        setClipToOutline(true);
        image=new ImageView(context);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(image,new LayoutParams(-1,-1));
        placeholder=new TextView(context);
        placeholder.setText("♪");
        placeholder.setGravity(Gravity.CENTER);
        addView(placeholder,new LayoutParams(-1,-1));
    }
    @Override protected void onMeasure(int width,int height){
        int max=Math.round(460*getResources().getDisplayMetrics().density);
        int size=Math.min(max,Math.min(MeasureSpec.getSize(width),MeasureSpec.getSize(height)));
        int spec=MeasureSpec.makeMeasureSpec(size,MeasureSpec.EXACTLY);
        super.onMeasure(spec,spec);
    }
}
