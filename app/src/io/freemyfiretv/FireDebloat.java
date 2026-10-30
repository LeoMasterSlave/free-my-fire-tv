package io.freemyfiretv;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fire TV, experimental and opt-in: turns off (never uninstalls) Amazon packages the person picks one at a time,
 * the same mechanism as {@link FireReplace} (`pm disable-user` through the TV's own ADB) but for extra apps rather
 * than the launcher.
 *
 * Unlike a debloat list copied from a guide, which may be for an older device or Fire OS version (the person's own
 * concern, and a fair one), the list offered here is read from the TV itself: every entry is a package this app
 * found actually installed, minus a short, fixed list this app refuses to touch because the rest of the app (or the
 * TV) depends on it. Nothing is assumed "safe": the person sees the real name and decides, and `pm enable` always
 * undoes it. Not yet tried on a stick.
 */
final class FireDebloat {
    private FireDebloat() {}

    private static final String FILE = "fire-debloat.json";

    /** Never offered, on any TV: this app's own package, Amazon's launcher (that is {@link FireReplace}'s job), and
     * packages the rest of the app or the system depends on to keep working. */
    private static final Set<String> KEEP = new HashSet<>(Arrays.asList(
            FireReplace.LAUNCHER,
            "com.amazon.venezia",                 // the Appstore: needed to install or update anything, including this app
            "com.amazon.tv.settings.v2",          // Amazon's settings app: its pages are offered as shortcuts, not removed
            "com.amazon.tv.settings.core",
            "com.amazon.device.software.ota",     // system updates
            "com.amazon.tv.frameworkpackagestubs",
            "com.amazon.hedwig",                  // push/notifications plumbing some Amazon apps rely on
            "com.amazon.device.controllermanager", // pairing Bluetooth remotes and controllers
            "com.amazon.tv.inputmethod",          // the on-screen/remote keyboard
            "com.amazon.bueller.photos"            // used by the screensaver and some Amazon photo widgets; see docs
    ));

    /** Every installed Amazon package this TV has, minus {@link #KEEP} and this app itself: real names from this
     * device, sorted so the same list reads the same way each time. */
    static List<String> candidates(Context c) {
        List<String> out = new ArrayList<>();
        PackageManager pm = c.getPackageManager();
        for (ApplicationInfo a : pm.getInstalledApplications(0)) {
            if (a.packageName == null || !a.packageName.startsWith("com.amazon.")) continue;
            if (KEEP.contains(a.packageName) || a.packageName.equals(c.getPackageName())) continue;
            out.add(a.packageName);
        }
        Collections.sort(out);
        return out;
    }

    /** A package's display name on this TV ("Amazon Music"), or the package name itself if Android won't say. */
    static String label(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        try {
            ApplicationInfo a = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(a);
            return l == null || l.length() == 0 ? pkg : l.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static boolean isOff(Context c, String pkg) {
        return disabled(c).contains(pkg);
    }

    static String lastError(Context c) {
        return FireHome.read(c, FILE).optString("error");
    }

    /** Every package this app has turned off (read once per draw, not once per row). */
    static Set<String> disabled(Context c) {
        return disabledByUs(c);
    }

    private static Set<String> disabledByUs(Context c) {
        JSONArray a = FireHome.read(c, FILE).optJSONArray("disabled");
        Set<String> s = new HashSet<>();
        if (a != null) for (int i = 0; i < a.length(); i++) {
            String p = a.optString(i, "");
            if (!p.isEmpty()) s.add(p);
        }
        return s;
    }

    private static void save(Context c, Set<String> disabled, String error) {
        JSONObject o = FireHome.read(c, FILE);
        try {
            o.put("disabled", new JSONArray(disabled));
            o.put("error", error == null ? "" : error);
            o.put("at", System.currentTimeMillis());
        } catch (JSONException ignored) {
        }
        FireHome.write(c, FILE, o);
    }

    static void requestToggle(Context c, String pkg, boolean off) {
        ModeJob.schedule(c, ModeJob.FIRE_DEBLOAT, (off ? "off:" : "on:") + pkg, 0);
    }

    /** Main process, from ModeJob. `mode` is "off:&lt;pkg&gt;" or "on:&lt;pkg&gt;". */
    static void toggleNow(Context app, String mode) {
        boolean off = mode.startsWith("off:");
        String pkg = mode.substring(mode.indexOf(':') + 1);
        if (pkg.isEmpty() || KEEP.contains(pkg) || pkg.equals(app.getPackageName())) return;
        String error = null;
        try (Adb adb = Adb.connect(app, 120_000,
                () -> FireHome.step(app, Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”).")))) {
            FireHome.step(app, off ? Ui.tr("Turning off…") : Ui.tr("Turning back on…"));
            String reply = adb.shell((off ? "pm disable-user --user 0 " : "pm enable ") + pkg);
            AppLog.w((off ? "pm disable-user " : "pm enable ") + pkg + ": " + reply.trim());
            if (off && (reply.contains("protected package") || reply.contains("SecurityException"))) {
                error = Ui.trf("Fire OS protects {pkg}: it can't be turned off on this TV.", "pkg", pkg);
            } else {
                Set<String> s = disabledByUs(app);
                if (off) s.add(pkg); else s.remove(pkg);
                save(app, s, "");
            }
        } catch (IOException e) {
            error = e.getMessage() == null ? "error" : e.getMessage();
            AppLog.w("couldn't change " + pkg + ": " + error);
        } finally {
            FireHome.step(app, null);
        }
        if (error != null) save(app, disabledByUs(app), error);
    }

    /** Puts every package this app turned off back on (Restore uses this too, so nothing is left off behind). */
    static void restoreAllNow(Context app) {
        Set<String> s = disabledByUs(app);
        if (s.isEmpty()) return;
        try (Adb adb = Adb.connect(app, 15_000, null)) {
            for (String pkg : s) {
                try {
                    adb.shell("pm enable " + pkg);
                } catch (IOException e) {
                    AppLog.w("couldn't re-enable " + pkg + ": " + e.getMessage());
                }
            }
            save(app, new HashSet<>(), "");
        } catch (IOException e) {
            AppLog.w("couldn't reach the TV's ADB to restore debloated apps: " + e.getMessage());
        }
    }
}
