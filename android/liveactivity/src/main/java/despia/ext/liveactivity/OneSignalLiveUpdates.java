package despia.ext.liveactivity;

import androidx.annotation.Keep;

import com.onesignal.notifications.INotificationReceivedEvent;
import com.onesignal.notifications.INotificationServiceExtension;

import org.json.JSONObject;

/**
 * The only class of this extension that names a OneSignal type. OneSignal calls it for every push it
 * receives, also while the app is closed (manifest meta-data com.onesignal.NotificationServiceExtension).
 * A push whose data carries {@code live_notification} is a Live Update event and is never shown as an
 * ordinary notification; every other push is left to OneSignal untouched.
 *
 * <p>Payload ({@code data} of OneSignal's POST /notifications, the convention of OneSignal's
 * "Android Live Updates" guide, plus {@code template}):
 * <pre>
 * {"live_notification": {"key": "order-42", "event": "start|update|end", "template": "delivery",
 *   "event_attributes": {...}, "event_updates": {...}, "stale_date": 1763650000}}
 * </pre>
 * {@code stale_date} (optional, epoch seconds, the APNs Live Activity field of the same name) turns the
 * activity stale at that time; an update without it keeps the stored one. APNs' {@code relevance_score}
 * has no Android counterpart (a notification has no relevance score) and is not read.
 * {@code key} is the activity id (the iOS {@code activity_id}); {@code template} defaults to
 * {@code key}, as in OneSignal's guide where the key picks the layout. Attributes may be omitted on
 * update and end: the stored ones are used.
 *
 * <p>One owner: this never initializes OneSignal; the app's own OneSignal setup does.
 */
@Keep
public final class OneSignalLiveUpdates implements INotificationServiceExtension {
    @Override
    public void onNotificationReceived(INotificationReceivedEvent event) {
        JSONObject data = event.getNotification().getAdditionalData();
        JSONObject live = data == null ? null : data.optJSONObject("live_notification");
        if (live == null) return;
        // Discard now (OneSignal SDK 5.1.14+): the no-argument form would hold the worker up to 30 s.
        event.preventDefault(true);
        String key = live.optString("key", "");
        String template = live.optString("template", key);
        long stale = live.optLong("stale_date", 0) > 0 ? live.optLong("stale_date") * 1000L
                : "start".equals(live.optString("event")) ? 0 : LiveUpdates.KEEP_STALE;
        LiveUpdates.apply(event.getContext(), live.optString("event", ""), key, template,
                live.optJSONObject("event_attributes"), live.optJSONObject("event_updates"), "push", null, null, stale);
    }
}
