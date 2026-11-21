package despia.ext.liveactivity.templates;

import android.app.Notification;
import android.content.Context;

import org.json.JSONObject;

import despia.ext.liveactivity.LiveTemplate;
import despia.ext.liveactivity.R;

/**
 * Template "score": a live sports score card. The keys are the iOS template's (ScoreActivity.swift):
 * attributes {league, homeTeam, awayTeam, homeShort?, awayShort?};
 * state {homeScore, awayScore, period, clock?, final?}.
 */
public final class ScoreTemplate implements LiveTemplate {
    @Override
    public String name() {
        return "score";
    }

    @Override
    public int smallIcon() {
        return R.drawable.nx_live_score;
    }

    private static String three(JSONObject a, String shortKey, String nameKey) {
        String s = a.optString(shortKey, "");
        if (!s.isEmpty()) return s;
        String n = a.optString(nameKey, "");
        return (n.length() > 3 ? n.substring(0, 3) : n).toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public void render(Context ctx, Notification.Builder b, JSONObject attributes, JSONObject state, boolean live) {
        int home = state.optInt("homeScore", 0);
        int away = state.optInt("awayScore", 0);
        boolean isFinal = state.optBoolean("final", false);
        String clock = state.optString("clock", "");
        String period = state.optString("period", "");
        String status = isFinal ? "Final" : (clock.isEmpty() ? period : period + " · " + clock);

        b.setContentTitle(attributes.optString("homeTeam", "") + "  " + home + " – " + away + "  "
                + attributes.optString("awayTeam", ""));
        b.setContentText(status);
        b.setSubText(attributes.optString("league", ""));
        b.setCategory(Notification.CATEGORY_STATUS);
        if (live) {
            // Status-bar chip, as iOS's compact leading and trailing: "ARS 2-1 CHE". The system may
            // shorten a chip, so a long one falls back to the score alone, which always fits.
            String chip = three(attributes, "homeShort", "homeTeam") + " " + home + "-" + away + " "
                    + three(attributes, "awayShort", "awayTeam");
            b.setShortCriticalText(chip.length() <= 11 ? chip : home + "-" + away);
        }
    }
}
