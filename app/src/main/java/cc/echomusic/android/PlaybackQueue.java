package cc.echomusic.android;
import java.util.*;
/** Queue edits preserve the selected item and carry a revision for stale dialog actions. */
public final class PlaybackQueue<T> {
    private final List<T> items=new ArrayList<>();private int index=-1;private long revision=0;
    public int size(){return items.size();}public int index(){return index;}public long revision(){return revision;}
    public T current(){return index>=0&&index<items.size()?items.get(index):null;}
    public List<T> snapshot(){return new ArrayList<>(items);}
    public void replace(List<T> songs,int at){items.clear();items.addAll(songs);index=items.isEmpty()?-1:Math.max(0,Math.min(at,items.size()-1));revision++;}
    public boolean select(int at,long expected){if(expected!=revision||at<0||at>=items.size())return false;index=at;revision++;return true;}
    public void select(int at){if(at>=0&&at<items.size()){index=at;revision++;}}
    public void append(T item){items.add(item);if(index<0)index=0;revision++;}
    public void insertNext(T item){if(index<0){append(item);return;}items.add(index+1,item);revision++;}
    public boolean remove(int at,long expected){if(expected!=revision||at<0||at>=items.size())return false;boolean selected=at==index;items.remove(at);if(items.isEmpty())index=-1;else if(at<index)index--;else if(index>=items.size())index=0;revision++;return selected;}
    public boolean move(int from,int to,long expected){if(expected!=revision||from<0||from>=items.size()||to<0||to>=items.size())return false;if(from==to)return true;T item=items.remove(from);items.add(to,item);if(index==from)index=to;else if(from<index&&to>=index)index--;else if(from>index&&to<=index)index++;revision++;return true;}
    public boolean matches(long expected){return revision==expected;}
    public void clear(){items.clear();index=-1;revision++;}
}
