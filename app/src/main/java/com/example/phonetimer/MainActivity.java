package com.example.phonetimer;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView tvLimit, tvNotif, tvLock;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(LockService.PREFS, MODE_PRIVATE);

        tvLimit = findViewById(R.id.tvLimit);
        tvNotif = findViewById(R.id.tvNotif);
        tvLock = findViewById(R.id.tvLock);
        btnToggle = findViewById(R.id.btnToggle);
        SeekBar seek = findViewById(R.id.seekLimit);

        // 1..60 minutes
        seek.setMax(59);
        int limit = prefs.getInt(LockService.KEY_LIMIT, 5);
        seek.setProgress(limit - 1);
        showLimit(limit);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showLimit(progress + 1);
                prefs.edit().putInt(LockService.KEY_LIMIT, progress + 1).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                LockService.kickNow();
            }
        });

        findViewById(R.id.btnNotif).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });

        findViewById(R.id.btnLock).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        btnToggle.setOnClickListener(v -> {
            boolean on = prefs.getBoolean(LockService.KEY_RUNNING, false);
            prefs.edit().putBoolean(LockService.KEY_RUNNING, !on).apply();
            if (!on && LockService.instance == null) {
                Toast.makeText(this,
                        "Now turn on Phone Timer in Accessibility settings (button above)",
                        Toast.LENGTH_LONG).show();
            }
            LockService.kickNow();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        LockService.kickNow();
        refresh();
    }

    private void showLimit(int minutes) {
        tvLimit.setText("Auto-lock after: " + LockService.limitText(minutes));
    }

    private void refresh() {
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        tvNotif.setText(notifOk ? "✔ Notifications allowed" : "✘ Notifications not allowed yet");
        tvLock.setText(LockService.instance != null
                ? "✔ Accessibility is on (timer, circle and auto-lock can run)"
                : "✘ Accessibility is off (find Phone Timer there and turn it on)");
        btnToggle.setText(prefs.getBoolean(LockService.KEY_RUNNING, false)
                ? "Turn OFF Phone Timer" : "Turn ON Phone Timer");
    }
}
