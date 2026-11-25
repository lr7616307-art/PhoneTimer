package com.example.phonetimer;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
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
    private TextView tvLimit, tvNotif, tvLock, tvBackup;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(Engine.PREFS, MODE_PRIVATE);

        tvLimit = findViewById(R.id.tvLimit);
        tvNotif = findViewById(R.id.tvNotif);
        tvLock = findViewById(R.id.tvLock);
        tvBackup = findViewById(R.id.tvBackup);
        btnToggle = findViewById(R.id.btnToggle);
        SeekBar seek = findViewById(R.id.seekLimit);

        // 1..60 minutes
        seek.setMax(59);
        int limit = prefs.getInt(Engine.KEY_LIMIT, 5);
        seek.setProgress(limit - 1);
        showLimit(limit);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                showLimit(progress + 1);
                prefs.edit().putInt(Engine.KEY_LIMIT, progress + 1).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                Engine.kickNow();
            }
        });

        findViewById(R.id.btnNotif).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        });

        findViewById(R.id.btnLock).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btnOverlay).setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()))));

        findViewById(R.id.btnAdmin).setOnClickListener(v -> {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent());
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Only used to lock the screen when your time is up.");
            startActivity(i);
        });

        findViewById(R.id.btnBattery).setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            } catch (ActivityNotFoundException e) {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            }
        });

        btnToggle.setOnClickListener(v -> {
            boolean on = prefs.getBoolean(Engine.KEY_RUNNING, false);
            prefs.edit().putBoolean(Engine.KEY_RUNNING, !on).apply();
            if (on) {
                stopService(new Intent(this, TimerService.class));
            } else if (LockService.instance == null && !accessibilityListed()) {
                Toast.makeText(this,
                        "Also turn on Phone Timer in Accessibility settings (button above)",
                        Toast.LENGTH_LONG).show();
            }
            ensureEngine();
            Engine.kickNow();
            refresh();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureEngine();
        Engine.kickNow();
        refresh();
    }

    /** If Android left the accessibility service stuck, run the backup engine instead. */
    private void ensureEngine() {
        if (prefs.getBoolean(Engine.KEY_RUNNING, false) && LockService.instance == null) {
            try {
                startForegroundService(new Intent(this, TimerService.class));
            } catch (Exception ignored) {
            }
        }
    }

    private ComponentName adminComponent() {
        return new ComponentName(this, AdminReceiver.class);
    }

    private boolean accessibilityListed() {
        String s = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return s != null && s.contains(getPackageName() + "/");
    }

    private void showLimit(int minutes) {
        tvLimit.setText("Auto-lock after: " + Engine.limitText(minutes));
    }

    private void refresh() {
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        tvNotif.setText(notifOk ? "✔ Notifications allowed" : "✘ Notifications not allowed yet");

        if (LockService.instance != null) {
            tvLock.setText("✔ Accessibility is on (timer, circle and auto-lock run)");
        } else if (accessibilityListed()) {
            tvLock.setText("⚠ Android left Accessibility stuck (happens after 'Clear all'). "
                    + "Backup mode is running instead. A phone restart fixes it fully.");
        } else {
            tvLock.setText("✘ Accessibility is off (find Phone Timer there and turn it on)");
        }

        DevicePolicyManager dpm = getSystemService(DevicePolicyManager.class);
        boolean adminOk = dpm != null && dpm.isAdminActive(adminComponent());
        tvBackup.setText("Backup mode (works even if Accessibility gets stuck):\n"
                + (Settings.canDrawOverlays(this) ? "✔" : "✘") + " floating circle    "
                + (adminOk ? "✔" : "✘") + " auto-lock");

        btnToggle.setText(prefs.getBoolean(Engine.KEY_RUNNING, false)
                ? "Turn OFF Phone Timer" : "Turn ON Phone Timer");
    }
}
