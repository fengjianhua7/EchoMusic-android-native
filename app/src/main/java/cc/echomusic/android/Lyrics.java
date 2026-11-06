package cc.echomusic.android;

import java.util.*;
import java.util.regex.*;
import java.nio.*;
import java.nio.charset.*;
import java.io.*;
import java.util.zip.*;
import org.json.*;

/** LRC, enhanced LRC and decoded KRC. Only real word timings produce karaoke spans. */
public final class Lyrics {
    public static final class Word {
        public final int start,end,from,to;
        Word(int start,int end,int from,int to){this.start=start;this.end=Math.max(start+1,end);this.from=from;this.to=to;}
        public float progress(int position){return Math.max(0f,Math.min(1f,(position-start)/(float)Math.max(1,end-start)));}
    }
    public static final class Line {
        public final int millis,end;public final String text,translation,romanization;public final List<Word> words;
        Line(int millis,String text){this(millis,millis+3000,text,"","",Collections.emptyList());}
        Line(int millis,int end,String text,String translation,String roman,List<Word> words){this.millis=millis;this.end=end;this.text=text;this.translation=translation;romanization=roman;this.words=Collections.unmodifiableList(new ArrayList<>(words));}
        Line secondary(String translated,String roman){return new Line(millis,end,text,translated,roman,words);}
    }
    public static final class Document {
        public final String raw,plain;public final List<Line> lines;public final boolean karaoke;
        Document(String raw,String plain,List<Line> lines){this.raw=raw;this.plain=plain;this.lines=Collections.unmodifiableList(lines);boolean any=false;for(Line line:lines)any|=!line.words.isEmpty();karaoke=any;}
    }
    private static final Pattern TIME=Pattern.compile("\\[(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern KRC=Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern WORD=Pattern.compile("<(\\d+),(\\d+),[^>]*>([^<]*)");
    private static final Pattern INLINE=Pattern.compile("<(\\d+):(\\d{1,2})(?:[.:](\\d{1,3}))?>");
    private static int clamp(long value){return (int)Math.max(0,Math.min(Integer.MAX_VALUE-1,value));}
    private static long timestamp(String min,String sec,String fraction){try{long minutes=Long.parseLong(min),seconds=Long.parseLong(sec);if(minutes>Integer.MAX_VALUE/60000L+1||seconds>=60)return -1;long f=fraction==null?0:Integer.parseInt(fraction)*(fraction.length()==1?100:fraction.length()==2?10:1);long result=minutes*60000+seconds*1000+f;return result>Integer.MAX_VALUE?-1:result;}catch(NumberFormatException e){return -1;}}
    public static List<Line> parse(String source){return document(source).lines;}
    public static Document document(String source){
        String raw=source==null?"":source.replace("\uFEFF","").replace("&apos;","'").replace("&quot;","\"").replace("&amp;","&");
        long offset=0;Matcher off=Pattern.compile("\\[offset:([+-]?\\d+)\\]",Pattern.CASE_INSENSITIVE).matcher(raw);if(off.find())try{offset=Long.parseLong(off.group(1));offset=Math.max(-Integer.MAX_VALUE,Math.min(Integer.MAX_VALUE,offset));}catch(NumberFormatException ignored){}
        JSONArray translated=null,romanized=null;
        Matcher metadata=Pattern.compile("\\[language:([^\\]]+)\\]").matcher(raw);
        if(metadata.find())try{JSONObject language=new JSONObject(new String(Base64.getDecoder().decode(metadata.group(1)),StandardCharsets.UTF_8));JSONArray content=language.optJSONArray("content");if(content!=null)for(int i=0;i<content.length();i++){JSONObject part=content.optJSONObject(i);if(part==null)continue;if(part.optInt("type",-1)==1)translated=part.optJSONArray("lyricContent");if(part.optInt("type",-1)==0)romanized=part.optJSONArray("lyricContent");}}catch(Exception ignored){}
        List<Line> parsed=new ArrayList<>();StringBuilder plain=new StringBuilder();int sourceIndex=0;
        for(String row:raw.split("\\r?\\n")){
            Matcher krc=KRC.matcher(row.trim());
            if(krc.matches()){
                try{
                    long start=Long.parseLong(krc.group(1)),length=Long.parseLong(krc.group(2));if(start>Integer.MAX_VALUE||length>Integer.MAX_VALUE)continue;
                    String body=krc.group(3);Matcher words=WORD.matcher(body);List<Word> timings=new ArrayList<>();StringBuilder text=new StringBuilder();
                    while(words.find()){String token=words.group(3);int from=text.length();text.append(token);long relative=Long.parseLong(words.group(1)),duration=Long.parseLong(words.group(2));if(relative>Integer.MAX_VALUE||duration>Integer.MAX_VALUE)continue;timings.add(new Word(clamp(start+relative+offset),clamp(start+relative+Math.max(1,duration)+offset),from,text.length()));}
                    if(timings.isEmpty())text.append(body.replaceAll("<[^>]*>",""));
                    String content=text.toString();if(!content.isEmpty()){int time=timings.isEmpty()?clamp(start+offset):timings.get(0).start;parsed.add(new Line(time,clamp(start+length+offset),content,secondary(translated,sourceIndex,false),secondary(romanized,sourceIndex,true),timings));sourceIndex++;}
                }catch(NumberFormatException ignored){}continue;
            }
            Matcher time=TIME.matcher(row);List<Long> stamps=new ArrayList<>();int end=0;
            while(time.find()){long value=timestamp(time.group(1),time.group(2),time.group(3));if(value>=0){stamps.add(value);end=time.end();}}
            if(!stamps.isEmpty()){
                String body=row.substring(end);Matcher inline=INLINE.matcher(body);List<Long> starts=new ArrayList<>();List<String> tokens=new ArrayList<>();int previous=0;Long previousTime=null;
                while(inline.find()){if(previousTime!=null){tokens.add(body.substring(previous,inline.start()));starts.add(previousTime);}else if(inline.start()>0){tokens.add(body.substring(0,inline.start()));starts.add(stamps.get(0));}previousTime=timestamp(inline.group(1),inline.group(2),inline.group(3));previous=inline.end();}
                if(previousTime!=null){tokens.add(body.substring(previous));starts.add(previousTime);}
                for(long stamp:stamps){
                    if(tokens.isEmpty()){String content=body.trim();parsed.add(new Line(clamp(stamp+offset),clamp(stamp+offset+3000),content,secondary(translated,sourceIndex,false),secondary(romanized,sourceIndex,true),Collections.emptyList()));}
                    else{StringBuilder text=new StringBuilder();List<Word> words=new ArrayList<>();long delta=stamp-stamps.get(0);for(int i=0;i<tokens.size();i++){int from=text.length();text.append(tokens.get(i));long begin=starts.get(i),finish=i+1<starts.size()?starts.get(i+1):begin+1000;if(begin>=0)words.add(new Word(clamp(begin+delta+offset),clamp(Math.max(begin+1,finish)+delta+offset),from,text.length()));}parsed.add(new Line(clamp(stamp+offset),words.isEmpty()?clamp(stamp+offset+3000):words.get(words.size()-1).end,text.toString(),secondary(translated,sourceIndex,false),secondary(romanized,sourceIndex,true),words));}
                }sourceIndex++;
            }else if(!row.trim().matches("^\\[[^\\]]*\\]$")&&!row.trim().isEmpty()){if(plain.length()>0)plain.append('\n');plain.append(row.trim());}
        }
        parsed.sort(Comparator.comparingInt(line->line.millis));List<Line> merged=new ArrayList<>();
        for(Line line:parsed){if(!merged.isEmpty()&&merged.get(merged.size()-1).millis==line.millis){Line old=merged.get(merged.size()-1);if(!old.text.equals(line.text)){String extra=old.translation.isEmpty()?line.text:old.translation+"\n"+line.text;merged.set(merged.size()-1,old.secondary(extra,old.romanization));}}else merged.add(line);}
        return new Document(raw,plain.toString(),merged);
    }
    private static String secondary(JSONArray lines,int index,boolean join){if(lines==null)return "";JSONArray line=lines.optJSONArray(index);if(line==null)return "";StringBuilder text=new StringBuilder();int length=join?line.length():Math.min(1,line.length());for(int i=0;i<length;i++)text.append(line.optString(i,""));return text.toString().trim();}
    public static int current(List<Line> lines,int position){int low=0,high=lines.size()-1,found=-1;while(low<=high){int mid=(low+high)>>>1;if(lines.get(mid).millis<=position){found=mid;low=mid+1;}else high=mid-1;}return found;}
    public static String decodeFile(byte[] bytes)throws IOException{
        if(bytes.length>2*1024*1024)throw new IOException("歌词文件过大");
        if(bytes.length>=4&&bytes[0]=='k'&&bytes[1]=='r'&&bytes[2]=='c'&&bytes[3]=='1'){
            int[] key={64,71,97,119,94,50,116,71,81,54,49,45,206,210,110,105};byte[] compressed=Arrays.copyOfRange(bytes,4,bytes.length);for(int i=0;i<compressed.length;i++)compressed[i]=(byte)(compressed[i]^key[i%16]);
            try(InputStream input=new InflaterInputStream(new ByteArrayInputStream(compressed));ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){output.write(buffer,0,count);if(output.size()>2*1024*1024)throw new IOException("歌词解压后过大");}return new String(output.toByteArray(),StandardCharsets.UTF_8);}
        }
        if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254)return new String(bytes,StandardCharsets.UTF_16LE);
        if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255)return new String(bytes,StandardCharsets.UTF_16BE);
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(CharacterCodingException e){return new String(bytes,Charset.forName("GB18030"));}
    }
}
