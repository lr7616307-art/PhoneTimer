package com.example.phonetimer;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Home-screen widget. One tap starts the tracker, or shows/hides the floating circle. */
public class WidgetProvider extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        manager.updateAppWidget(ids, build(context));
    }

    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, WidgetProvider.class));
        if (ids != null && ids.length > 0) {
            manager.updateAppWidget(ids, build(context));
        }
    }

    private static RemoteViews build(Context context) {
        boolean running = context.getSharedPreferences(Engine.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Engine.KEY_RUNNING, false);
        boolean circle = context.getSharedPreferences(Engine.PREFS, Context.MODE_PRIVATE)
                .getBoolean(Engine.KEY_BUBBLE, true);

        RemoteViews rv = new RemoteViews(context.getPackageName(), R.layout.widget);
        String status;
        if (!running) {
            status = "Tracker off • tap to start";
        } else if (circle) {
            status = "Circle ON • tap to hide";
        } else {
            status = "Circle OFF • tap to show";
        }
        rv.setTextViewText(R.id.wStatus, status);
        rv.setInt(R.id.widgetRoot, "setBackgroundResource",
                running && circle ? R.drawable.widget_bg_on : R.drawable.widget_bg_off);

        Intent intent = new Intent(context, ToggleActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        rv.setOnClickPendingIntent(R.id.widgetRoot, pi);
        return rv;
    }
}
