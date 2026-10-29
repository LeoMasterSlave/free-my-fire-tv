package io.freemyfiretv;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Free My Fire TV's Home button on Fire TV (Fire OS 7 and 8): the state it keeps, the guided setup, and the way back.
 *
 * Amazon's launcher keeps the Home button, so nothing here replaces it or turns anything off. {@link HomeRedirectService}
 * (an accessibility service) sees Amazon's home arrive and starts our home screen on top; {@link RedirectPolicy}
 * decides when. What changes on the TV is only: that service is listed in Android's enabled accessibility services and,
 * if the person lets the setup run it, the app holds WRITE_SECURE_SETTINGS. Both go back with "Restore Amazon Home".
 *
 * Nothing here is assumed to work: the setup says what it did, then checks it (is the service listed, did it connect)
 * and the settings screen only says "Working" after a redirect really happened (see {@link Snapshot#summary}).
 *
 * Files in files/, one writer each, read from any process:
 *  - fire-home.json     the person's choices: "enabled" (default on) and "pausedUntil" (wall clock): :home, and the
 *                       main process when our home screen keeps crashing;
 *  - fire-redirect.json what the service saw: connected, redirects, the launcher's window: the service only;
 *  - fire-setup.json    the last setup or restore: the setup job only.
 */
final class FireHome {
    private FireHome() {}

    static final String PERMISSION = "android.permission.WRITE_SECURE_SETTINGS";
    static final String SERVICES = "enabled_accessibility_services", ACCESSIBILITY = "accessibility_enabled";
    /** Asks the running service to switch itself off (it lists itself out of Android's settings with disableSelf). */
    static final String ACTION_OFF = HomeMode.PKG + ".FIRE_HOME_OFF";
    /** "Open Amazon Home" leaves it alone this long, or our own redirect would bring it straight back. */
    static final long AMAZON_PAUSE_MS = 60_000;
    /** The commands a person can run from a computer when the TV can't do it by itself. */
    static final String GRANT_COMMAND = "adb shell pm grant " + HomeMode.PKG + " " + PERMISSION;

    private static final Object RUN = new Object();   // setup and restore, one at a time (main process)
    private static final Object FILE = new Object();

    // MARK: - Files

    private static File file(Context c, String name) {
        return new File(c.getFilesDir(), name);
    }

    static JSONObject read(Context c, String name) {
        synchronized (FILE) {
            File f = file(c, name);
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) Math.min(f.length(), 16 * 1024)];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException e) {
                return new JSONObject();
            }
        }
    }

    static void write(Context c, String name, JSONObject o) {
        synchronized (FILE) {
            File tmp = file(c, name + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                AppLog.w("could not save " + name + ": " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(file(c, name))) AppLog.w("could not replace " + name);
        }
    }

    private static void put(JSONObject o, String key, Object value) {
        try {
            o.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    // MARK: - The person's choices

    /** The person's switch: whether Home is redirected (on until they turn it off). */
    static boolean wanted(Context c) {
        return read(c, "fire-home.json").optBoolean("enabled", true);
    }

    static void setWanted(Context c, boolean on) {
        JSONObject o = read(c, "fire-home.json");
        put(o, "enabled", on);
        write(c, "fire-home.json", o);
        if (on) Watchdog.clear(c);   // a new start: the crash count of the home screen starts over
    }

    /** The Home button is being redirected to us right now (the person wants it, and the service is listed). */
    static boolean redirecting(Context c) {
        return wanted(c) && serviceEnabled(c);
    }

    /** Wall-clock time until which the person asked to leave Amazon's home alone (0: not asked). */
    static long userPausedUntil(Context c) {
        return read(c, "fire-home.json").optLong("pausedUntil", 0);
    }

    static void pauseFor(Context c, long ms) {
        JSONObject o = read(c, "fire-home.json");
        put(o, "pausedUntil", System.currentTimeMillis() + ms);
        write(c, "fire-home.json", o);
    }

    // MARK: - Amazon's home screen

    /**
     * The home screens on this TV other than ours, best first (what Android's own Home button chooses between), found
     * by asking the system, so no package or activity name is assumed. The manifest's {@code <queries>} lets Android 11
     * (Fire OS 8) show them to us.
     */
    static List<ComponentName> amazonHomes(Context c) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> found = c.getPackageManager().queryIntentActivities(home, 0);
        List<ResolveInfo> others = new ArrayList<>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo != null && !HomeMode.PKG.equals(ri.activityInfo.packageName)) others.add(ri);
        }
        Collections.sort(others, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return b.priority - a.priority;
            }
        });
        List<ComponentName> out = new ArrayList<>();
        for (ResolveInfo ri : others) out.add(new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name));
        return out;
    }

    /** Whether a window (package, class) is one of `homes`: same package, and its activity class (or a "Home" one). */
    static boolean isAmazonHome(List<ComponentName> homes, String pkg, CharSequence cls) {
        if (pkg == null || cls == null) return false;
        String name = cls.toString();
        for (ComponentName h : homes) {
            if (!h.getPackageName().equals(pkg)) continue;
            if (h.getClassName().equals(name) || name.contains("Home")) return true;
        }
        return false;
    }

    /**
     * Opens Amazon's home screen, and leaves it alone for a minute (the redirect would bring ours straight back). Used
     * by the "Amazon Home" tile and the settings.
     */
    static boolean openAmazonHome(Context c) {
        List<ComponentName> homes = amazonHomes(c);
        if (homes.isEmpty()) {
            AppLog.w("Amazon's home screen: none found");
            return false;
        }
        pauseFor(c, AMAZON_PAUSE_MS);
        for (ComponentName h : homes) {
            try {
                c.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(h)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (ActivityNotFoundException | SecurityException e) {
                AppLog.w("couldn't open " + h.flattenToShortString() + ": " + e.getMessage());
            }
        }
        return false;
    }

    // MARK: - Android's accessibility settings

    static ComponentName serviceComponent(Context c) {
        return new ComponentName(c, HomeRedirectService.class);
    }

    /** The service is listed in Android's enabled services and accessibility is on. */
    static boolean serviceEnabled(Context c) {
        ContentResolver cr = c.getContentResolver();
        return RedirectPolicy.lists(Settings.Secure.getString(cr, SERVICES), serviceComponent(c).flattenToString())
                && Settings.Secure.getInt(cr, ACCESSIBILITY, 0) == 1;
    }

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private static void rememberAccessibilityWasOff(Context c, boolean off) {
        JSONObject o = read(c, "fire-setup.json");
        put(o, "accessibilityWasOff", off);
        write(c, "fire-setup.json", o);
    }

    /** With WRITE_SECURE_SETTINGS: lists the service, or takes it out, keeping every other entry. */
    private static void setListedDirect(Context c, boolean on) throws IOException {
        ContentResolver cr = c.getContentResolver();
        String current = Settings.Secure.getString(cr, SERVICES), flat = serviceComponent(c).flattenToString();
        String merged = RedirectPolicy.merge(current, flat, on);
        if (merged == null) throw new IOException(Ui.tr("another accessibility entry looks unusual, so the setting was left as it is"));
        try {
            // Only before the first listing: what accessibility was before us is what "restore" puts back.
            if (on && !RedirectPolicy.lists(current, flat)) rememberAccessibilityWasOff(c, Settings.Secure.getInt(cr, ACCESSIBILITY, 0) != 1);
            Settings.Secure.putString(cr, SERVICES, merged);
            if (on) {
                Settings.Secure.putInt(cr, ACCESSIBILITY, 1);
            } else if (merged.isEmpty() && read(c, "fire-setup.json").optBoolean("accessibilityWasOff")) {
                Settings.Secure.putInt(cr, ACCESSIBILITY, 0);
            }
        } catch (SecurityException e) {
            throw new IOException(Ui.tr("Android didn't allow the app to change that setting"));
        }
    }

    /** Through the TV's own ADB (as Free My TV always did): grants the permission and lists the service. */
    private static void enableViaAdb(Context c) throws IOException {
        try (Adb adb = Adb.connect(c, 120_000, () -> step(c, Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”).")))) {
            step(c, Ui.tr("Setting it up…"));
            // First the permission, so the app can list the service again by itself after an update (Android turns
            // accessibility services off when their app is updated). What it answers is checked, not assumed.
            String grant = adb.shell("pm grant " + HomeMode.PKG + " " + PERMISSION).trim();
            AppLog.w("pm grant WRITE_SECURE_SETTINGS: " + (grant.isEmpty() ? "no answer" : grant));
            String current = adb.shell("settings get secure " + SERVICES).trim();
            String merged = RedirectPolicy.merge(current, serviceComponent(c).flattenToString(), true);
            if (merged == null) throw new IOException(Ui.tr("another accessibility entry looks unusual, so the setting was left as it is"));
            rememberAccessibilityWasOff(c, !"1".equals(adb.shell("settings get secure " + ACCESSIBILITY).trim()));
            adb.shell("settings put secure " + SERVICES + " '" + merged + "'");
            adb.shell("settings put secure " + ACCESSIBILITY + " 1");
        }
    }

    // MARK: - Setup (main process, from ModeJob)

    /** What the setup is doing now, for the settings screen (fire-setup.json "busy"). */
    private static void step(Context c, String text) {
        JSONObject o = read(c, "fire-setup.json");
        put(o, "busy", text);
        put(o, "busyAt", SystemClock.elapsedRealtime());
        write(c, "fire-setup.json", o);
    }

    private static void finish(Context c, String result, String method, String error, boolean grantedNow) {
        JSONObject o = read(c, "fire-setup.json");
        o.remove("busy");
        put(o, "result", result);
        put(o, "method", method);
        put(o, "error", error == null ? "" : error);
        put(o, "permission", grantedNow);
        put(o, "at", System.currentTimeMillis());
        write(c, "fire-setup.json", o);
        AppLog.w("Home button setup: " + result + " via " + method + (error == null || error.isEmpty() ? "" : " (" + error + ")")
                + ", WRITE_SECURE_SETTINGS " + (grantedNow ? "held" : "not held"));
    }

    /** Waits for the service to say it connected after `since` (wall clock). */
    private static boolean waitConnected(Context c, long since, long maxMs) {
        long until = SystemClock.elapsedRealtime() + maxMs;
        while (true) {
            JSONObject r = read(c, "fire-redirect.json");
            if (r.optLong("connectedAt") >= since && r.optLong("connectedAt") > r.optLong("disconnectedAt")) return true;
            if (SystemClock.elapsedRealtime() >= until) return false;
            SystemClock.sleep(500);
        }
    }

    /**
     * Guided setup: lists the service by the easiest way that works here, then checks it. In order: already listed;
     * the app already holds WRITE_SECURE_SETTINGS; the TV's own ADB (the person accepts "Allow USB debugging?" once);
     * otherwise Android's Accessibility settings open and the exact command for a computer is shown. The result is one
     * of: running (listed and connected), listed-not-running, manual, failed. "Working" comes later, from a real
     * redirect.
     */
    static void setupNow(Context app) {
        synchronized (RUN) {
            long started = System.currentTimeMillis();
            step(app, Ui.tr("Checking…"));
            String method = "already", error = "";
            try {
                if (!serviceEnabled(app)) {
                    if (canWriteSecure(app)) {
                        method = "direct";
                        try {
                            setListedDirect(app, true);
                        } catch (IOException e) {
                            error = e.getMessage();
                        }
                    }
                    if (!serviceEnabled(app)) {
                        method = "adb";
                        try {
                            enableViaAdb(app);
                        } catch (IOException e) {
                            error = e.getMessage();
                        }
                    }
                }
                boolean listed = serviceEnabled(app);
                if (!listed) {
                    // Last way: Android's own screen (often blank on Fire OS: then the command is the way).
                    try {
                        app.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        AppLog.w("Accessibility settings didn't open: " + e.getMessage());
                    }
                    finish(app, "manual", "manual", error, canWriteSecure(app));
                    return;
                }
                step(app, Ui.tr("Checking that it runs…"));
                boolean connected = waitConnected(app, started, 12_000);
                finish(app, connected ? "running" : "listed-not-running", method, error, canWriteSecure(app));
            } catch (RuntimeException e) {
                finish(app, "failed", method, String.valueOf(e), canWriteSecure(app));
            }
        }
    }

    // MARK: - Way back (main process, from ModeJob)

    /**
     * Gives Amazon's Home button back: our switch goes off first (that alone ends the redirect, whatever else fails),
     * the service lists itself out (disableSelf needs no permission) and, where it can, the setting and the permission
     * the setup added are taken back. Amazon's own launcher was never touched.
     */
    static void restoreNow(Context app) {
        synchronized (RUN) {
            step(app, Ui.tr("Restoring…"));
            setWanted(app, false);
            app.sendBroadcast(new Intent(ACTION_OFF).setPackage(HomeMode.PKG));
            for (int i = 0; i < 10 && serviceEnabled(app); i++) SystemClock.sleep(300);
            String error = "";
            if (serviceEnabled(app)) {   // the service wasn't running to list itself out
                try {
                    if (canWriteSecure(app)) {
                        setListedDirect(app, false);
                    } else {
                        try (Adb adb = Adb.connect(app, 15_000, null)) {
                            String merged = RedirectPolicy.merge(adb.shell("settings get secure " + SERVICES).trim(),
                                    serviceComponent(app).flattenToString(), false);
                            if (merged == null) throw new IOException(Ui.tr("another accessibility entry looks unusual, so the setting was left as it is"));
                            adb.shell(merged.isEmpty() ? "settings delete secure " + SERVICES
                                    : "settings put secure " + SERVICES + " '" + merged + "'");
                        }
                    }
                } catch (IOException e) {
                    error = e.getMessage();
                }
            }
            boolean listed = serviceEnabled(app);
            finish(app, listed ? "restored-listed" : "restored", "restore", listed ? error : "", canWriteSecure(app));
            AppLog.w("Amazon Home restored: the redirect is off" + (listed ? "; the service is still listed in Android's settings" : ""));
            // Last, because taking the permission back may restart the app's processes: the switch and the setting are done.
            if (!listed && canWriteSecure(app)) {
                try (Adb adb = Adb.connect(app, 5_000, null)) {
                    adb.shell("pm revoke " + HomeMode.PKG + " " + PERMISSION);
                } catch (IOException e) {
                    AppLog.w("the permission stays (harmless): " + e.getMessage());
                }
            }
        }
    }

    /**
     * Android turns an app's accessibility service off when the app is updated. If the setup had worked, the person
     * still wants the redirect and the app holds the permission, list it again; otherwise nothing is changed.
     */
    static void repair(Context c) {
        if (!HomeMode.AMAZON) return;
        String result = read(c, "fire-setup.json").optString("result");
        boolean wasSetUp = result.equals("running") || result.equals("listed-not-running") || result.equals("already");
        if (!wasSetUp || !wanted(c) || serviceEnabled(c) || !canWriteSecure(c)) return;
        try {
            setListedDirect(c, true);
            AppLog.w("Home button service listed again (after an update)");
        } catch (IOException e) {
            AppLog.w("couldn't list the Home button service again: " + e.getMessage());
        }
    }

    /** Our home screen keeps crashing (Watchdog): stop redirecting to it, and say so. Nothing else is touched. */
    static void safeMode(Context app) {
        if (!wanted(app)) return;
        setWanted(app, false);
        HomeMode.notice(app, Ui.tr("Free My Fire TV's home screen crashed 3 times in a row, so the Home button is Amazon's again. Turn it back on in Settings > Home Button."));
    }

    static void requestSetup(Context c) {
        ModeJob.schedule(c, ModeJob.FIRE_SETUP, null, 0);
    }

    static void requestRestore(Context c) {
        ModeJob.schedule(c, ModeJob.FIRE_RESTORE, null, 0);
    }

    // MARK: - What the service saw (written by HomeRedirectService only)

    static void noteConnected(Context c, boolean connected) {
        JSONObject o = read(c, "fire-redirect.json");
        put(o, connected ? "connectedAt" : "disconnectedAt", System.currentTimeMillis());
        write(c, "fire-redirect.json", o);
    }

    static void noteHomeSeen(Context c, String pkg, String cls, String verdict, boolean redirected) {
        JSONObject o = read(c, "fire-redirect.json");
        put(o, "homeSeenAt", System.currentTimeMillis());
        put(o, "homeWindow", pkg + "/" + cls);
        put(o, "lastVerdict", verdict);
        if (redirected) {
            put(o, "redirects", o.optInt("redirects") + 1);
            put(o, "lastRedirectAt", System.currentTimeMillis());
        }
        write(c, "fire-redirect.json", o);
    }

    // MARK: - For the settings screen

    /** Everything the settings screen shows about the Home button, read in one go (small files; nothing blocks). */
    static final class Snapshot {
        boolean wanted, listed, connected, canWrite, amazonHomeFound;
        long pausedUntil, lastRedirectAt, homeSeenAt, setupAt;
        int redirects;
        String busy = "", result = "", error = "", amazonHome = "", homeWindow = "", lastVerdict = "";

        boolean paused() {
            return System.currentTimeMillis() < pausedUntil;
        }

        /** The row's value. "Working" only once a redirect has really happened. */
        String summary() {
            if (!busy.isEmpty()) return busy;
            if (!wanted) return Ui.tr("Off");
            if (!listed) return Ui.tr("Needs setup");
            if (!connected) return Ui.tr("Not running yet");
            if (paused()) return Ui.tr("Paused");
            return redirects > 0 ? Ui.tr("Working") : Ui.tr("Ready: press Home to test");
        }
    }

    /** `withHomes`: also ask the system for Amazon's home screen (a package query, so only where it is shown). */
    static Snapshot snapshot(Context c, boolean withHomes) {
        Snapshot s = new Snapshot();
        JSONObject home = read(c, "fire-home.json"), red = read(c, "fire-redirect.json"), setup = read(c, "fire-setup.json");
        s.wanted = home.optBoolean("enabled", true);
        s.pausedUntil = home.optLong("pausedUntil", 0);
        s.listed = serviceEnabled(c);
        s.connected = s.listed && red.optLong("connectedAt") > red.optLong("disconnectedAt");
        s.canWrite = canWriteSecure(c);
        s.redirects = red.optInt("redirects");
        s.lastRedirectAt = red.optLong("lastRedirectAt");
        s.homeSeenAt = red.optLong("homeSeenAt");
        s.homeWindow = red.optString("homeWindow");
        s.lastVerdict = red.optString("lastVerdict");
        s.result = setup.optString("result");
        s.error = setup.optString("error");
        s.setupAt = setup.optLong("at");
        long at = setup.optLong("busyAt"), now = SystemClock.elapsedRealtime();
        boolean stale = now < at || now - at > 3 * 60_000L;   // a job that died leaves its text behind
        s.busy = stale ? "" : setup.optString("busy");
        if (withHomes) {
            List<ComponentName> homes = amazonHomes(c);
            s.amazonHomeFound = !homes.isEmpty();
            s.amazonHome = homes.isEmpty() ? "" : homes.get(0).flattenToShortString();
        }
        return s;
    }
}
