package cc.echomusic.android;

/** Node.js Mobile entry used only by the ARMv7 compatibility flavor. */
final class NativeNode {
    static {System.loadLibrary("echo_node");}
    static native int start(String script);
}
