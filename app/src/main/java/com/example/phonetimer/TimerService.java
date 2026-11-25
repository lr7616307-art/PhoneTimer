package com.example.phonetimer;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class TimerService extends Service {

    static final String PREFS = "prefs";
    static final String KEY_LIMIT = "limit_min";
    static final String KEY_RUNNING = "running";
    static final String KEY_UNLOCK = "unlock_time";
    static final String KEY_LOCK = "lock_time";
    static final String KEY_X = "bubble_x";
    static final String KEY_Y = "bubble_y";

    private static final String CHANNEL = "timer";
    private static final int NOTIF_ID = 1;

    private SharedPreferences prefs;
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private int size;

    private boolean active;          // phone is unlocked and in use
    private long unlockTime;
    private long lastLockAttempt;
    private boolean warned;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_USER_PRESENT.equals(action)) {
                startSession();
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                endSession();
            }
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            long now = System.currentTimeMillis();
            long elapsed = now - unlockTime;
            if (bubble != null) bubble.setText(formatShort(elapsed));

            long limit = prefs.getInt(KEY_LIMIT, 5) * 60000L;
            if (elapsed >= limit && now - lastLockAttempt >= 5000) {
                lastLockAttempt = now;
                if (!LockService.lockScreen() && !warned) {
                    warned = true;
                    Toast.makeText(TimerService.this,
                            "Turn on Phone Timer in Accessibility settings to enable auto-lock",
                            Toast.LENGTH_LONG).show();
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        size = (int) (64 * getResources().getDisplayMetrics().density);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Phone time", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (pm.isInteractive() && !km.isKeyguardLocked()) {
            startSession();
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        hideBubble();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- unlock / lock handling ----

    private void startSession() {
        active = true;
        unlockTime = System.currentTimeMillis();
        warned = false;
        lastLockAttempt = 0;
        prefs.edit().putLong(KEY_UNLOCK, unlockTime).apply();
        showBubble();
        handler.removeCallbacks(tick);
        handler.post(tick);
        updateNotification();
    }

    private void endSession() {
        if (active) {
            prefs.edit().putLong(KEY_LOCK, System.currentTimeMillis()).apply();
        }
        active = false;
        handler.removeCallbacks(tick);
        hideBubble();
        updateNotification();
    }

    // ---- notification ----

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF_ID, buildNotification());
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
        if (bubble != null || !Settings.canDrawOverlays(this)) return;

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
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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
