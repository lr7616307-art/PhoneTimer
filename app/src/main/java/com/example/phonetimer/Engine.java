package com.example.phonetimer;

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
import android.widget.TextView;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The timer brain. It can be hosted by the accessibility service (main way) or by the
 * foreground service (backup way, used when Android leaves the accessibility service stuck).
 */
class Engine {

    interface Host {
        boolean lockScreen();

        int overlayType();

        boolean canShowOverlay();
    }

    static final String PREFS = "prefs";
    static final String KEY_LIMIT = "limit_min";
    static final String KEY_RUNNING = "running";   // the app's on/off switch
    static final String KEY_UNLOCK = "unlock_time";
    static final String KEY_LOCK = "lock_time";
    static final String KEY_ACTIVE = "active";
    static final String KEY_HEART = "heartbeat";
    static final String KEY_X = "bubble_x";
    static final String KEY_Y = "bubble_y";
    static final String KEY_BUBBLE = "show_bubble";  // show the floating circle?

    static final int NOTIF_ID = 1;
    private static final String CHANNEL = "timer";

    static volatile Engine current;

    private final Context ctx;
    private final Host host;
    private final SharedPreferences prefs;
    private final WindowManager wm;
    private final NotificationManager nm;
    private final PowerManager pm;
    private final KeyguardManager km;
    private final int size;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView bubble;
    private WindowManager.LayoutParams lp;

    private boolean started;
    private boolean wasEnabled;
    private boolean active;          // phone is unlocked and in use
    private long unlockTime;
    private long lastLockAttempt;
    private int shownLimit = -1;
    private int loops;

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

    Engine(Context context, Host host) {
        this.ctx = context;
        this.host = host;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        nm = context.getSystemService(NotificationManager.class);
        pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        size = (int) (64 * context.getResources().getDisplayMetrics().density);
    }

    // ---- start / stop ----

    void start() {
        current = this;
        if (started) {
            kick();
            return;
        }
        started = true;

        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Phone time", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, filter);
        }

        wasEnabled = false;
        active = false;
        hideBubble();
        kick();
    }

    void stop() {
        if (current == this) current = null;
        if (!started) return;
        started = false;
        handler.removeCallbacksAndMessages(null);
        try {
            ctx.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        hideBubble();
    }

    /** Run a check right now (and keep the one-second loop going). */
    void kick() {
        handler.removeCallbacks(loop);
        handler.post(loop);
    }

    static void kickNow() {
        Engine e = current;
        if (e != null) e.kick();
    }

    // ---- main check ----

    private void sync() {
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

        if (!prefs.getBoolean(KEY_BUBBLE, true) && bubble != null) {
            hideBubble();
        }
        if (active) {
            tickActive(now);
        }

        int limit = prefs.getInt(KEY_LIMIT, 5);
        loops++;
        if (limit != shownLimit || (loops % 3 == 0 && !notificationVisible())) {
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
            host.lockScreen();
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

    Notification buildNotification() {
        // Make sure the channel exists even when this is called before start().
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Phone time", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        nm.createNotificationChannel(channel);

        PendingIntent pi = PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        DateFormat df = android.text.format.DateFormat.getTimeFormat(ctx);
        long lastLock = prefs.getLong(KEY_LOCK, 0);
        int limit = prefs.getInt(KEY_LIMIT, 5);

        Notification.Builder b = new Notification.Builder(ctx, CHANNEL)
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
        if (bubble != null || !prefs.getBoolean(KEY_BUBBLE, true) || !host.canShowOverlay()) {
            return;
        }

        bubble = new TextView(ctx);
        bubble.setBackgroundResource(R.drawable.circle_bg);
        bubble.setGravity(Gravity.CENTER);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(14);
        bubble.setTypeface(null, Typeface.BOLD);
        bubble.setText("0:00");

        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(
                size, size,
                host.overlayType(),
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
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
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
