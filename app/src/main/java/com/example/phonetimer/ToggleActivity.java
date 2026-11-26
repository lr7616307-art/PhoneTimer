package com.example.phonetimer;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Toast;

/** Invisible. Opened by the widget, does its job and closes straight away. */
public class ToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences prefs = getSharedPreferences(Engine.PREFS, MODE_PRIVATE);
        boolean running = prefs.getBoolean(Engine.KEY_RUNNING, false);
        boolean alive = LockService.instance != null || Engine.current != null;
        String message;

        if (!running || !alive) {
            // Tracker was off or was cleared from memory: start it. Nothing else changes.
            prefs.edit().putBoolean(Engine.KEY_RUNNING, true).apply();
            if (LockService.instance == null) {
                try {
                    startForegroundService(new Intent(this, TimerService.class));
                } catch (Exception ignored) {
                }
            }
            message = "Phone Timer is on";
        } else {
            // Already working: show or hide the circle. The notification always stays.
            boolean show = !prefs.getBoolean(Engine.KEY_BUBBLE, true);
            prefs.edit().putBoolean(Engine.KEY_BUBBLE, show).apply();
            message = show ? "Circle shown" : "Circle hidden";
        }

        Engine.kickNow();
        WidgetProvider.refresh(this);
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
        finish();
    }
}
