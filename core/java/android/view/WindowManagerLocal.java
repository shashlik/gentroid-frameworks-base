/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.view;

import android.hardware.display.DisplayManagerLocal;
import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.opengl.ManagedEGLContext;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.ArraySet;
import android.util.Log;

import android.view.Display;
import android.view.inputmethod.InputMethodManager;
import com.android.internal.util.FastPrintWriter;
import com.android.internal.view.IInputContext;
import com.android.internal.view.IInputMethodClient;
import com.android.internal.view.IInputMethodManager;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;

public class WindowManagerLocal implements WindowManager {
    private static final String TAG = "WindowManager";

    /**
     * The user is navigating with keys (not the touch screen), so
     * navigational focus should be shown.
     */
    public static final int RELAYOUT_RES_IN_TOUCH_MODE = 0x1;

    /**
     * This is the first time the window is being drawn,
     * so the client must call drawingFinished() when done
     */
    public static final int RELAYOUT_RES_FIRST_TIME = 0x2;

    /**
     * The window manager has changed the surface from the last call.
     */
    public static final int RELAYOUT_RES_SURFACE_CHANGED = 0x4;

    /**
     * The window manager is currently animating.  It will call
     * IWindow.doneAnimating() when done.
     */
    public static final int RELAYOUT_RES_ANIMATING = 0x8;

    /**
     * Flag for relayout: the client will be later giving
     * internal insets; as a result, the window will not impact other window
     * layouts until the insets are given.
     */
    public static final int RELAYOUT_INSETS_PENDING = 0x1;

    /**
     * Flag for relayout: the client may be currently using the current surface,
     * so if it is to be destroyed as a part of the relayout the destroy must
     * be deferred until later.  The client will call performDeferredDestroy()
     * when it is okay.
     */
    public static final int RELAYOUT_DEFER_SURFACE_DESTROY = 0x2;

    public static final int ADD_FLAG_APP_VISIBLE = 0x2;
    public static final int ADD_FLAG_IN_TOUCH_MODE = RELAYOUT_RES_IN_TOUCH_MODE;

    public static final int ADD_OKAY = 0;
    public static final int ADD_BAD_APP_TOKEN = -1;
    public static final int ADD_BAD_SUBWINDOW_TOKEN = -2;
    public static final int ADD_NOT_APP_TOKEN = -3;
    public static final int ADD_APP_EXITING = -4;
    public static final int ADD_DUPLICATE_ADD = -5;
    public static final int ADD_STARTING_NOT_NEEDED = -6;
    public static final int ADD_MULTIPLE_SINGLETON = -7;
    public static final int ADD_PERMISSION_DENIED = -8;
    public static final int ADD_INVALID_DISPLAY = -9;

    private static WindowManagerLocal sDefaultWindowManager;

    private final Display mDisplay;
    private Window mParentWindow;

    private final Object mLock = new Object();

    private final ArrayList<View> mViews = new ArrayList<View>();
    private final ArrayList<ViewRootImpl> mRoots = new ArrayList<ViewRootImpl>();
    private final ArrayList<WindowManager.LayoutParams> mParams =
            new ArrayList<WindowManager.LayoutParams>();
    private final ArraySet<View> mDyingViews = new ArraySet<View>();
    private boolean mNeedsEglTerminate;

    float mWindowAnimationScale = 1.0f;
    float mTransitionAnimationScale = 1.0f;
    float mAnimatorDurationScale = 1.0f;

    private Runnable mSystemPropertyUpdater;



    private WindowManagerLocal() {
        mDisplay = DisplayManagerLocal.getInstance().getRealDisplay(0);
    }

    public static WindowManagerLocal getInstance() {
        synchronized (WindowManagerLocal.class) {
            if (sDefaultWindowManager == null) {
                sDefaultWindowManager = new WindowManagerLocal();
            }
            return sDefaultWindowManager;
        }
    }

    @Override
    public Display getDefaultDisplay() {
        return mDisplay;
    }

    public float getAnimationScale(int which) {
        switch (which) {
            case 0: return mWindowAnimationScale;
            case 1: return mTransitionAnimationScale;
            case 2: return mAnimatorDurationScale;
        }
        return 0;
    }

    public String[] getViewRootNames() {
        synchronized (mLock) {
            final int numRoots = mRoots.size();
            String[] mViewRoots = new String[numRoots];
            for (int i = 0; i < numRoots; ++i) {
                mViewRoots[i] = getWindowName(mRoots.get(i));
            }
            return mViewRoots;
        }
    }

    public View getRootView(String name) {
        synchronized (mLock) {
            for (int i = mRoots.size() - 1; i >= 0; --i) {
                final ViewRootImpl root = mRoots.get(i);
                if (name.equals(getWindowName(root))) return root.getView();
            }
        }

        return null;
    }

    public ViewRootImpl getViewRoot(String name) {
        synchronized (mLock) {
            for (int i = mRoots.size() - 1; i >= 0; --i) {
                final ViewRootImpl root = mRoots.get(i);
                if (name.equals(getWindowName(root))) return root;
            }
        }

        return null;
    }

    public void addView(View view, ViewGroup.LayoutParams params,
                        Display display, Window parentWindow) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (display == null) {
            throw new IllegalArgumentException("display must not be null");
        }
        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }

        final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams)params;
        if (parentWindow != null) {
            parentWindow.adjustLayoutParamsForSubWindow(wparams);
        }

        ViewRootImpl root;
        View panelParentView = null;

        synchronized (mLock) {
            // Start watching for system property changes.
            if (mSystemPropertyUpdater == null) {
                mSystemPropertyUpdater = new Runnable() {
                    @Override public void run() {
                        synchronized (mLock) {
                            for (int i = mRoots.size() - 1; i >= 0; --i) {
                                mRoots.get(i).loadSystemProperties();
                            }
                        }
                    }
                };
                SystemProperties.addChangeCallback(mSystemPropertyUpdater);
            }

            int index = findViewLocked(view, false);
            if (index >= 0) {
                if (mDyingViews.contains(view)) {
                    // Don't wait for MSG_DIE to make it's way through root's queue.
                    mRoots.get(index).doDie();
                } else {
                    throw new IllegalStateException("View " + view
                            + " has already been added to the window manager.");
                }
                // The previous removeView() had not completed executing. Now it has.
            }

            // If this is a panel window, then find the window it is being
            // attached to for future reference.
            if (wparams.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                    wparams.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW) {
                final int count = mViews.size();
                for (int i = 0; i < count; i++) {
                    if (mRoots.get(i).mWindow.asBinder() == wparams.token) {
                        panelParentView = mViews.get(i);
                    }
                }
            }

            root = new ViewRootImpl(view.getContext(), display);
            wparams.flags |= WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
            view.setLayoutParams(wparams);

            mViews.add(view);
            mRoots.add(root);
            mParams.add(wparams);
        }

        // do this last because it fires off messages to start doing things
        try {
            root.setView(view, wparams, panelParentView);
        } catch (RuntimeException e) {
            // BadTokenException or InvalidDisplayException, clean up.
            synchronized (mLock) {
                final int index = findViewLocked(view, false);
                if (index >= 0) {
                    removeViewLocked(index, true);
                }
            }
            throw e;
        }
    }

    public void updateViewLayout(View view, ViewGroup.LayoutParams params) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        if (!(params instanceof WindowManager.LayoutParams)) {
            throw new IllegalArgumentException("Params must be WindowManager.LayoutParams");
        }

        final WindowManager.LayoutParams wparams = (WindowManager.LayoutParams)params;

        view.setLayoutParams(wparams);

        synchronized (mLock) {
            int index = findViewLocked(view, true);
            ViewRootImpl root = mRoots.get(index);
            mParams.remove(index);
            mParams.add(index, wparams);
            root.setLayoutParams(wparams, false);
        }
    }

    @Override
    public void addView(View view, ViewGroup.LayoutParams params) {
        addView(view, params, mDisplay, mParentWindow);
    }

    @Override
    public void removeView(View view) {
        removeView(view, false);
    }

    public void removeView(View view, boolean immediate) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }

        synchronized (mLock) {
            int index = findViewLocked(view, true);
            View curView = mRoots.get(index).getView();
            removeViewLocked(index, immediate);
            if (curView == view) {
                return;
            }

            throw new IllegalStateException("Calling with view " + view
                    + " but the ViewAncestor is attached to " + curView);
        }
    }

    public void closeAll(IBinder token, String who, String what) {
        synchronized (mLock) {
            int count = mViews.size();
            //Log.i("foo", "Closing all windows of " + token);
            for (int i = 0; i < count; i++) {
                //Log.i("foo", "@ " + i + " token " + mParams[i].token
                //        + " view " + mRoots[i].getView());
                if (token == null || mParams.get(i).token == token) {
                    ViewRootImpl root = mRoots.get(i);

                    //Log.i("foo", "Force closing " + root);
                    if (who != null) {
                        WindowLeaked leak = new WindowLeaked(
                                what + " " + who + " has leaked window "
                                        + root.getView() + " that was originally added here");
                        leak.setStackTrace(root.getLocation().getStackTrace());
                        Log.e(TAG, "", leak);
                    }

                    removeViewLocked(i, false);
                }
            }
        }
    }

    private void removeViewLocked(int index, boolean immediate) {
        ViewRootImpl root = mRoots.get(index);
        View view = root.getView();

        //if (view != null) {
        //    InputMethodManager imm = InputMethodManager.getInstance();
        //    if (imm != null) {
        //        imm.windowDismissed(mViews.get(index).getWindowToken());
        //    }
        //}
        boolean deferred = root.die(immediate);
        if (view != null) {
            view.assignParent(null);
            if (deferred) {
                mDyingViews.add(view);
            }
        }
    }

    void doRemoveView(ViewRootImpl root) {
        synchronized (mLock) {
            final int index = mRoots.indexOf(root);
            if (index >= 0) {
                mRoots.remove(index);
                mParams.remove(index);
                final View view = mViews.remove(index);
                mDyingViews.remove(view);
            }
        }
    }

    private int findViewLocked(View view, boolean required) {
        final int index = mViews.indexOf(view);
        if (required && index < 0) {
            throw new IllegalArgumentException("View=" + view + " not attached to window manager");
        }
        return index;
    }

    public void startTrimMemory(int level) {
        if (HardwareRenderer.isAvailable()) {
            // On low-end gfx devices we trim when memory is moderate;
            // on high-end devices we do this when low.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE
                    || (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE
                    && !ActivityManager.isHighEndGfx())) {
                // Destroy all hardware surfaces and resources associated to
                // known windows
                synchronized (mLock) {
                    for (int i = mRoots.size() - 1; i >= 0; --i) {
                        mRoots.get(i).destroyHardwareResources();
                    }
                }
                // Force a full memory flush
                mNeedsEglTerminate = true;
                HardwareRenderer.startTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE);
                return;
            }

            HardwareRenderer.startTrimMemory(level);
        }
    }

    public void endTrimMemory() {
        HardwareRenderer.endTrimMemory();

        if (mNeedsEglTerminate) {
            ManagedEGLContext.doTerminate();
            mNeedsEglTerminate = false;
        }
    }

    public void trimLocalMemory() {
        synchronized (mLock) {
            for (int i = mRoots.size() - 1; i >= 0; --i) {
                mRoots.get(i).destroyHardwareLayers();
            }
        }
    }

    public void dumpGfxInfo(FileDescriptor fd) {
        FileOutputStream fout = new FileOutputStream(fd);
        PrintWriter pw = new FastPrintWriter(fout);
        try {
            synchronized (mLock) {
                final int count = mViews.size();

                pw.println("Profile data in ms:");

                for (int i = 0; i < count; i++) {
                    ViewRootImpl root = mRoots.get(i);
                    String name = getWindowName(root);
                    pw.printf("\n\t%s", name);

                    HardwareRenderer renderer =
                            root.getView().mAttachInfo.mHardwareRenderer;
                    if (renderer != null) {
                        renderer.dumpGfxInfo(pw);
                    }
                }

                pw.println("\nView hierarchy:\n");

                int viewsCount = 0;
                int displayListsSize = 0;
                int[] info = new int[2];

                for (int i = 0; i < count; i++) {
                    ViewRootImpl root = mRoots.get(i);
                    root.dumpGfxInfo(info);

                    String name = getWindowName(root);
                    pw.printf("  %s\n  %d views, %.2f kB of display lists",
                            name, info[0], info[1] / 1024.0f);
                    HardwareRenderer renderer =
                            root.getView().mAttachInfo.mHardwareRenderer;
                    if (renderer != null) {
                        pw.printf(", %d frames rendered", renderer.getFrameCount());
                    }
                    pw.printf("\n\n");

                    viewsCount += info[0];
                    displayListsSize += info[1];
                }

                pw.printf("\nTotal ViewRootImpl: %d\n", count);
                pw.printf("Total Views:        %d\n", viewsCount);
                pw.printf("Total DisplayList:  %.2f kB\n\n", displayListsSize / 1024.0f);
            }
        } finally {
            pw.flush();
        }
    }

    private static String getWindowName(ViewRootImpl root) {
        return root.mWindowAttributes.getTitle() + "/" +
                root.getClass().getName() + '@' + Integer.toHexString(root.hashCode());
    }

    public void setStoppedState(IBinder token, boolean stopped) {
        synchronized (mLock) {
            int count = mViews.size();
            for (int i = 0; i < count; i++) {
                if (token == null || mParams.get(i).token == token) {
                    ViewRootImpl root = mRoots.get(i);
                    root.setStopped(stopped);
                }
            }
        }
    }

    public void reportNewConfiguration(Configuration config) {
        synchronized (mLock) {
            int count = mViews.size();
            config = new Configuration(config);
            for (int i=0; i < count; i++) {
                ViewRootImpl root = mRoots.get(i);
                root.requestUpdateConfiguration(config);
            }
        }
    }

    /** @hide */
    public void changeCanvasOpacity(IBinder token, boolean opaque) {
        if (token == null) {
            return;
        }
        synchronized (mLock) {
            for (int i = mParams.size() - 1; i >= 0; --i) {
                if (mParams.get(i).token == token) {
                    mRoots.get(i).changeCanvasOpacity(opaque);
                    return;
                }
            }
        }
    }

    @Override
    public void removeViewImmediate(View view) {
        removeView(view, true);
    }

    public static WindowSessionLocal getWindowSession() {
        return WindowSessionLocal.getInstance();
    }

    public static WindowSessionLocal peekWindowSession() { return WindowSessionLocal.getInstance(); }

    public void setEventDispatching(boolean enabled) { }

}

final class WindowLeaked extends AndroidRuntimeException {
    public WindowLeaked(String msg) {
        super(msg);
    }
}