package com.example.phonetimer;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Main always-on host. An enabled accessibility service is started by Android itself
 * (also after reboot). It never reads anything on your screen.
 */
public class LockService extends AccessibilityService implements Engine.Host {

    static volatile LockService instance;

    private Engine engine;

    @Override
    protected void onServiceConnected() {
        instance = this;
        // The backup service is no longer needed once this one is alive.
        stopService(new Intent(this, TimerService.class));
        if (engine == null) engine = new Engine(this, this);
        engine.start();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        instance = null;
        if (engine != null) engine.stop();
    }

    @Override
    public boolean lockScreen() {
        return performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public int overlayType() {
        return WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    @Override
    public boolean canShowOverlay() {
        return true;
    }
}
