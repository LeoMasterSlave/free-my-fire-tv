package io.freemyfiretv;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.accessibility.AccessibilityEvent;

import java.util.Collections;
import java.util.List;

/**
 * Fire TV: brings Free My Fire TV's home screen up when Amazon's home arrives (the Home button, or Back out of an app).
 * It listens to window changes only: no key filter (so it can't slow or double the remote's keys) and no window
 * content. The rules are {@link RedirectPolicy}'s; the setup, the way back and the state files are {@link FireHome}'s.
 *
 * Amazon's launcher stays the system's home screen: this starts our activity on top of it. Fire OS 7 holds an app
 * started from the Home button back for a few seconds (Android's app-switch lock), so there it can show late.
 * Everything here runs in the main process, on the main thread (accessibility callbacks are).
 */
public class HomeRedirectService extends AccessibilityService {
    private final RedirectPolicy policy = new RedirectPolicy();
    private List<ComponentName> homes = Collections.emptyList();
    private long homesAt;
    private boolean receiving;

    private final BroadcastReceiver off = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            AppLog.w("Home button service: switching itself off");
            disableSelf();   // takes it out of Android's enabled services; no permission needed
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        FireHome.noteConnected(this, true);
        if (!receiving) {
            registerReceiver(off, new IntentFilter(FireHome.ACTION_OFF));
            receiving = true;
        }
        AppLog.w("Home button service connected");
        bootStart();
    }

    @Override public boolean onUnbind(Intent intent) {
        FireHome.noteConnected(this, false);
        unregister();
        AppLog.w("Home button service disconnected");
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        unregister();
        super.onDestroy();
    }

    /** Just after the TV started: open our home screen without waiting for the Home button. Once per service start. */
    private void bootStart() {
        long now = System.currentTimeMillis();
        if (!RedirectPolicy.startAtBoot(android.os.SystemClock.elapsedRealtime(), FireHome.wanted(this), now,
                FireHome.userPausedUntil(this))) {
            return;
        }
        try {
            startActivity(new Intent(this, FireHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppLog.w("Home button service: opened our home screen after the TV started");
        } catch (RuntimeException e) {
            AppLog.w("couldn't open our home screen after the TV started: " + e);
        }
    }

    private void unregister() {
        if (!receiving) return;
        receiving = false;
        try {
            unregisterReceiver(off);
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Override public void onInterrupt() {}

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        try {
            handle(event);
        } catch (RuntimeException e) {
            AppLog.w("Home button service: " + e);   // never let an accessibility callback take the process down
        }
    }

    private void handle(AccessibilityEvent event) {
        String pkg = event.getPackageName() == null ? "" : event.getPackageName().toString();
        CharSequence cls = event.getClassName();
        long now = System.currentTimeMillis();
        // The home screens are asked of the system now and then, not on every window change.
        if (now - homesAt > 60_000 || homes.isEmpty()) {
            homes = FireHome.amazonHomes(this);
            homesAt = now;
        }
        boolean amazonHome = FireHome.isAmazonHome(homes, pkg, cls);
        boolean ours = pkg.equals(getPackageName());
        RedirectPolicy.Kind kind = FireHome.kindOf(pkg, cls, amazonHome);
        RedirectPolicy.Verdict verdict = policy.onWindow(now, pkg, amazonHome, ours, FireHome.wanted(this),
                FireHome.userPausedUntil(this), kind);
        if (verdict == RedirectPolicy.Verdict.IGNORE) return;

        boolean go = verdict == RedirectPolicy.Verdict.REDIRECT;
        AppLog.w("Amazon home arrived (" + pkg + "/" + cls + "): " + verdict);   // what to look for in logcat, on a real TV
        if (go) {
            try {
                startActivity(new Intent(this, FireHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException e) {   // ActivityNotFound, or Android refusing a start from the background
                AppLog.w("couldn't start Free My Fire TV's home screen: " + e);
                go = false;
            }
        }
        FireHome.noteHomeSeen(this, pkg, String.valueOf(cls), go ? verdict.name() : verdict.name() + " (not started)", go);
    }
}
