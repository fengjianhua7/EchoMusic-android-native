package cc.echomusic.android;
import org.json.*;
import java.text.Normalizer;
import java.util.*;
public final class LyricChoice {
    public final String id,key,title,artist;public final int duration;
    public LyricChoice(JSONObject data){id=AuthProtocol.text(data,"id");key=AuthProtocol.text(data,"accesskey");title=AuthProtocol.text(data,"song","songname","name");artist=AuthProtocol.text(data,"singer","singername");duration=data.optInt("duration",0);}
    static String normalized(String value){return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]","");}
    public int score(Song song,int durationMs){String a=normalized(title),b=normalized(song.title),s=normalized(artist),artistWanted=normalized(song.artist);int score=a.equals(b)&&!a.isEmpty()?100:!a.isEmpty()&&!b.isEmpty()&&(a.contains(b)||b.contains(a))?40:0;if(!s.isEmpty()&&!artistWanted.isEmpty()&&(s.contains(artistWanted)||artistWanted.contains(s)))score+=30;if(duration>0&&durationMs>0)score-=Math.min(50,Math.abs((long)duration-durationMs)/1000);return score;}
    public String label(){return (title.isEmpty()?"歌词候选 "+id:title)+(artist.isEmpty()?"":" · "+artist);}
}
