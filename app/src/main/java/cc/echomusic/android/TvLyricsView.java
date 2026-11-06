package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Three full-width single lines, fitted to available TV width without truncation. */
final class TvLyricsView extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private Lyrics.Document document;private int position;
    TvLyricsView(Context context){super(context);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);}
    void display(Lyrics.Document doc,int time){document=doc;position=time;invalidate();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);
        int current=document==null?-1:Lyrics.current(document.lines,position);
        float density=getResources().getDisplayMetrics().density,slot=getHeight()/3f;
        if(document==null||document.lines.isEmpty()){paint.setColor(Color.WHITE);paint.setTextSize(30*density);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(document==null?"正在准备歌词…":"暂无同步歌词",getWidth()/2f,getHeight()/2f,paint);return;}
        for(int row=0;row<3;row++){
            int index=Math.max(0,current)+row-1;if(index<0||index>=document.lines.size())continue;
            Lyrics.Line line=document.lines.get(index);String text=line.text.isEmpty()?"♪":line.text;
            paint.setTypeface(Typeface.create(Typeface.DEFAULT,row==1?Typeface.BOLD:Typeface.NORMAL));
            paint.setTextAlign(Paint.Align.LEFT);float max=Math.min(slot*.55f,(row==1?76:64)*density);paint.setTextSize(max);
            float width=paint.measureText(text),available=Math.max(1,getWidth()-48*density);
            if(width>available)paint.setTextSize(max*available/width);
            width=paint.measureText(text);float x=(getWidth()-width)/2f,y=slot*(row+.5f)-(paint.ascent()+paint.descent())/2;
            paint.setColor(row==1&&current>=0?Color.WHITE:0xff98b3c4);canvas.drawText(text,x,y,paint);
            if(row==1&&current>=0)for(Lyrics.Word word:line.words){if(word.to<=word.from||word.to>text.length())continue;float left=x+paint.measureText(text,0,word.from),w=paint.measureText(text,word.from,word.to);int save=canvas.save();canvas.clipRect(left,0,left+w*word.progress(position),getHeight());paint.setColor(0xffa5f2f3);canvas.drawText(text,x,y,paint);canvas.restoreToCount(save);}
        }
    }
}
