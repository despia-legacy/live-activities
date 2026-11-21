package despia.ext.liveactivity;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;

import org.json.JSONObject;

/**
 * Posts and cancels the ongoing notification of one activity. On Android 16 (API 36) it asks for
 * promotion to a Live Update (Notification.ProgressStyle, the status-bar chip, the
 * "android.requestPromotedOngoing" extra); below API 36 the same notification updates in place
 * without promotion. Framework Notification.Builder only, as OneSignal's Android Live Updates guide
 * advises, so no androidx.core upgrade is forced on the app.
 */
final class LiveNotifier {
    /** Low importance: frequent updates never sound, vibrate or badge. */
    static final String CHANNEL = "nx_live_updates";
    /** An update that carries an alert (alert_title or alert_body) is posted once here. */
    static final String ALERT_CHANNEL = "nx_live_alerts";
    /** The request extra Android 16 reads (Notification.EXTRA_REQUEST_PROMOTED_ONGOING, API 36.1). */
    static final String EXTRA_PROMOTED = "android.requestPromotedOngoing";
    /** A Live Activity lasts at most 8 hours on iOS; the Android backstop if no end arrives. */
    static final long TIMEOUT_MS = 8L * 60 * 60 * 1000;
    /** iOS keeps an ended Live Activity on the Lock Screen for up to 4 hours (dismissal "default"). */
    static final long ENDED_KEEP_MS = 4L * 60 * 60 * 1000;

    private LiveNotifier() {
    }

    static NotificationManager manager(Context ctx) {
        return (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = manager(ctx);
        NotificationChannel quiet = new NotificationChannel(CHANNEL, ctx.getString(R.string.nx_live_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        quiet.setDescription(ctx.getString(R.string.nx_live_channel_description));
        quiet.setShowBadge(false);
        quiet.enableVibration(false);
        quiet.setSound(null, null);
        nm.createNotificationChannel(quiet);
        NotificationChannel alerts = new NotificationChannel(ALERT_CHANNEL, ctx.getString(R.string.nx_live_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        alerts.setDescription(ctx.getString(R.string.nx_live_channel_description));
        alerts.setShowBadge(false);
        nm.createNotificationChannel(alerts);
    }

    static boolean enabled(Context ctx) {
        return manager(ctx).areNotificationsEnabled();
    }

    /** Whether Android 16 may show this app's ongoing notifications as Live Updates. */
    static boolean promotable(Context ctx) {
        return Build.VERSION.SDK_INT >= 36 && manager(ctx).canPostPromotedNotifications();
    }

    static void post(Context ctx, String id, int nid, LiveTemplate t, JSONObject attributes, JSONObject state,
                     String alertTitle, String alertBody) {
        post(ctx, id, nid, t, attributes, state, alertTitle, alertBody, true, TIMEOUT_MS);
    }

    /**
     * @param ongoing   false for the final state of an ended activity: it can be swiped away
     * @param timeoutMs when the system removes it (API 26+)
     */
    static void post(Context ctx, String id, int nid, LiveTemplate t, JSONObject attributes, JSONObject state,
                     String alertTitle, String alertBody, boolean ongoing, long timeoutMs) {
        ensureChannels(ctx);
        boolean alert = alertTitle != null || alertBody != null;
        boolean live = Build.VERSION.SDK_INT >= 36;
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(ctx, alert ? ALERT_CHANNEL : CHANNEL)
                : new Notification.Builder(ctx);
        b.setSmallIcon(t.smallIcon())
                .setOngoing(ongoing)
                .setAutoCancel(false)
                .setOnlyAlertOnce(!alert)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setDeleteIntent(dismissIntent(ctx, id, nid));
        if (ongoing) {
            b.addAction(new Notification.Action.Builder(null, ctx.getString(R.string.nx_live_remove),
                    dismissIntent(ctx, id, nid)).build());
        }
        PendingIntent open = openIntent(ctx, nid);
        if (open != null) b.setContentIntent(open);
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(timeoutMs);
        t.render(ctx, b, attributes, state, live);
        if (alert) {
            // Posted once on the alert channel: it sounds and peeks like an iOS alert update, and the
            // template's own content and style stay as they are.
            b.setTicker(alertTitle != null && alertBody != null ? alertTitle + ": " + alertBody
                    : alertTitle != null ? alertTitle : alertBody);
        }
        if (live && ongoing) {
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_PROMOTED, true);
            b.addExtras(extras);
        }
        manager(ctx).notify(nid, b.build());
    }

    static void cancel(Context ctx, int nid) {
        manager(ctx).cancel(nid);
    }

    private static PendingIntent dismissIntent(Context ctx, String id, int nid) {
        Intent i = new Intent(ctx, DismissReceiver.class).putExtra(DismissReceiver.EXTRA_ID, id)
                .putExtra(DismissReceiver.EXTRA_NID, nid);
        return PendingIntent.getBroadcast(ctx, nid, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Opens the app, as a tap on a Live Activity does on iOS. */
    private static PendingIntent openIntent(Context ctx, int nid) {
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (launch == null) return null;
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, nid, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
