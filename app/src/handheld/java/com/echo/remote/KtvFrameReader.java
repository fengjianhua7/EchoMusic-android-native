package com.echo.remote;
import cc.echomusic.android.R;
import java.io.*;import java.net.SocketTimeoutException;import java.util.function.BooleanSupplier;
/** Preserve frame boundaries across a brief stall, including a timeout after a partial read. */
final class KtvFrameReader {
 static void read(InputStream input,byte[] frame,BooleanSupplier closed)throws IOException{int offset=0,timeouts=0;while(offset<frame.length){if(closed.getAsBoolean())throw new IOException("KTV 已关闭");try{int n=input.read(frame,offset,frame.length-offset);if(n<0)throw new EOFException("电脑音频连接已关闭");if(n==0)continue;offset+=n;timeouts=0;}catch(SocketTimeoutException error){if(++timeouts>=3)throw error;}}}
}

