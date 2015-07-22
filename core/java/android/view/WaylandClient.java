package android.view;

import android.util.Log;

public class WaylandClient {
    private static native long nativeCreateWindow(int width, int height);
    private static native void nativeConnect();
    private static Thread mDispatchThread;
    private static native int nativeDispatch();
    public static  void connect() {
        nativeConnect();

    }
    public static int dispatch() {
        return nativeDispatch();
    }
    public static void startDispatcher() {
        mDispatchThread = new Thread(new Runnable() {
            @Override
            public void run() {
                while(nativeDispatch() >= 0);
                Log.e("WaylandClient", "nativeDispatchPending failed");
            }
        });
       mDispatchThread.start();
    }

    public static WaylandWindow createWindow(int width, int height) {
        long ptr = nativeCreateWindow(width, height);
        return new WaylandWindow(ptr);
    }

};