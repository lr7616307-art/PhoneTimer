package com.example.phonetimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        // Phone restarted, or phone unlocked while the app was cleared from memory.
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action))
                && LockService.instance == null
                && Engine.current == null
                && context.getSharedPreferences(Engine.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Engine.KEY_RUNNING, false)) {
            try {
                context.startForegroundService(new Intent(context, TimerService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
