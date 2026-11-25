package com.example.phonetimer;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The whole app engine lives here. An enabled accessibility service is started by Android
 * itself (also after reboot) and is restarted by the system if it gets killed, which is why
 * the timer, the floating circle, the notification and the auto-lock all run from here.
 * It never reads anything on your screen.
 */
public class LockService extends AccessibilityService {

    static final String PREFS = "prefs";
    static final String KEY_LIMIT = "limit_min";
    static final String KEY_RUNNING = "running";   // the app's on/off switch
    static final String KEY_UNLOCK = "unlock_time";
    static final String KEY_LOCK = "lock_time";
    static final String KEY_ACTIVE = "active";
    static final String KEY_HEART = "heartbeat";
    static final String KEY_X = "bubble_x";
    static final String KEY_Y = "bubble_y";

    private static final String CHANNEL = "timer";
    private static final int NOTIF_ID = 1;

    static volatile LockService instance;

    private SharedPreferences prefs;
    private WindowManager wm;
    private NotificationManager nm;
    private PowerManager pm;
    private KeyguardManager km;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private int size;

    private boolean registered;
    private boolean wasEnabled;
    private boolean active;          // phone is unlocked and in use
    private long unlockTime;
    private long lastLockAttempt;
    private int shownLimit = -1;
    private int loops;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            kick();
        }
    };

    /** Checks the real phone state every second, so a missed broadcast can never matter. */
    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            try {
                sync();
            } catch (Exception ignored) {
            }
            handler.removeCallbacks(this);
            handler.postDelayed(this, pm.isInteractive() ? 1000 : 3000);
        }
    };

    // ---- lifecycle ----

    @Override
    protected void onServiceConnected() {
        instance = this;
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        nm = getSystemService(NotificationManager.class);
        pm = (PowerManager) getSystemService(POWER_SERVICE);
        km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        size = (int) (64 * getResources().getDisplayMetrics().density);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Phone time", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        if (!registered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(receiver, filter);
            }
            registered = true;
        }

        wasEnabled = false;
        active = false;
        hideBubble();
        kick();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacksAndMessages(null);
        if (registered) {
            try {
                unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            registered = false;
        }
        hideBubble();
        super.onDestroy();
    }

    /** Run a check right now (and keep the one-second loop going). */
    void kick() {
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    static void kickNow() {
        LockService s = instance;
        if (s != null) s.kick();
    }

    // ---- main check ----

    private void sync() {
        if (prefs == null) return;

        if (!prefs.getBoolean(KEY_RUNNING, false)) {
            if (wasEnabled) {
                wasEnabled = false;
                active = false;
                prefs.edit().putBoolean(KEY_ACTIVE, false).apply();
                hideBubble();
                nm.cancel(NOTIF_ID);
            }
            return;
        }
        if (!wasEnabled) {
            wasEnabled = true;
            shownLimit = -1;
        }

        long now = System.currentTimeMillis();
        boolean unlocked = pm.isInteractive() && !km.isKeyguardLocked();

        if (unlocked && !active) {
            // If the app was only restarted a moment ago, carry on the same session.
            long since = prefs.getLong(KEY_UNLOCK, 0);
            boolean resume = prefs.getBoolean(KEY_ACTIVE, false)
                    && now - prefs.getLong(KEY_HEART, 0) < 20000
                    && since > 0 && since <= now;
            startSession(resume ? since : now);
        } else if (!unlocked && active) {
            endSession();
        }

        if (active) {
            tickActive(now);
        }

        int limit = prefs.getInt(KEY_LIMIT, 5);
        loops++;
        if (limit != shownLimit || (loops % 10 == 0 && !notificationVisible())) {
            updateNotification();
        }
    }

    private void startSession(long since) {
        active = true;
        unlockTime = since;
        lastLockAttempt = 0;
        prefs.edit()
                .putLong(KEY_UNLOCK, since)
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_HEART, System.currentTimeMillis())
                .apply();
        showBubble();
        updateNotification();
    }

    private void endSession() {
        active = false;
        prefs.edit()
                .putLong(KEY_LOCK, System.currentTimeMillis())
                .putBoolean(KEY_ACTIVE, false)
                .apply();
        hideBubble();
        updateNotification();
    }

    private void tickActive(long now) {
        long elapsed = now - unlockTime;
        if (bubble == null) showBubble();
        if (bubble != null) bubble.setText(formatShort(elapsed));

        if (loops % 5 == 0) {
            prefs.edit().putLong(KEY_HEART, now).apply();
        }

        long limit = prefs.getInt(KEY_LIMIT, 5) * 60000L;
        if (elapsed >= limit && now - lastLockAttempt >= 5000) {
            lastLockAttempt = now;
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
    }

    // ---- notification ----

    private boolean notificationVisible() {
        try {
            for (StatusBarNotification s : nm.getActiveNotifications()) {
                if (s.getId() == NOTIF_ID) return true;
            }
        } catch (Exception ignored) {
            return true;
        }
        return false;
    }

    private void updateNotification() {
        shownLimit = prefs.getInt(KEY_LIMIT, 5);
        nm.notify(NOTIF_ID, buildNotification());
    }

    private Notification buildNotification() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        DateFormat df = android.text.format.DateFormat.getTimeFormat(this);
        long lastLock = prefs.getLong(KEY_LOCK, 0);
        int limit = prefs.getInt(KEY_LIMIT, 5);

        Notification.Builder b = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_notif)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi);

        if (active) {
            b.setContentTitle("Phone awake")
                    .setContentText("Unlocked at " + df.format(new Date(unlockTime))
                            + " • auto-lock in " + limitText(limit))
                    .setWhen(unlockTime)
                    .setShowWhen(true)
                    .setUsesChronometer(true);
            if (lastLock > 0) {
                b.setSubText("Last locked " + df.format(new Date(lastLock)));
            }
        } else {
            long unlock = prefs.getLong(KEY_UNLOCK, 0);
            b.setContentTitle("Phone locked").setShowWhen(false);
            if (lastLock > 0 && unlock > 0 && lastLock >= unlock) {
                b.setContentText("Locked at " + df.format(new Date(lastLock))
                        + " • was awake for " + formatLong(lastLock - unlock));
            } else {
                b.setContentText("Waiting for you to unlock");
            }
        }
        return b.build();
    }

    // ---- floating circle ----

    private void showBubble() {
        if (bubble != null) return;

        bubble = new TextView(this);
        bubble.setBackgroundResource(R.drawable.circle_bg);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14);
        bubble.setTypeface(null, Typeface.BOLD);
        bubble.setText("0:00");

        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(
                size, size,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = clamp(prefs.getInt(KEY_X, dm.widthPixels - size), 0, dm.widthPixels - size);
        lp.y = clamp(prefs.getInt(KEY_Y, dm.heightPixels / 4), 0, dm.heightPixels - size);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            private int startX, startY;
            private float touchX, touchY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startX = lp.x;
                        startY = lp.y;
                        touchX = e.getRawX();
                        touchY = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = startX + (int) (e.getRawX() - touchX);
                        lp.y = startY + (int) (e.getRawY() - touchY);
                        wm.updateViewLayout(bubble, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        snapToEdge();
                        return true;
                    default:
                        return false;
                }
            }
        });

        try {
            wm.addView(bubble, lp);
        } catch (Exception ex) {
            bubble = null;
        }
    }

    /** After you let go, the circle sticks to the nearest left or right edge. */
    private void snapToEdge() {
        if (bubble == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        boolean left = lp.x + size / 2 < dm.widthPixels / 2;
        lp.x = left ? 0 : dm.widthPixels - size;
        lp.y = clamp(lp.y, 0, dm.heightPixels - size);
        wm.updateViewLayout(bubble, lp);
        prefs.edit().putInt(KEY_X, lp.x).putInt(KEY_Y, lp.y).apply();
    }

    private void hideBubble() {
        if (bubble != null) {
            try {
                wm.removeView(bubble);
            } catch (Exception ignored) {
            }
            bubble = null;
        }
    }

    // ---- helpers ----

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(v, Math.max(min, max)));
    }

    static String limitText(int minutes) {
        return minutes >= 60 ? "1 hour" : minutes + " min";
    }

    private static String formatShort(long ms) {
        long s = ms / 1000;
        if (s < 3600) {
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
        }
        return String.format(Locale.US, "%dh%02d", s / 3600, (s % 3600) / 60);
    }

    private static String formatLong(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) {
            return (s / 3600) + " h " + ((s % 3600) / 60) + " min";
        }
        return (s / 60) + " min " + (s % 60) + " s";
    }
}
