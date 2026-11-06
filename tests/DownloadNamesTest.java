package cc.echomusic.android;

public class DownloadNamesTest {
    private static int checks;
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args){
        String malicious=DownloadNames.filename("../song:<>|?*\n", "artist/../", "/audio/test.flac");
        check(!malicious.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*"),"filesystem control characters removed");
        check(malicious.endsWith(".flac"),"FLAC suffix preserved");
        check(DownloadNames.filename("歌曲","歌手","/audio/signed").endsWith(".mp3"),"default audio suffix");
        check(DownloadNames.filename("..","",null).startsWith("音乐-"),"empty dot-only names replaced");
        check(DownloadNames.filename("歌曲","", "/a.M4A").endsWith(".m4a"),"extension comparison ignores case");
        check(DownloadNames.filename("a".repeat(1000),"", "/a.ogg").length()<110,"oversize titles bounded");
        check(!DownloadNames.filename("歌曲","歌手","/a.mp3").equals(DownloadNames.filename("歌曲","歌手","/a.mp3")),"distinct files avoid overwriting an existing download");
        check(DownloadNames.filename("周深 - 璀璨冒险人","","/x.wav").startsWith("周深 - 璀璨冒险人-"),"Chinese titles remain readable");
        System.out.println("PASS: "+checks+" download filename checks");
    }
}
