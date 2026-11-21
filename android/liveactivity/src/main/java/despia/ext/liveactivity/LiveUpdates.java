package despia.ext.liveactivity;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.despia.nativeext.ExtContext;

import java.util.List;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * One pipeline for every start, update and end, whether the page called a route or a OneSignal push
 * arrived (possibly in a process with no page). Decisions are LiveCore's; this class only stores,
 * posts and reports. Thread-safe: the bridge calls it on the main thread, OneSignal on its worker,
 * the dismiss receiver on the main thread of a possibly fresh process.
 */
final class LiveUpdates {
    private static final Object LOCK = new Object();
    private static final LiveCore.Ledger LEDGER = new LiveCore.Ledger();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Set in onCreate, which runs in Application.onCreate before OneSignal or a receiver gets here. */
    static volatile ExtContext ext;

    private LiveUpdates() {
    }

    /** The outcome of one event: LiveCore.decide's action, or "notifications_off" / "store_failed". */
    static final class Outcome {
        final String action;
        final String template;

        Outcome(String action, String template) {
            this.action = action;
            this.template = template;
        }
    }

    /** Keep the stored stale date (an update or push that names none, as iOS keeps its staleDate). */
    static final long KEEP_STALE = -1;

    /**
     * @param staleAt when the activity turns stale (epoch ms), 0 for never, or KEEP_STALE
     */
    static Outcome apply(Context ctx, String event, String id, String templateName, JSONObject attributes,
                         JSONObject state, String origin, String alertTitle, String alertBody, long staleAt) {
        if (!LiveCore.validId(id)) return new Outcome("ignore_bad_event", templateName);
        Context app = ctx.getApplicationContext();
        LiveStore store = new LiveStore(app);
        synchronized (LOCK) {
            JSONObject rec = store.get(id);
            restore(rec, id);
            boolean known = rec != null && !"ended".equals(rec.optString("phase"));
            boolean dismissed = rec != null && rec.optBoolean("dismissed", false);
            // An update or end keeps the template the activity started with; a start names its own.
            String name = rec != null && !"start".equals(event) ? rec.optString("template", templateName)
                    : templateName != null && !templateName.isEmpty() ? templateName
                    : rec == null ? null : rec.optString("template", null);
            LiveTemplate t = LiveTemplates.find(name);
            JSONObject attrs = attributes != null ? withId(attributes, id) : rec == null ? null : rec.optJSONObject("attributes");
            JSONObject st = state != null ? state : rec == null ? null : rec.optJSONObject("state");
            String action = LiveCore.decide(event, known, dismissed, t != null, t != null && attrs != null && st != null);
            switch (action) {
                case "post": {
                    if (!LiveNotifier.enabled(app)) return new Outcome("notifications_off", name);
                    int nid = rec != null && rec.has("nid") ? rec.optInt("nid") : store.notificationId(id);
                    // L5, L6: a start is a new life, named by its notification id.
                    if ("start".equals(event)) LEDGER.begin(id, life(nid));
                    long stale = staleAt != KEEP_STALE ? staleAt
                            : rec != null && !"start".equals(event) ? rec.optLong("staleAt", 0) : 0;
                    String phase = LiveCore.timedPhase("active", stale, System.currentTimeMillis());
                    JSONObject next = new JSONObject();
                    try {
                        next.put("template", name).put("attributes", attrs).put("state", st).put("phase", phase)
                                .put("dismissed", false).put("nid", nid).put("staleAt", stale)
                                .put("origin", rec != null ? rec.optString("origin", origin) : origin);
                    } catch (JSONException e) {
                        return new Outcome("store_failed", name);
                    }
                    if (!store.put(id, next)) return new Outcome("store_failed", name);
                    LiveNotifier.post(app, id, nid, t, attrs, st, alertTitle, alertBody);
                    report(LEDGER.observe(id, phase, life(nid)), name, next.optString("origin", origin));
                    scheduleStale(app, id, nid, stale);
                    return new Outcome(action, name);
                }
                case "cancel": {
                    if (rec != null) {
                        if (rec.has("nid")) LiveNotifier.cancel(app, rec.optInt("nid"));
                        store.remove(id);
                        report(LEDGER.observe(id, "ended", life(rec)), name, rec.optString("origin", origin));
                    }
                    return new Outcome(action, name);
                }
                default:
                    return new Outcome(action, name);
            }
        }
    }

    /**
     * Ends an activity from the page: with keepMs > 0 (API 26+) its final state stays, no longer
     * ongoing, until the system removes it after keepMs; otherwise it is removed now. Either way the
     * record goes, so later update pushes are ignored, and the page is told "ended".
     */
    static void finish(Context ctx, String id, JSONObject finalState, long keepMs) {
        Context app = ctx.getApplicationContext();
        LiveStore store = new LiveStore(app);
        synchronized (LOCK) {
            JSONObject rec = store.get(id);
            if (rec == null) return;
            restore(rec, id);
            LiveTemplate t = LiveTemplates.find(rec.optString("template", null));
            JSONObject attrs = rec.optJSONObject("attributes");
            JSONObject st = finalState != null ? finalState : rec.optJSONObject("state");
            if (rec.has("nid")) {
                int nid = rec.optInt("nid");
                if (keepMs > 0 && android.os.Build.VERSION.SDK_INT >= 26 && t != null && attrs != null && st != null) {
                    LiveNotifier.post(app, id, nid, t, attrs, st, null, null, false, keepMs);
                } else {
                    LiveNotifier.cancel(app, nid);
                }
            }
            store.remove(id);
            report(LEDGER.observe(id, "ended", life(rec)), rec.optString("template", ""), rec.optString("origin", "app"));
        }
    }

    /**
     * The person removed the notification nid: remembered (dismissed), the notification goes, the page
     * is told. Only for the id's current life: the swipe of an ended activity's final state, kept
     * on screen under its own nid after a new start of the same id, changes nothing.
     */
    static void dismissed(Context ctx, String id, int nid) {
        Context app = ctx.getApplicationContext();
        LiveStore store = new LiveStore(app);
        synchronized (LOCK) {
            JSONObject rec = store.get(id);
            if (rec == null) return;
            restore(rec, id);
            List<String> effects = LEDGER.observe(id, "dismissed", life(nid));
            if (effects.isEmpty()) return;
            try {
                rec.put("dismissed", true).put("phase", "dismissed");
            } catch (JSONException e) {
                return;
            }
            store.put(id, rec);
            if (rec.has("nid")) LiveNotifier.cancel(app, rec.optInt("nid"));
            report(effects, rec.optString("template", ""), rec.optString("origin", "app"));
        }
    }

    /**
     * Moves a running activity to stale (or back) by its stored stale date, and tells the page.
     * Called when the stale date passes in this process, and by list, so a fresh process reads it too.
     *
     * @param nid the life the check was scheduled for, or 0 for the current one
     */
    static void refresh(Context ctx, String id, int nid) {
        Context app = ctx.getApplicationContext();
        LiveStore store = new LiveStore(app);
        synchronized (LOCK) {
            JSONObject rec = store.get(id);
            if (rec == null || rec.optBoolean("dismissed", false) || !rec.has("nid")) return;
            if (nid != 0 && rec.optInt("nid") != nid) return;
            restore(rec, id);
            String was = rec.optString("phase", "active");
            String now = LiveCore.timedPhase(was, rec.optLong("staleAt", 0), System.currentTimeMillis());
            if (now.equals(was)) return;
            try {
                rec.put("phase", now);
            } catch (JSONException e) {
                return;
            }
            store.put(id, rec);
            report(LEDGER.observe(id, now, life(rec)), rec.optString("template", ""), rec.optString("origin", "app"));
        }
    }

    private static void scheduleStale(Context app, String id, int nid, long staleAt) {
        long wait = staleAt - System.currentTimeMillis();
        if (staleAt > 0 && wait > 0) MAIN.postDelayed(() -> refresh(app, id, nid), wait);
    }

    static String phase(String id) {
        return LEDGER.phase(id);
    }

    private static String life(int nid) {
        return String.valueOf(nid);
    }

    private static String life(JSONObject rec) {
        return rec.has("nid") ? life(rec.optInt("nid")) : "";
    }

    /** A new process: the ledger learns the stored phase and life before the first event. */
    private static void restore(JSONObject rec, String id) {
        if (rec != null && LEDGER.phase(id) == null) LEDGER.restore(id, rec.optString("phase", "active"), life(rec));
    }

    private static JSONObject withId(JSONObject attributes, String id) {
        try {
            return new JSONObject(attributes.toString()).put("activityId", id);
        } catch (JSONException e) {
            return attributes;
        }
    }

    /** Events reach a loaded page only (ExtContext.emit); list() is the readable state. */
    private static void report(List<String> effects, String template, String origin) {
        ExtContext c = ext;
        if (c == null) return;
        for (String e : effects) {
            String[] p = e.split(":", 3);
            JSONObject data = new JSONObject();
            try {
                if (p[0].equals("state")) {
                    data.put("activityId", p[1]).put("template", template).put("state", p[2]).put("origin", origin);
                    MAIN.post(() -> c.emit("state", data));
                } else if (p[0].equals("dismissed")) {
                    data.put("activityId", p[1]).put("template", template).put("by", p[2]);
                    MAIN.post(() -> c.emit("dismissed", data));
                }
                // "release": the stored record already went (end) or stays as the dismissal record.
            } catch (JSONException ignored) {
                // Building a JSON object from strings does not fail.
            }
        }
    }
}
