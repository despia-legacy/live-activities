package despia.ext.liveactivity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Every decision of the Live Updates extension that needs no Android API: ids, payload checks, the
 * page-visible life of an activity and what a pushed event does. No android.* import, so the shared
 * rules run it on a plain JVM. iOS's LiveActivityCore.swift
 * makes the same decisions from the same vectors.
 */
public final class LiveCore {
    /** The same limit as iOS (ActivityKit: 4 KB of static and dynamic data). FCM also caps a push at 4 KB. */
    public static final int MAX_PAYLOAD_BYTES = 4096;
    /** The bytes {@code "activityId":"",} adds when the id is injected into the attributes. */
    public static final int INJECTED_KEY_BYTES = 16;

    private LiveCore() {
    }

    /** A malformed or oversized value: code bad_value or too_large. */
    public static final class Refusal extends Exception {
        public final String code;

        Refusal(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    /** Ids the page, the server and OneSignal share: 1 to 64 of A-Z a-z 0-9 . _ - */
    public static boolean validId(String id) {
        if (id == null) return false;
        byte[] b = id.getBytes(StandardCharsets.UTF_8);
        if (b.length < 1 || b.length > 64) return false;
        for (byte c : b) {
            boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || c == '.' || c == '_' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** Template names: 1 to 32 of a-z 0-9 _ */
    public static boolean validTemplate(String name) {
        if (name == null) return false;
        byte[] b = name.getBytes(StandardCharsets.UTF_8);
        if (b.length < 1 || b.length > 32) return false;
        for (byte c : b) {
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || c == '_')) return false;
        }
        return true;
    }

    static int utf8(String s) {
        return s == null ? 0 : s.getBytes(StandardCharsets.UTF_8).length;
    }

    /** The size rule, measured on what the page or server sent, plus the injected id. */
    public static int size(String attributes, String state, String injectedId) {
        return utf8(attributes) + utf8(state) + (injectedId == null ? 0 : INJECTED_KEY_BYTES + utf8(injectedId));
    }

    static JSONObject object(String json, String field) throws Refusal {
        if (json == null) throw new Refusal("bad_value", field + " is not a JSON object");
        String t = json.trim();
        if (!t.startsWith("{")) throw new Refusal("bad_value", field + " is not a JSON object");
        try {
            return new JSONObject(t);
        } catch (JSONException e) {
            throw new Refusal("bad_value", field + " is not a JSON object");
        }
    }

    /**
     * Start: {attributes with activityId set to id, state}, both checked. An attributes object that
     * names another activityId is refused, never overwritten.
     */
    public static JSONObject[] prepareStart(String attributes, String state, String id) throws Refusal {
        if (!validId(id)) throw new Refusal("bad_value", "activity_id must be 1 to 64 of A-Z a-z 0-9 . _ -");
        JSONObject attrs = object(attributes, "attributes");
        JSONObject st = object(state, "state");
        String injected = id;
        if (attrs.has("activityId")) {
            Object given = attrs.opt("activityId");
            if (!(given instanceof String) || !given.equals(id)) {
                throw new Refusal("bad_value", "attributes.activityId differs from activity_id");
            }
            injected = null;
        }
        int n = size(attributes, state, injected);
        if (n > MAX_PAYLOAD_BYTES) {
            throw new Refusal("too_large", "attributes and state are " + n + " bytes; the limit is " + MAX_PAYLOAD_BYTES);
        }
        try {
            attrs.put("activityId", id);
        } catch (JSONException e) {
            throw new Refusal("bad_value", "attributes could not take activityId");
        }
        return new JSONObject[] {attrs, st};
    }

    /** Update and end: the new state, checked against the stored attributes' size. */
    public static JSONObject prepareState(String state, int attributesBytes) throws Refusal {
        JSONObject st = object(state, "state");
        int n = attributesBytes + utf8(state);
        if (n > MAX_PAYLOAD_BYTES) {
            throw new Refusal("too_large", "attributes and state are " + n + " bytes; the limit is " + MAX_PAYLOAD_BYTES);
        }
        return st;
    }

    // ------------------------------------------------------------------------------------ ledger

    /**
     * The page-visible life of each activity id, the same rules as iOS's LiveLedger:
     * L1 phases only move forward (active and stale may alternate; dismissed after ended is the one
     * move between the final phases); L2 each change is reported once; L3 the registration is
     * released once, on the first of ended or dismissed; L4 dismissed says "user" while running,
     * "end" after an end (an end the app asked for that removes it at once is reported ended first); L5 a new start of an ended or dismissed id begins a new life; L6 only the
     * current life (its notification id) reports, so the late dismissal of an earlier life's
     * notification never touches the activity running now. Effects are "state:id:phase",
     * "dismissed:id:by", "release:id".
     */
    public static final class Ledger {
        private final Map<String, String> phases = new HashMap<>();
        private final Map<String, String> lives = new HashMap<>();
        private final Map<String, String> endings = new HashMap<>();

        static int rank(String phase) {
            switch (phase) {
                case "active":
                case "stale":
                    return 1;
                case "ended":
                    return 2;
                case "dismissed":
                    return 3;
                default:
                    return 0;
            }
        }

        public synchronized String phase(String id) {
            return phases.get(id);
        }

        /** Seeds a phase and life read back from storage, reporting nothing (a new process). */
        public synchronized void restore(String id, String phase, String life) {
            if (rank(phase) == 0) return;
            phases.put(id, phase);
            lives.put(id, life);
        }

        /** Whether life is the id's current life. An id with no life yet takes the first it sees. */
        public synchronized boolean isCurrent(String id, String life) {
            String cur = lives.get(id);
            return cur == null || cur.equals(life);
        }

        /** Who dismissed it: the app asked to end the current life of id; a null life withdraws it (the end failed). */
        public synchronized void ending(String id, String life) {
            if (life == null) endings.remove(id);
            else if (isCurrent(id, life)) endings.put(id, life);
        }

        /** L5, L6: a new start begins a new life of its id; a phase that ended or was dismissed is forgotten. */
        public synchronized void begin(String id, String life) {
            lives.put(id, life);
            endings.remove(id);
            String p = phases.get(id);
            if (p != null && rank(p) > 1) {
                phases.remove(id);
            }
        }

        public synchronized List<String> observe(String id, String next, String life) {
            List<String> out = new ArrayList<>();
            int nr = rank(next);
            if (nr == 0) return out;
            if (!isCurrent(id, life)) return out;
            lives.put(id, life);
            String current = phases.get(id);
            if (next.equals("dismissed") && life.equals(endings.get(id)) && (current == null || rank(current) == 1)) {
                out.addAll(observe(id, "ended", life));
                out.addAll(observe(id, "dismissed", life));
                return out;
            }
            if (current != null) {
                int cr = rank(current);
                if (current.equals(next)) return out;
                if (nr < cr) return out;
                if (cr == nr && cr > 1) return out;
            }
            phases.put(id, next);
            out.add("state:" + id + ":" + next);
            boolean wasRunning = current == null || rank(current) == 1;
            if (next.equals("dismissed")) out.add("dismissed:" + id + ":" + ("ended".equals(current) ? "end" : "user"));
            if (nr >= 2 && wasRunning) out.add("release:" + id);
            return out;
        }
    }

    /**
     * S1, the stale polyfill: iOS marks an activity stale at its stale date (ActivityContent.staleDate)
     * and reports it; Android has no such notification state, so the stored deadline decides. A running
     * activity is "stale" from staleAt (epoch ms, 0 for none) on and "active" before it; ended and
     * dismissed stay as they are.
     */
    public static String timedPhase(String phase, long staleAt, long now) {
        if (!"active".equals(phase) && !"stale".equals(phase)) return phase;
        return staleAt > 0 && now >= staleAt ? "stale" : "active";
    }

    // ------------------------------------------------------------------------------- push events

    /**
     * What one start, update or end does, whether the page or a OneSignal push sent it (OneSignal,
     * "Android Live Updates": start clears a dismissal and posts; update is skipped after the person
     * dismissed it, so a later push never brings back what they swiped away; end cancels and clears).
     *
     * @param known      a stored record exists for the id
     * @param dismissed  the stored record was dismissed by the person
     * @param renderable a known template and attributes are available (sent now or stored)
     * @return "post", "cancel", "skip_dismissed", "ignore_unknown_activity",
     *         "ignore_unknown_template" or "ignore_bad_event"
     */
    public static String decide(String event, boolean known, boolean dismissed, boolean templateKnown, boolean renderable) {
        if (event == null) return "ignore_bad_event";
        switch (event) {
            case "start":
                if (!templateKnown) return "ignore_unknown_template";
                return renderable ? "post" : "ignore_unknown_activity";
            case "update":
                if (!known && !renderable) return "ignore_unknown_activity";
                if (known && dismissed) return "skip_dismissed";
                if (!templateKnown) return "ignore_unknown_template";
                return renderable ? "post" : "ignore_unknown_activity";
            case "end":
                return "cancel";
            default:
                return "ignore_bad_event";
        }
    }

    /**
     * The Android notification id of an activity: stable for its key while it is stored, and never
     * the id of another stored activity. Ids start at BASE_ID and step by one.
     */
    public static final int BASE_ID = 0x4E580001;

    public static int allocate(Map<String, Integer> taken, String key) {
        Integer have = taken.get(key);
        if (have != null) return have;
        int next = BASE_ID;
        for (Integer v : taken.values()) {
            if (v != null && v >= next) next = v + 1;
        }
        taken.put(key, next);
        return next;
    }
}
