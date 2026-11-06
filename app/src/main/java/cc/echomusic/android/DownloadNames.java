package cc.echomusic.android;

import java.util.Locale;
import java.util.UUID;

final class DownloadNames {
    static String filename(String title,String artist,String urlPath){
        String name=(artist.isEmpty()?title:artist+" - "+title).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").replaceAll("[. ]+$","").trim();
        if(name.isEmpty()||name.equals(".")||name.equals(".."))name="音乐";
        if(name.length()>90)name=name.substring(0,90);
        String extension="mp3";String path=urlPath==null?"":urlPath.toLowerCase(Locale.ROOT);for(String format:new String[]{"mp3","flac","m4a","aac","ogg","opus","wav"})if(path.endsWith("."+format)){extension=format;break;}
        return name+"-"+UUID.randomUUID().toString().substring(0,8)+"."+extension;
    }
}
