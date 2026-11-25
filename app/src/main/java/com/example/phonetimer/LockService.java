package com.example.phonetimer;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/** Only used to press the system "lock screen" action. It never reads the screen. */
public class LockService extends AccessibilityService {

    static volatile LockService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    static boolean lockScreen() {
        LockService s = instance;
        return s != null && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }
}
