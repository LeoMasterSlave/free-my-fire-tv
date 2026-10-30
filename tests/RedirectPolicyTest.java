package io.freemyfiretv;

import io.freemyfiretv.RedirectPolicy.Verdict;

/**
 * Plain-JVM tests of the Home redirect rules (no Android, no JUnit): tools/check.sh and CI run
 *   javac -d out/test app/src/io/freemyfiretv/RedirectPolicy.java tests/RedirectPolicyTest.java && java -cp out/test io.freemyfiretv.RedirectPolicyTest
 * They check the rules, not the Fire TV: what Amazon's launcher really sends needs a device (docs/DEVICE-TESTS.md).
 */
public final class RedirectPolicyTest {
    private static int checks;

    private static void eq(Object expected, Object actual, String what) {
        checks++;
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError(what + ": expected " + expected + " but got " + actual);
    }

    private static final String LAUNCHER = "com.amazon.tv.launcher", OURS = "io.freemyfiretv", APP = "com.netflix.ninja";

    private static Verdict event(RedirectPolicy p, long t, String pkg) {
        return p.onWindow(t, pkg, pkg.equals(LAUNCHER), pkg.equals(OURS), true, 0);
    }

    public static void main(String[] args) {
        // Home pressed from an app: Amazon's home arrives -> redirect; its other window events right after are not new presses.
        RedirectPolicy p = new RedirectPolicy();
        eq(Verdict.IGNORE, event(p, 0, APP), "an app in front");
        eq(Verdict.REDIRECT, event(p, 1_000, LAUNCHER), "Home from an app");
        eq(Verdict.IGNORE, event(p, 1_100, LAUNCHER), "the launcher's second window event");
        eq(Verdict.IGNORE, event(p, 1_600, OURS), "our home shows");

        // Home pressed again within 2.5 s while ours is up: Amazon's home is wanted; stays out of the way for 20 s.
        eq(Verdict.ESCAPE, event(p, 2_500, LAUNCHER), "second Home press");
        eq(Verdict.IGNORE, event(p, 2_600, LAUNCHER), "still the launcher");
        eq(Verdict.IGNORE, event(p, 3_000, APP), "an app from Amazon's home");
        eq(Verdict.PAUSED, event(p, 5_000, LAUNCHER), "paused after the escape");
        eq(Verdict.IGNORE, event(p, 6_000, APP), "another app");
        eq(Verdict.REDIRECT, event(p, 2_500 + RedirectPolicy.ESCAPE_PAUSE_MS + 1, LAUNCHER), "redirect works again after the pause");

        // Fire OS 7 holds our start back: Amazon's home is seen again (via another window) before ours shows: not an escape.
        p = new RedirectPolicy();
        eq(Verdict.REDIRECT, event(p, 0, LAUNCHER), "first arrival");
        eq(Verdict.IGNORE, event(p, 200, "com.amazon.tv.quicksettings.ui"), "a system window");
        eq(Verdict.WAITING, event(p, 400, LAUNCHER), "arrival while ours is still being started");

        // Ours never stays up: after LOOP_MAX redirects in 10 s, stop for a minute.
        p = new RedirectPolicy();
        long t = 0;
        for (int i = 0; i < RedirectPolicy.LOOP_MAX; i++) {
            eq(Verdict.REDIRECT, event(p, t, LAUNCHER), "redirect " + (i + 1));
            eq(Verdict.IGNORE, event(p, t + 1, APP), "something else comes up");
            t += 1_600;
        }
        eq(Verdict.LOOP_BREAK, event(p, t, LAUNCHER), "the loop breaker");
        eq(Verdict.IGNORE, event(p, t + 1, APP), "another window");
        eq(Verdict.PAUSED, event(p, t + 5_000, LAUNCHER), "paused after the loop breaker");
        eq(Verdict.IGNORE, event(p, t + 5_100, APP), "another window");
        eq(Verdict.REDIRECT, event(p, t + RedirectPolicy.LOOP_PAUSE_MS + 10, LAUNCHER), "redirect after the minute");

        // The person's switch and pause.
        p = new RedirectPolicy();
        eq(Verdict.DISABLED, p.onWindow(0, LAUNCHER, true, false, false, 0), "switch off");
        p = new RedirectPolicy();
        eq(Verdict.PAUSED, p.onWindow(5_000, LAUNCHER, true, false, true, 65_000), "Open Amazon Home's pause");
        p.onWindow(6_000, APP, false, false, true, 65_000);
        eq(Verdict.REDIRECT, p.onWindow(70_000, LAUNCHER, true, false, true, 65_000), "after the pause");

        // Enabled-accessibility-services setting: keeps other services, matches either spelling, refuses odd entries.
        String ours = "io.freemyfiretv/io.freemyfiretv.HomeRedirectService";
        eq("com.talk/com.talk.Back:" + ours, RedirectPolicy.merge("com.talk/com.talk.Back", ours, true), "add to a list");
        eq(ours, RedirectPolicy.merge(null, ours, true), "add to nothing");
        eq(ours, RedirectPolicy.merge("null", ours, true), "add to 'null'");
        eq("com.talk/com.talk.Back", RedirectPolicy.merge("com.talk/com.talk.Back:" + ours, ours, false), "remove");
        eq("", RedirectPolicy.merge(ours, ours, false), "remove the only one");
        eq("com.talk/com.talk.Back:" + ours, RedirectPolicy.merge("com.talk/com.talk.Back:io.freemyfiretv/.HomeRedirectService", ours, true), "no duplicate");
        eq(true, RedirectPolicy.lists("x/x.Y:io.freemyfiretv/.HomeRedirectService", ours), "short spelling is listed");
        eq(false, RedirectPolicy.lists("x/x.Y", ours), "not listed");
        eq(false, RedirectPolicy.lists(null, ours), "no setting");
        eq(null, RedirectPolicy.merge("bad entry;rm -rf", ours, true), "an odd entry is never rewritten");

        // Amazon's settings session: starts with the pause (TV Settings button), keeps the redirect off while the person
        // moves around Amazon's settings, ends at the first window that isn't Amazon's.
        final String SET = "com.amazon.tv.settings.v2";
        Verdict v;
        p = new RedirectPolicy();
        p.onWindow(0, APP, false, false, true, 0, RedirectPolicy.Kind.OTHER);
        p.onWindow(1_000, SET, false, false, true, 16_000, RedirectPolicy.Kind.SETTINGS);   // pause running: session starts
        p.onWindow(30_000, SET, false, false, true, 16_000, RedirectPolicy.Kind.SETTINGS);  // a page, long after the pause
        eq(Verdict.PAUSED, p.onWindow(40_000, LAUNCHER, true, false, true, 16_000, RedirectPolicy.Kind.SYSTEM), "back to the grid");
        p.onWindow(50_000, OURS, false, true, true, 16_000, RedirectPolicy.Kind.OTHER);      // our home: session over
        eq(Verdict.REDIRECT, p.onWindow(60_000, LAUNCHER, true, false, true, 16_000, RedirectPolicy.Kind.SYSTEM), "redirect works again");

        // Without the TV Settings button's pause, Amazon's settings windows don't start a session.
        p = new RedirectPolicy();
        p.onWindow(0, APP, false, false, true, 0, RedirectPolicy.Kind.OTHER);
        p.onWindow(1_000, SET, false, false, true, 0, RedirectPolicy.Kind.SETTINGS);
        eq(Verdict.REDIRECT, p.onWindow(40_000, LAUNCHER, true, false, true, 0, RedirectPolicy.Kind.SYSTEM), "no session without the pause");

        // Opening our home right after the TV started: only then, and only when wanted and not paused.
        eq(true, RedirectPolicy.startAtBoot(30_000, true, 1_000, 0), "just after boot");
        eq(false, RedirectPolicy.startAtBoot(RedirectPolicy.BOOT_WINDOW_MS + 1, true, 1_000, 0), "long after boot");
        eq(false, RedirectPolicy.startAtBoot(30_000, false, 1_000, 0), "switch off");
        eq(false, RedirectPolicy.startAtBoot(30_000, true, 1_000, 5_000), "paused by the person");

        System.out.println("RedirectPolicyTest: " + checks + " checks passed");
    }
}
