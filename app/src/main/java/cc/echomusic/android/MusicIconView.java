package cc.echomusic.android;

import android.content.Context;
import android.graphics.*;
import android.widget.TextView;

/** One consistent 24-unit icon grid, independent of the device font. Touch-only UI. */
final class MusicIconView extends TextView {
    private String icon="";private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final float units;
    MusicIconView(Context context,int size){super(context);units=size;setGravity(android.view.Gravity.CENTER);}
    private static String type(String text){switch(text){case "▶":return "play";case "Ⅱ":return "pause";case "|◀":case "◀":return "previous";case "▶|":return "next";case "♡":case "♡·":return "heart";case "♥":return "heartFill";case "☷":return "queue";case "↻":return "repeat";case "↻₁":return "repeatOne";case "⇄":return "shuffle";case "↓":return "download";case "⌄":return "collapse";case "⋮":return "more";case "back":case "remote":case "home":case "search":case "library":case "settings":return text;default:return "";}}
    @Override public void setText(CharSequence text,BufferType buffer){String value=text==null?"":text.toString();icon=type(value);super.setText(icon.isEmpty()?text:"",buffer);invalidate();}
    @Override protected void onDraw(Canvas canvas){if(icon==null||icon.isEmpty()){super.onDraw(canvas);return;}float size=Math.min(Math.min(getWidth(),getHeight()),units*getResources().getDisplayMetrics().density);int save=canvas.save();canvas.translate((getWidth()-size)/2,(getHeight()-size)/2);canvas.scale(size/24,size/24);paint.setColor(getCurrentTextColor());paint.setStrokeWidth(1.7f);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);Path path=new Path();
        switch(icon){
            case "back":path.moveTo(15,5);path.lineTo(8,12);path.lineTo(15,19);canvas.drawPath(path,paint);break;
            case "play":paint.setStyle(Paint.Style.FILL);path.moveTo(7,4);path.lineTo(20,12);path.lineTo(7,20);path.close();canvas.drawPath(path,paint);break;
            case "pause":paint.setStyle(Paint.Style.FILL);canvas.drawRoundRect(new RectF(6,4,9,20),1,1,paint);canvas.drawRoundRect(new RectF(15,4,18,20),1,1,paint);break;
            case "previous":case "next":paint.setStyle(Paint.Style.FILL);boolean next=icon.equals("next");path.moveTo(next?5:19,5);path.lineTo(next?16:8,12);path.lineTo(next?5:19,19);path.close();canvas.drawPath(path,paint);canvas.drawRoundRect(new RectF(next?18:4,5,next?20:6,19),.7f,.7f,paint);break;
            case "heart":case "heartFill":path.moveTo(12,21);path.cubicTo(9,18,2,13,2,7.5f);path.cubicTo(2,1.5f,9,1,12,6);path.cubicTo(15,1,22,1.5f,22,7.5f);path.cubicTo(22,13,15,18,12,21);path.close();if(icon.equals("heartFill"))paint.setStyle(Paint.Style.FILL);canvas.drawPath(path,paint);break;
            case "queue":for(int y=5;y<=17;y+=6)canvas.drawLine(3,y,15,y,paint);path.moveTo(19,13);path.lineTo(23,16);path.lineTo(19,19);path.close();paint.setStyle(Paint.Style.FILL);canvas.drawPath(path,paint);break;
            case "repeat":case "repeatOne":canvas.drawLine(5,5,20,5,paint);canvas.drawLine(20,5,17,2,paint);canvas.drawLine(20,5,17,8,paint);path.moveTo(20,5);path.lineTo(20,15);path.quadTo(20,19,16,19);path.lineTo(4,19);canvas.drawPath(path,paint);canvas.drawLine(4,19,7,16,paint);canvas.drawLine(4,19,7,22,paint);canvas.drawLine(4,19,4,9,paint);if(icon.equals("repeatOne")){paint.setStyle(Paint.Style.FILL);paint.setTextSize(9);paint.setTextAlign(Paint.Align.CENTER);canvas.drawText("1",12,15,paint);}break;
            case "shuffle":canvas.drawLine(3,5,20,19,paint);canvas.drawLine(3,19,20,5,paint);canvas.drawLine(20,5,16,5,paint);canvas.drawLine(20,5,20,9,paint);canvas.drawLine(20,19,16,19,paint);canvas.drawLine(20,19,20,15,paint);break;
            case "download":canvas.drawLine(12,3,12,16,paint);canvas.drawLine(7,11,12,16,paint);canvas.drawLine(17,11,12,16,paint);path.moveTo(3,16);path.lineTo(3,21);path.lineTo(21,21);path.lineTo(21,16);canvas.drawPath(path,paint);break;
            case "collapse":path.moveTo(5,9);path.lineTo(12,16);path.lineTo(19,9);canvas.drawPath(path,paint);break;
            case "more":paint.setStyle(Paint.Style.FILL);for(int y=5;y<=19;y+=7)canvas.drawCircle(12,y,1.5f,paint);break;
            case "remote":canvas.drawRoundRect(new RectF(2,3,22,17),2,2,paint);canvas.drawLine(8,21,16,21,paint);canvas.drawLine(12,17,12,21,paint);canvas.drawLine(6,7,18,7,paint);canvas.drawLine(6,11,14,11,paint);break;
            case "home":path.moveTo(3,10);path.lineTo(12,3);path.lineTo(21,10);path.lineTo(21,21);path.lineTo(15,21);path.lineTo(15,14);path.lineTo(9,14);path.lineTo(9,21);path.lineTo(3,21);path.close();canvas.drawPath(path,paint);break;
            case "search":canvas.drawCircle(10,10,7,paint);canvas.drawLine(15,15,22,22,paint);break;
            case "library":canvas.drawRoundRect(new RectF(3,3,21,21),3,3,paint);canvas.drawLine(9,15,9,8,paint);canvas.drawLine(9,8,17,6,paint);canvas.drawLine(17,6,17,13,paint);paint.setStyle(Paint.Style.FILL);canvas.drawOval(new RectF(5,14,10,18),paint);canvas.drawOval(new RectF(13,12,18,16),paint);break;
            case "settings":canvas.drawCircle(12,12,7,paint);canvas.drawCircle(12,12,2.5f,paint);for(int i=0;i<8;i++){double angle=i*Math.PI/4;canvas.drawLine(12+(float)Math.cos(angle)*8,12+(float)Math.sin(angle)*8,12+(float)Math.cos(angle)*10.5f,12+(float)Math.sin(angle)*10.5f,paint);}break;
        }canvas.restoreToCount(save);
    }
}
