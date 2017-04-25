package com.hanyee.recorder.helper;

import android.content.Context;
import android.os.PowerManager;

public class WakeLockHelper {

    private static PowerManager.WakeLock sWakeLock;

    public static void acquire(Context ctx, String tag) {
        if (sWakeLock == null) {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            sWakeLock = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, tag);
            sWakeLock.acquire();
        }
    }

    public static void release() {
        if (sWakeLock != null) {
            sWakeLock.release();
            sWakeLock = null;
        }
    }
}
