package io.freemyfiretv;

import android.util.Log;

/** The app's log (adb logcat -s FreeMyFireTV). Never logs keys. */
final class AppLog {
    private AppLog() {}

    static void w(String message) {
        Log.i("FreeMyFireTV", message);
    }
}
