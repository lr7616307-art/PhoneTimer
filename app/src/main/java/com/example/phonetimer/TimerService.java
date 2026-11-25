package com.example.phonetimer;

import android.app.Notification;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.WindowManager;

/**
 * Backup host. Used when Android leaves the accessibility service stuck (for example after
 * "Clear all"). It starts when you open the app, and it stops itself as soon as the
 * accessibility service is alive again.
 */
public class TimerService extends Service implements Engine.Host {

    private Engine engine;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (engine == null) engine = new Engine(this, this);

        Notification n = engine.buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Engine.NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Engine.NOTIF_ID, n);
        }

        if (LockService.instance != null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        engine.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) engine.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public boolean lockScreen() {
        LockService s = LockService.instance;
        if (s != null) return s.lockScreen();
        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        ComponentName admin = new ComponentName(this, AdminReceiver.class);
        if (dpm != null && dpm.isAdminActive(admin)) {
            dpm.lockNow();
            return true;
        }
        return false;
    }

    @Override
    public int overlayType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    @Override
    public boolean canShowOverlay() {
        return Settings.canDrawOverlays(this);
    }
}
