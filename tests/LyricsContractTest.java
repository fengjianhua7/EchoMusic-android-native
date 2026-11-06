package cc.echomusic.android;
import java.util.*;
public final class LyricsContractTest {
    static int checks=0;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        List<Lyrics.Line> l=Lyrics.parse("[ar:Artist]\n[offset:-100]\n[00:01.2][00:03.45]重复歌词\n[00:02.345]逐行歌词\n[00:61]invalid\n[00:05]尾声");
        check(l.size()==4,"timestamps, metadata and invalid seconds");
        check(l.get(0).millis==1100,"tenths and negative offset");
        check(l.get(1).millis==2245,"milliseconds and sort order");
        check(l.get(2).millis==3350 && l.get(2).text.equals("重复歌词"),"multiple timestamps and hundredths");
        check(Lyrics.current(l,1000)==-1,"before first line");
        check(Lyrics.current(l,1100)==0,"inclusive boundary");
        check(Lyrics.current(l,3350)==2,"exact seek");
        check(Lyrics.current(l,Integer.MAX_VALUE)==3,"after last line");
        check(Lyrics.parse("[offset:+250]\n[00:00]开始").get(0).millis==250,"positive offset");
        check(Lyrics.parse("[offset:-1000]\n[00:00.10]开始").get(0).millis==0,"negative time clamps");
        check(Lyrics.parse("plain text").isEmpty(),"untimed lyric fallback");
        check(Lyrics.current(Collections.emptyList(),0)==-1,"empty lyric safe");
        String krc="[offset:100]\n[1000,1200]<0,500,0>你<500,700,0>好\n[2300,800]<0,800,0>世界";
        List<Lyrics.Line> words=Lyrics.parse(krc);check(words.size()==2,"KRC lines parsed");check(words.get(0).text.equals("你好")&&words.get(0).millis==1100,"KRC text and file offset");check(words.get(0).words.size()==2,"real KRC word timing retained");check(words.get(0).words.get(1).start==1600&&words.get(0).words.get(1).end==2300,"KRC relative word times become absolute");check(Math.abs(words.get(0).words.get(0).progress(1350)-.5f)<.001,"karaoke word progress");check(words.get(0).words.get(0).progress(0)==0&&words.get(0).words.get(0).progress(9999)==1,"karaoke progress clamped");
        String language=new org.json.JSONObject().put("content",new org.json.JSONArray().put(new org.json.JSONObject().put("type",1).put("lyricContent",new org.json.JSONArray().put(new org.json.JSONArray().put("Hello")).put(new org.json.JSONArray().put("World")))).put(new org.json.JSONObject().put("type",0).put("lyricContent",new org.json.JSONArray().put(new org.json.JSONArray().put("ni").put("hao"))))).toString();
        String metadata="[language:"+java.util.Base64.getEncoder().encodeToString(language.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"]\n";
        List<Lyrics.Line> translated=Lyrics.parse(metadata+krc);check(translated.get(0).translation.equals("Hello")&&translated.get(1).translation.equals("World"),"language metadata translation by source line");check(translated.get(0).romanization.equals("nihao"),"romanization tokens retained");
        List<Lyrics.Line> multi=Lyrics.parse(metadata+"[00:01][00:05]first\n[00:03]second");check(multi.get(2).translation.equals("Hello")&&multi.get(1).translation.equals("World"),"translation applied before repeated LRC timestamps expand");
        List<Lyrics.Line> enhanced=Lyrics.parse("[00:01]<00:01.00>Hello <00:02.50>world");check(enhanced.get(0).text.equals("Hello world")&&enhanced.get(0).words.size()==2,"enhanced LRC word tags parsed");check(enhanced.get(0).words.get(0).end==2500,"enhanced LRC uses next timestamp as word end");
        List<Lyrics.Line> repeat=Lyrics.parse("[00:01][00:10]<00:01.00>A<00:02.00>B");check(repeat.get(1).words.get(0).start==10000&&repeat.get(1).words.get(1).start==11000,"enhanced timing shifted for repeated timestamps");
        List<Lyrics.Line> duplicate=Lyrics.parse("[00:01]original\n[00:01]translation");check(duplicate.size()==1&&duplicate.get(0).text.equals("original")&&duplicate.get(0).translation.equals("translation"),"same-time secondary line does not hide original");
        check(Lyrics.parse("[9999999999999999999:00]bad").isEmpty(),"huge timestamp cannot overflow into the start");check(!Lyrics.document("[00:01]plain line").karaoke,"ordinary LRC does not invent word timing");check(Lyrics.document("[ar:Artist]\nuntimed lyrics").plain.equals("untimed lyrics"),"plain text omits metadata");check(Lyrics.parse("[language:bad-base64]\n[00:01]original").size()==1,"malformed language metadata preserves original lyric");
        java.io.ByteArrayOutputStream compressed=new java.io.ByteArrayOutputStream();try(java.util.zip.DeflaterOutputStream zip=new java.util.zip.DeflaterOutputStream(compressed)){zip.write(krc.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        byte[] payload=compressed.toByteArray();int[] key={64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105};for(int i=0;i<payload.length;i++)payload[i]=(byte)(payload[i]^key[i%16]);java.io.ByteArrayOutputStream binary=new java.io.ByteArrayOutputStream();binary.write(new byte[]{'k','r','c','1'});binary.write(payload);check(Lyrics.decodeFile(binary.toByteArray()).equals(krc),"local binary KRC import decodes");
        check(Lyrics.decodeFile("歌词".getBytes(java.nio.charset.Charset.forName("GB18030"))).equals("歌词"),"GB18030 local LRC fallback");
        System.out.println("PASS: "+checks+" lyric timing checks");
    }
}
