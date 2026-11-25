package com.example.phonetimer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && context.getSharedPreferences(Engine.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Engine.KEY_RUNNING, false)) {
            try {
                context.startForegroundService(new Intent(context, TimerService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
