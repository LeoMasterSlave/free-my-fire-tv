package io.freemyfiretv;

import android.content.ComponentName;
import android.content.Context;
import android.os.SystemClock;

import org.json.JSONObject;

import java.io.IOException;
import java.util.List;

/**
 * Fire TV, experimental and opt-in: turns Amazon's launcher off (never uninstalled) and makes this app the Home, so the
 * Home button and the power-on go straight to it and Amazon's home never shows.
 *
 * It is `pm disable-user --user 0 com.amazon.tv.launcher` through the TV's own ADB, the way Free My TV does it on Google
 * TV, and fully reversible: `pm enable` and the recorded home go back (restoreLauncherNow; Settings > Home Button >
 * Restore Amazon Home; the watchdog after 3 crashes; or on a computer, see README). Nothing is changed unless the
 * checks before pass, and everything is undone if the checks after don't. What stops working while it is off (Amazon's
 * settings screen, quick settings, ...) is documented, and unverified beyond the one stick it was tried on.
 */
final class FireReplace {
    private FireReplace() {}

    static final String LAUNCHER = "com.amazon.tv.launcher";
    static final String RESTORE_COMMAND = "adb shell pm enable " + LAUNCHER;
    private static final String FILE = "fire-replace.json";
    private static final Object LOCK = new Object();

    static boolean active(Context c) {
        return FireHome.read(c, FILE).optBoolean("active");
    }

    static String lastError(Context c) {
        return FireHome.read(c, FILE).optString("error");
    }

    static void requestEnable(Context c) {
        ModeJob.schedule(c, ModeJob.FIRE_REPLACE, null, 0);
    }

    private static ComponentName ours(Context c) {
        return new ComponentName(c, HomeActivity.class);
    }

    private static void save(Context c, boolean active, String originalHome, String error) {
        JSONObject o = FireHome.read(c, FILE);
        try {
            o.put("active", active);
            if (originalHome != null) o.put("originalHome", originalHome);
            o.put("error", error == null ? "" : error);
            o.put("at", System.currentTimeMillis());
        } catch (org.json.JSONException ignored) {
        }
        FireHome.write(c, FILE, o);
    }

    private static boolean launcherOff(Adb adb) throws IOException {
        return adb.shell("pm list packages -d").contains("package:" + LAUNCHER);
    }

    /** The component the system picks for Home now (last line of resolve-activity). */
    private static String homeNow(Adb adb) throws IOException {
        String[] lines = adb.shell("cmd package resolve-activity --brief --user 0 -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME").trim().split("\n");
        return lines[lines.length - 1].trim();
    }

    /** Main process, from ModeJob. */
    static void enableNow(Context app) {
        synchronized (LOCK) {
            FireHome.step(app, Ui.tr("Checking Amazon's Home…"));
            String error = null;
            try (Adb adb = Adb.connect(app, 120_000,
                    () -> FireHome.step(app, Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”).")))) {
                if (!adb.shell("pm path " + LAUNCHER).contains("package:")) {
                    throw new IOException(Ui.tr("Amazon's launcher isn't on this TV."));
                }
                List<ComponentName> homes = FireHome.amazonHomes(app);
                String original = homes.isEmpty() ? FireHome.read(app, FILE).optString("originalHome") : homes.get(0).flattenToShortString();
                if (original.isEmpty()) throw new IOException(Ui.tr("Amazon's home screen: not found."));
                // 1. Free My Fire TV becomes the default Home. If that doesn't work, nothing else is touched.
                FireHome.step(app, Ui.tr("Making Free My Fire TV the Home…"));
                String set = adb.shell("cmd package set-home-activity '" + ours(app).flattenToShortString() + "'");
                AppLog.w("set-home-activity: " + set.trim());
                if (!set.contains("Success")) throw new IOException(Ui.tr("Android didn't accept Free My Fire TV as the Home."));
                // 2. The way back is written down before anything is turned off.
                save(app, true, original, "");
                // 3. Amazon's launcher off (not removed).
                FireHome.step(app, Ui.tr("Turning off Amazon's Home…"));
                String off = adb.shell("pm disable-user --user 0 " + LAUNCHER);
                AppLog.w("pm disable-user " + LAUNCHER + ": " + off.trim());
                if (off.contains("protected package") || off.contains("SecurityException")) {
                    // Fire OS 7.7.1.7 refuses to change this package at all ("Cannot disable a protected package").
                    undo(adb, app, original);
                    throw new IOException(Ui.tr("Fire OS protects Amazon's launcher: it can't be turned off on this TV. Nothing was changed."));
                }
                // 4. Checked, not assumed: it is off, and Home now opens us. Otherwise everything goes back.
                FireHome.step(app, Ui.tr("Checking that Free My Fire TV is the Home…"));
                String now = homeNow(adb);
                if (!launcherOff(adb) || !now.contains(HomeMode.PKG)) {
                    AppLog.w("replace check failed: off=" + launcherOff(adb) + ", home=" + now);
                    undo(adb, app, original);
                    throw new IOException(Ui.tr("The TV didn't accept it, so Amazon's Home was put back.") + " " + off.trim());
                }
                AppLog.w("Amazon's launcher is off; Home is " + now);
            } catch (IOException e) {
                error = e.getMessage() == null ? "error" : e.getMessage();
                AppLog.w("couldn't turn off Amazon's Home: " + error);
                // A failure after the launcher went off must not leave it off.
                if (active(app)) restoreLauncherNow(app);
                save(app, false, null, error);
            } finally {
                FireHome.step(app, null);
            }
        }
    }

    private static void undo(Adb adb, Context app, String original) throws IOException {
        adb.shell("pm enable " + LAUNCHER);
        if (!original.isEmpty()) adb.shell("cmd package set-home-activity '" + original + "'");
    }

    /**
     * Puts Amazon's launcher and its Home back, if this app turned it off. Never touches it otherwise. True when
     * nothing is left off by us. Used by Restore, the watchdog and a failed turn-off.
     */
    static boolean restoreLauncherNow(Context app) {
        synchronized (LOCK) {
            JSONObject s = FireHome.read(app, FILE);
            try (Adb adb = Adb.connect(app, 15_000, null)) {
                boolean off = launcherOff(adb);
                if (!s.optBoolean("active") && !off) return true;
                if (!s.optBoolean("active")) return true;   // off, but not by us: left alone
                String original = s.optString("originalHome");
                if (original.isEmpty()) {
                    List<ComponentName> homes = FireHome.amazonHomes(app);
                    if (!homes.isEmpty()) original = homes.get(0).flattenToShortString();
                }
                adb.shell("pm enable " + LAUNCHER);
                if (!original.isEmpty()) adb.shell("cmd package set-home-activity '" + original + "'");
                SystemClock.sleep(500);
                boolean back = !launcherOff(adb);
                AppLog.w("Amazon's Home back: " + back + ", Home is " + homeNow(adb));
                if (back) save(app, false, null, "");
                return back;
            } catch (IOException e) {
                AppLog.w("couldn't bring Amazon's Home back: " + e.getMessage() + " (on a computer: " + RESTORE_COMMAND + ")");
                return false;
            }
        }
    }
}
