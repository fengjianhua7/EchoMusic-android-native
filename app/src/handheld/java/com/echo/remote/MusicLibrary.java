package com.echo.remote;
import cc.echomusic.android.R;

import android.content.Context;
import org.json.*;
import java.io.IOException;

/** Native collections only: never report a phone/plugin-only save as desktop favorite. */
final class MusicLibrary {
    private final RemoteApi api;
    MusicLibrary(Context context,RemoteApi api){this.api=api;}
    static boolean handles(String action){
        return java.util.Arrays.asList("favorite","createPlaylist","deletePlaylist","playlistAdd","playlistRemove","playLibrary").contains(action);
    }
    boolean isLocal(){return false;}
    void remember(JSONArray ignored){}
    synchronized JSONObject view()throws Exception{
        JSONObject data;
        try{data=api.request("/api/library",null);}
        catch(RemoteApi.ApiException e){
            if(e.unsupported())throw new IOException("请安装电脑插件 1.7.0 完整包，当前电脑端不支持原生收藏同步",e);
            throw e;
        }
        if(!data.optBoolean("nativeSync")||!"echomusic".equals(data.optString("source")))
            throw new IOException("请更新电脑插件到 1.7.0；当前接口保存的是独立收藏，无法同步电脑的我喜欢");
        if(data.optJSONArray("favorites")==null||data.optJSONArray("playlists")==null)throw new IOException("电脑歌单数据无效");
        return data;
    }
    synchronized JSONObject command(JSONObject body)throws Exception{
        JSONObject data=view();
        if(!data.optBoolean("loggedIn"))throw new IOException("请先在电脑 EchoMusic 登录账号");
        return api.request("/api/command",body);
    }
}
