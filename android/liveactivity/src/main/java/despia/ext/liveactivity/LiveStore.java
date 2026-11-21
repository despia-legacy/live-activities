package despia.ext.liveactivity;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The activities this app shows, kept across process deaths: a OneSignal push can arrive in a fresh
 * process, an update may omit the attributes (they are read back from here), and a dismissal must
 * be remembered so the next update push does not bring the notification back.
 *
 * <p>One SharedPreferences file, one JSON record per activity id:
 * {template, attributes, state, phase, dismissed, nid, origin}. Every method holds the class lock,
 * because the bridge (main thread), OneSignal's worker and the dismiss receiver all use it.
 */
final class LiveStore {
    private static final String FILE = "nx_liveactivity";
    private static final String PREFIX = "a.";
    private static final Object LOCK = new Object();
    /** Not an activity id (ids never start with '#'). */
    private static final String LAST = "#last_nid";

    private final SharedPreferences prefs;

    LiveStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    JSONObject get(String id) {
        synchronized (LOCK) {
            String raw = prefs.getString(PREFIX + id, null);
            if (raw == null) return null;
            try {
                return new JSONObject(raw);
            } catch (JSONException e) {
                // An unreadable record is dropped: the next start writes a fresh one.
                prefs.edit().remove(PREFIX + id).commit();
                return null;
            }
        }
    }

    /** Writes synchronously (commit), so a process killed right after a push keeps the record. */
    boolean put(String id, JSONObject record) {
        synchronized (LOCK) {
            return prefs.edit().putString(PREFIX + id, record.toString()).commit();
        }
    }

    void remove(String id) {
        synchronized (LOCK) {
            prefs.edit().remove(PREFIX + id).commit();
        }
    }

    /**
     * The notification id for an activity: kept while it is stored, never another record's, and
     * never one issued before (the last issued id is kept), so a new activity cannot replace the
     * final state an ended one still shows.
     */
    int notificationId(String id) {
        synchronized (LOCK) {
            Map<String, Integer> taken = new HashMap<>();
            for (String key : ids()) {
                JSONObject r = get(key);
                if (r != null && r.has("nid")) taken.put(key, r.optInt("nid"));
            }
            if (taken.containsKey(id)) return taken.get(id);
            taken.put(LAST, prefs.getInt(LAST, LiveCore.BASE_ID - 1));
            int nid = LiveCore.allocate(taken, id);
            prefs.edit().putInt(LAST, nid).commit();
            return nid;
        }
    }

    List<String> ids() {
        synchronized (LOCK) {
            List<String> out = new ArrayList<>();
            Iterator<String> it = prefs.getAll().keySet().iterator();
            while (it.hasNext()) {
                String k = it.next();
                if (k.startsWith(PREFIX)) out.add(k.substring(PREFIX.length()));
            }
            return out;
        }
    }
}
