package cc.echomusic.android;

/** Owns asynchronous dialog close/reopen transitions; old callbacks cannot close a new window. */
final class LyricWindowState {
    private long sequence,active;
    private boolean requested,closing;
    void requestOpen(){requested=true;}
    long opened(){closing=false;return active=++sequence;}
    boolean current(long token){return active==token&&token!=0;}
    boolean rebuild(){if(active==0||closing)return false;closing=true;return true;}
    void hide(){requested=false;closing=false;}
    boolean requested(){return requested;}
    boolean dismissed(long token){if(!current(token))return false;boolean reopen=closing&&requested;active=0;closing=false;if(!reopen)requested=false;return reopen;}
}
