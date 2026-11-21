package despia.ext.liveactivity;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;

import com.despia.nativeext.ExtCall;
import com.despia.nativeext.ExtContext;
import com.despia.nativeext.ExtResult;
import com.despia.nativeext.NativeExtension;

import java.util.UUID;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Android's counterpart of iOS Live Activities: Live Updates (Android 16, promoted ongoing
 * notifications with Notification.ProgressStyle), and the same ongoing notification, updated in
 * place, on Android 7 to 15. Android has no Live Activities, no Dynamic Island and no push-to-start
 * token: a server starts, updates and ends one with an ordinary OneSignal data push
 * (OneSignalLiveUpdates), delivered also while the app is closed.
 *
 * <p>Routes and events have the iOS names and data. Replies are made once, on the main thread.
 * The notification permission (API 33+) is asked only by the {@code permission} route, through the
 * activity's ActivityResultRegistry under {@link ExtContext#resultKey}; a late answer after the
 * call timed out starts nothing.
 */
public final class LiveActivityExtension implements NativeExtension {
    private ExtContext ctx;
    private ActivityResultLauncher<String> permission;
    private ComponentActivity registeredOn;
    private ExtCall pendingCall;
    private ExtResult.Callback pendingReply;

    @Override
    public void onCreate(ExtContext context) {
        ctx = context;
        LiveUpdates.ext = context;
        LiveNotifier.ensureChannels(context.app());
    }

    @Override
    public void onActivityEvent(Activity activity, ActivityEvent event) {
        if (!(activity instanceof ComponentActivity)) return;
        ComponentActivity a = (ComponentActivity) activity;
        if (event == ActivityEvent.CREATED) {
            if (permission != null) permission.unregister();
            registeredOn = a;
            permission = a.getActivityResultRegistry().register(ctx.resultKey("notifications"),
                    new ActivityResultContracts.RequestPermission(), (Boolean granted) -> {
                        ExtResult.Callback reply = pendingReply;
                        ExtCall call = pendingCall;
                        pendingReply = null;
                        pendingCall = null;
                        if (reply == null) return;
                        if (!call.isActive()) {
                            reply.reply(ExtResult.error("timeout", "the answer came after the call timed out"));
                            return;
                        }
                        try {
                            reply.reply(ok(authorization()));
                        } catch (JSONException e) {
                            reply.reply(ExtResult.error("handler_error", "reply could not be built"));
                        }
                    });
        } else if (event == ActivityEvent.DESTROYED && registeredOn == a && permission != null) {
            permission.unregister();
            permission = null;
            registeredOn = null;
            if (pendingReply != null) {
                ExtResult.Callback reply = pendingReply;
                pendingReply = null;
                pendingCall = null;
                reply.reply(ExtResult.error("unavailable", "the activity was destroyed"));
            }
        }
    }

    @Override
    public void handle(ExtCall call, ExtResult.Callback reply) {
        try {
            switch (call.route) {
                case "start":
                    reply.reply(start(call));
                    return;
                case "update":
                    reply.reply(update(call));
                    return;
                case "end":
                    reply.reply(end(call));
                    return;
                case "list":
                    reply.reply(list());
                    return;
                case "permission":
                    permission(call, reply);
                    return;
                case "settings":
                    reply.reply(settings());
                    return;
                default:
                    reply.reply(ExtResult.error("not_registered", call.route));
            }
        } catch (JSONException e) {
            reply.reply(ExtResult.error("handler_error", "reply could not be built"));
        }
    }

    private static String str(ExtCall call, String key) {
        Object v = call.params.get(key);
        return v instanceof String ? (String) v : null;
    }

    private static ExtResult ok(JSONObject data) {
        return ExtResult.ok(data);
    }

    /** stale_minutes as a stale date (epoch ms), 0 for none, or LiveUpdates.KEEP_STALE when not given. */
    private static long staleAt(ExtCall call, long absent) {
        Object v = call.params.get("stale_minutes");
        return v instanceof Number ? System.currentTimeMillis() + ((Number) v).longValue() * 60_000L : absent;
    }

    /**
     * A named platform difference in the result, never a silent drop: iOS ranks a person's Live
     * Activities by ActivityContent.relevanceScore; an Android notification has no relevance score
     * (Notification.Builder offers none; the system orders notifications by channel importance and
     * time), so the value is not applied there.
     */
    private static JSONObject differences(ExtCall call, JSONObject out) throws JSONException {
        if (call.params.get("relevance") instanceof Number) {
            out.put("platformDifferences", new JSONObject().put("relevance",
                    "Android notifications have no relevance score; the system orders them"));
        }
        return out;
    }

    private ExtResult start(ExtCall call) throws JSONException {
        String name = str(call, "template");
        LiveTemplate t = LiveCore.validTemplate(name) ? LiveTemplates.find(name) : null;
        if (t == null) return ExtResult.error("unknown_template", "no template " + name + "; see LiveTemplates.java");
        String id = str(call, "activity_id");
        if (id == null) id = "la-" + UUID.randomUUID().toString();
        String phase = LiveUpdates.phase(id);
        JSONObject stored = new LiveStore(ctx.app()).get(id);
        if (stored != null && !stored.optBoolean("dismissed", false) && !"ended".equals(stored.optString("phase"))
                || "active".equals(phase) || "stale".equals(phase)) {
            return ExtResult.error("exists", "an activity with this id is running");
        }
        JSONObject[] p;
        try {
            p = LiveCore.prepareStart(str(call, "attributes"), str(call, "state"), id);
        } catch (LiveCore.Refusal r) {
            return ExtResult.error(r.code, r.getMessage());
        }
        LiveUpdates.Outcome o = LiveUpdates.apply(ctx.app(), "start", id, name, p[0], p[1], "app", null, null,
                staleAt(call, 0));
        if (!o.action.equals("post")) return refusal(o);
        return ok(differences(call, new JSONObject().put("activityId", id).put("template", name)
                .put("state", phaseOf(id))));
    }

    private ExtResult update(ExtCall call) throws JSONException {
        String id = str(call, "activity_id");
        JSONObject rec = LiveCore.validId(id) ? new LiveStore(ctx.app()).get(id) : null;
        if (rec == null || rec.optBoolean("dismissed", false)) {
            return ExtResult.error("unknown_activity", "no running activity with this id");
        }
        JSONObject attrs = rec.optJSONObject("attributes");
        JSONObject st;
        try {
            st = LiveCore.prepareState(str(call, "state"), LiveCore.utf8(attrs == null ? "" : attrs.toString()));
        } catch (LiveCore.Refusal r) {
            return ExtResult.error(r.code, r.getMessage());
        }
        LiveUpdates.Outcome o = LiveUpdates.apply(ctx.app(), "update", id, null, null, st, "app",
                str(call, "alert_title"), str(call, "alert_body"), staleAt(call, LiveUpdates.KEEP_STALE));
        if (!o.action.equals("post")) return refusal(o);
        return ok(differences(call, new JSONObject().put("activityId", id).put("template", o.template)
                .put("state", phaseOf(id))));
    }

    /**
     * As iOS: "default" keeps the final state on screen (no longer ongoing, so it can be swiped
     * away) for up to 4 hours, "after" for dismiss_minutes, "immediate" removes it now. The
     * system removes it on time (Notification.Builder.setTimeoutAfter), also if the process dies.
     * Android 7.x has no timeout: there the notification is removed at once.
     */
    private ExtResult end(ExtCall call) throws JSONException {
        String id = str(call, "activity_id");
        JSONObject rec = LiveCore.validId(id) ? new LiveStore(ctx.app()).get(id) : null;
        if (rec == null || rec.optBoolean("dismissed", false)) {
            return ExtResult.error("unknown_activity", "no running activity with this id");
        }
        String dismiss = str(call, "dismiss");
        Object minutes = call.params.get("dismiss_minutes");
        long keepMs;
        if ("immediate".equals(dismiss)) {
            keepMs = 0;
        } else if ("after".equals(dismiss)) {
            if (!(minutes instanceof Number)) return ExtResult.error("bad_value", "dismiss=after needs dismiss_minutes");
            keepMs = ((Number) minutes).longValue() * 60_000L;
        } else {
            keepMs = LiveNotifier.ENDED_KEEP_MS;
        }
        JSONObject st = null;
        String state = str(call, "state");
        if (state != null) {
            JSONObject attrs = rec.optJSONObject("attributes");
            try {
                st = LiveCore.prepareState(state, LiveCore.utf8(attrs == null ? "" : attrs.toString()));
            } catch (LiveCore.Refusal r) {
                return ExtResult.error(r.code, r.getMessage());
            }
        }
        LiveUpdates.finish(ctx.app(), id, st, keepMs);
        return ok(new JSONObject().put("activityId", id).put("template", rec.optString("template")).put("state", "ended"));
    }

    private ExtResult list() throws JSONException {
        LiveStore store = new LiveStore(ctx.app());
        JSONArray rows = new JSONArray();
        for (String id : store.ids()) {
            LiveUpdates.refresh(ctx.app(), id, 0);
            JSONObject r = store.get(id);
            if (r == null || r.optBoolean("dismissed", false) || "ended".equals(r.optString("phase"))) continue;
            rows.put(new JSONObject().put("activityId", id).put("template", r.optString("template"))
                    .put("state", r.optString("phase", "active")).put("origin", r.optString("origin", "app"))
                    .put("pushToken", JSONObject.NULL)
                    .put("attributes", r.optJSONObject("attributes")).put("content", r.optJSONObject("state")));
        }
        JSONArray names = new JSONArray();
        for (String n : LiveTemplates.names()) names.put(n);
        return ok(authorization().put("templates", names).put("activities", rows).put("pushToStart", new JSONObject()));
    }

    private JSONObject authorization() throws JSONException {
        Context app = ctx.app();
        return new JSONObject()
                .put("enabled", LiveNotifier.enabled(app))
                .put("permission", Build.VERSION.SDK_INT < 33
                        || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                .put("promoted", LiveNotifier.promotable(app))
                .put("liveUpdates", Build.VERSION.SDK_INT >= 36)
                .put("onesignal", oneSignalLinked() ? "linked" : "not_linked")
                .put("platform", "android");
    }

    /** Whether the base app ships OneSignal (the class is present), without loading or starting it. */
    private boolean oneSignalLinked() {
        try {
            Class.forName("com.onesignal.OneSignal", false, ctx.app().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private void permission(ExtCall call, ExtResult.Callback reply) throws JSONException {
        Context app = ctx.app();
        if (Build.VERSION.SDK_INT < 33
                || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            reply.reply(ok(authorization()));
            return;
        }
        if (permission == null) {
            reply.reply(ExtResult.error("unavailable", "no activity is showing"));
            return;
        }
        if (pendingReply != null) {
            if (pendingCall.isActive()) {
                reply.reply(ExtResult.error("busy", "the notification prompt is already showing"));
                return;
            }
            pendingReply.reply(ExtResult.error("timeout", "the prompt timed out"));
        }
        pendingCall = call;
        pendingReply = reply;
        permission.launch(Manifest.permission.POST_NOTIFICATIONS);
    }

    /**
     * Android 16: the page where a person allows Live Updates for this app
     * (Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS). Below that, the app's notification settings.
     */
    private ExtResult settings() throws JSONException {
        Context app = ctx.app();
        Intent i = Build.VERSION.SDK_INT >= 36
                ? new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                : Build.VERSION.SDK_INT >= 26 ? new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.getPackageName()));
        i.putExtra(Settings.EXTRA_APP_PACKAGE, app.getPackageName());
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(app.getPackageManager()) == null) {
            return ExtResult.error("unavailable", "no settings page on this device");
        }
        app.startActivity(i);
        return ok(new JSONObject().put("opened", true));
    }

    private static String phaseOf(String id) {
        String p = LiveUpdates.phase(id);
        return "stale".equals(p) ? "stale" : "active";
    }

    private static ExtResult refusal(LiveUpdates.Outcome o) {
        switch (o.action) {
            case "notifications_off":
                return ExtResult.error("activities_disabled", "notifications are off for this app");
            case "ignore_unknown_template":
                return ExtResult.error("unknown_template", "no template " + o.template);
            case "skip_dismissed":
            case "ignore_unknown_activity":
                return ExtResult.error("unknown_activity", "no running activity with this id");
            case "store_failed":
                return ExtResult.error("storage_failed", "the activity could not be saved");
            default:
                return ExtResult.error("bad_value", "the event was not applied");
        }
    }
}
