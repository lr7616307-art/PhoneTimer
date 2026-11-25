package com.example.phonetimer;

import android.Manifest;
import android.app.Activity;
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

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private TextView tvLimit, tvOverlay, tvNotif, tvLock;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(TimerService.PREFS, MODE_PRIVATE);

        tvLimit = findViewById(R.id.tvLimit);
        tvOverlay = findViewById(R.id.tvOverlay);
        tvNotif = findViewById(R.id.tvNotif);
        tvLock = findViewById(R.id.tvLock);
        btnToggle = findViewById(R.id.btnToggle);
        SeekBar seek = findViewById(R.id.seekLimit);

        // 1..60 minutes
        seek.setMax(59);
        int limit = prefs.getInt(TimerService.KEY_LIMIT, 5);
        seek.setProgress(limit - 1);
        showLimit(limit);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showLimit(progress + 1);
                prefs.edit().putInt(TimerService.KEY_LIMIT, progress + 1).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                if (prefs.getBoolean(TimerService.KEY_RUNNING, false)) {
                    startForegroundService(new Intent(MainActivity.this, TimerService.class));
                }
            }
        });

        findViewById(R.id.btnOverlay).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        findViewById(R.id.btnNotif).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });

        findViewById(R.id.btnLock).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        btnToggle.setOnClickListener(v -> {
            boolean running = prefs.getBoolean(TimerService.KEY_RUNNING, false);
            Intent svc = new Intent(this, TimerService.class);
            if (running) {
                stopService(svc);
            } else {
                startForegroundService(svc);
            }
            prefs.edit().putBoolean(TimerService.KEY_RUNNING, !running).apply();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void showLimit(int minutes) {
        tvLimit.setText("Auto-lock after: " + TimerService.limitText(minutes));
    }

    private void refresh() {
        tvOverlay.setText(Settings.canDrawOverlays(this)
                ? "✔ Floating circle allowed" : "✘ Floating circle not allowed yet");
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        tvNotif.setText(notifOk ? "✔ Notifications allowed" : "✘ Notifications not allowed yet");
        tvLock.setText(LockService.instance != null
                ? "✔ Auto-lock is on"
                : "✘ Auto-lock is off (find Phone Timer in Accessibility and turn it on)");
        btnToggle.setText(prefs.getBoolean(TimerService.KEY_RUNNING, false)
                ? "Stop Phone Timer" : "Start Phone Timer");
    }
}
