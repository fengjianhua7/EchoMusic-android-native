package cc.echomusic.android;

public class LyricWindowStateTest {
    static int checks;
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    public static void main(String[] args){
        LyricWindowState state=new LyricWindowState();state.requestOpen();long landscape=state.opened();
        check(state.rebuild(),"first orientation change starts closing");
        check(!state.rebuild(),"repeated configuration callback cannot recursively close/reopen");
        check(state.current(landscape),"old window owns the close until async callback arrives");
        check(state.dismissed(landscape),"reopen only after old window closes");
        long portrait=state.opened();check(!state.current(landscape),"old window token is obsolete");
        check(!state.dismissed(landscape)&&state.current(portrait),"late old callback cannot clear new window");
        check(state.rebuild(),"portrait can rotate back");state.hide();
        check(!state.dismissed(portrait)&&!state.requested(),"manual dismissal cancels a pending reopen");
        state.requestOpen();long next=state.opened();check(!state.dismissed(next),"ordinary close does not reopen");
        check(!state.requested(),"ordinary close clears desired visibility");
        for(int i=0;i<100;i++){state.requestOpen();long token=state.opened();if(!state.rebuild()||!state.dismissed(token))throw new AssertionError("rapid rotation "+i);}checks++;
        state.hide();check(!state.requested(),"destruction prevents queued reopen");
        System.out.println("PASS: "+checks+" lyric-window lifecycle checks");
    }
}
