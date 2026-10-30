package io.freemyfiretv;

/**
 * When to take the Home button on Fire TV: the rules behind {@link HomeRedirectService}, with no Android in them so they
 * run (and are tested) on a plain JVM: tests/RedirectPolicyTest.java.
 *
 * The idea, written for this project and independent of any other: Amazon keeps the Home button, so pressing it
 * always brings Amazon's launcher to the front. An accessibility service sees that window arrive and starts our home
 * screen on top. Nothing is replaced or disabled: Amazon's launcher is still the system's home screen.
 *
 * What counts is an <em>arrival</em>: Amazon's home window showing up after any other window. The launcher fires more
 * than one window event while it settles, and those are not new presses of Home.
 *
 * Ways out, so this can never trap anyone on our screen:
 *  - pressing Home again right after being redirected (ours was on screen, Amazon's comes back within
 *    {@link #ESCAPE_WINDOW_MS}) is taken as "I want Amazon's home": redirecting pauses for {@link #ESCAPE_PAUSE_MS};
 *  - more than {@link #LOOP_MAX} redirects in {@link #LOOP_WINDOW_MS} (ours failing to stay up) pauses it for
 *    {@link #LOOP_PAUSE_MS};
 *  - the person's own switch and pause (the "Open Amazon Home" tile) come in as arguments.
 */
final class RedirectPolicy {
    /** What to do with a window event. */
    enum Verdict {
        /** Not an arrival of Amazon's home: nothing to do. */
        IGNORE,
        /** Start our home screen now. */
        REDIRECT,
        /** The person turned the redirect off. */
        DISABLED,
        /** Paused: by the person (Open Amazon Home) or by the escape or the loop breaker. */
        PAUSED,
        /** Our home screen was just asked for and hasn't shown yet (Fire OS 7 holds it back): don't ask twice. */
        WAITING,
        /** Home pressed again right after a redirect: let Amazon's home stay. */
        ESCAPE,
        /** Redirecting keeps coming back (ours doesn't stay up): stop for a minute. */
        LOOP_BREAK
    }

    static final long ESCAPE_WINDOW_MS = 2_500, ESCAPE_PAUSE_MS = 20_000, DEDUPE_MS = 1_500,
            LOOP_WINDOW_MS = 10_000, LOOP_PAUSE_MS = 60_000;
    static final int LOOP_MAX = 4;

    private String lastPackage = "";
    private boolean redirected;   // a redirect is on record (none since the last escape or loop break)
    private long lastRedirectAt;
    private boolean oursSeen;      // our window appeared since the last redirect
    private long pausedUntil;      // set by the escape and the loop breaker
    private final long[] recent = new long[LOOP_MAX];
    private int recentCount;

    /**
     * @param now             a clock in milliseconds (any monotonic or wall clock, as long as it is the same one)
     * @param pkg             the package of the window that just came to the front
     * @param amazonHome      that window is Amazon's home screen
     * @param ours            that window is ours
     * @param enabled         the person's switch
     * @param userPausedUntil the person's own pause (same clock as `now`), 0 for none
     */
    Verdict onWindow(long now, String pkg, boolean amazonHome, boolean ours, boolean enabled, long userPausedUntil) {
        return onWindow(now, pkg, amazonHome, ours, enabled, userPausedUntil, Kind.OTHER);
    }

    /** What a window is, for the settings session: Amazon's settings, a neutral system window, or anything else. */
    enum Kind { SETTINGS, SYSTEM, OTHER }

    static final long SETTINGS_SESSION_MS = 30 * 60_000;
    private long settingsUntil;   // while Amazon's settings are in use: the redirect stays off

    /**
     * @param kind SETTINGS for Amazon's settings windows, SYSTEM for other Amazon/Android system windows (they neither
     *             start nor end a settings session), OTHER for everything else (our home, an app: ends the session)
     */
    Verdict onWindow(long now, String pkg, boolean amazonHome, boolean ours, boolean enabled, long userPausedUntil,
                     Kind kind) {
        if (pkg == null) pkg = "";
        // A settings session starts when Amazon's settings show up while the person's pause is running (the TV Settings
        // button sets it), lasts while they navigate Amazon's settings, and ends at the first window that isn't Amazon's.
        if (kind == Kind.SETTINGS && (now < userPausedUntil || now < settingsUntil)) settingsUntil = now + SETTINGS_SESSION_MS;
        else if (kind == Kind.OTHER || ours) settingsUntil = 0;
        boolean arrival = amazonHome && !pkg.equals(lastPackage);
        lastPackage = pkg;
        if (ours && redirected) oursSeen = true;
        if (!arrival) return Verdict.IGNORE;
        if (!enabled) return Verdict.DISABLED;
        if (now < userPausedUntil || now < pausedUntil || now < settingsUntil) return Verdict.PAUSED;

        if (redirected && now - lastRedirectAt < ESCAPE_WINDOW_MS) {
            if (oursSeen) {
                pausedUntil = now + ESCAPE_PAUSE_MS;
                redirected = false;
                recentCount = 0;
                return Verdict.ESCAPE;
            }
            if (now - lastRedirectAt < DEDUPE_MS) return Verdict.WAITING;
        }

        // Keep the redirects of the last LOOP_WINDOW_MS; a full list means ours isn't staying up.
        int kept = 0;
        for (int i = 0; i < recentCount; i++) if (now - recent[i] < LOOP_WINDOW_MS) recent[kept++] = recent[i];
        recentCount = kept;
        if (recentCount >= LOOP_MAX) {
            pausedUntil = now + LOOP_PAUSE_MS;
            redirected = false;
            recentCount = 0;
            return Verdict.LOOP_BREAK;
        }
        recent[recentCount++] = now;
        redirected = true;
        lastRedirectAt = now;
        oursSeen = false;
        return Verdict.REDIRECT;
    }

    /** The service connects this soon after the TV started: it came up with the system (a boot), not later. */
    static final long BOOT_WINDOW_MS = 180_000;

    /**
     * Whether the service should open our home screen as soon as it connects: only right after the TV started, so the
     * stick comes up on it even when it connected before Amazon's home showed (that one is then redirected as usual).
     * Never when the person turned the redirect off or paused it.
     */
    static boolean startAtBoot(long uptimeMs, boolean enabled, long now, long userPausedUntil) {
        return enabled && uptimeMs >= 0 && uptimeMs < BOOT_WINDOW_MS && now >= userPausedUntil;
    }

    /** When the escape or the loop breaker lets redirecting go on (0 when it isn't paused by them). */
    long pausedUntil() {
        return pausedUntil;
    }

    // MARK: - Pure helpers for the accessibility settings (tested too)

    /** "pkg/.Cls" and "pkg/pkg.Cls" are the same component: one spelling, so entries compare. */
    static String normalize(String component) {
        int slash = component.indexOf('/');
        if (slash <= 0 || slash == component.length() - 1) return component;
        String pkg = component.substring(0, slash), cls = component.substring(slash + 1);
        if (cls.startsWith(".")) cls = pkg + cls;
        else if (cls.indexOf('.') < 0) cls = pkg + "." + cls;
        return pkg + "/" + cls;
    }

    /** Whether the colon-separated enabled-services setting lists `component`. */
    static boolean lists(String setting, String component) {
        if (setting == null) return false;
        String want = normalize(component);
        for (String one : setting.split(":")) if (!one.isEmpty() && normalize(one).equals(want)) return true;
        return false;
    }

    /**
     * The enabled-services setting with `component` added or taken out, every other entry kept as it was. Null when an
     * entry isn't a plain component name (something unexpected: better not to rewrite the setting at all).
     */
    static String merge(String setting, String component, boolean on) {
        String want = normalize(component);
        StringBuilder out = new StringBuilder();
        if (setting != null && !setting.equals("null")) {
            for (String one : setting.split(":")) {
                if (one.isEmpty()) continue;
                if (!one.matches("[\\w.]+/[\\w.$]+")) return null;
                if (normalize(one).equals(want)) continue;
                if (out.length() > 0) out.append(':');
                out.append(one);
            }
        }
        if (on) {
            if (out.length() > 0) out.append(':');
            out.append(component);
        }
        return out.toString();
    }
}
