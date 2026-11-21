package despia.ext.liveactivity.templates;

import android.app.Notification;
import android.content.Context;

import java.util.Locale;

import org.json.JSONObject;

import despia.ext.liveactivity.LiveTemplate;
import despia.ext.liveactivity.R;

/**
 * Template "timer": a countdown the system animates itself (a chronometer counting down to
 * {@code endsAt}), with no update per second. The keys are the iOS template's (TimerActivity.swift):
 * attributes {title}; state {startedAt, endsAt, label, pausedRemaining?}. Times are Unix seconds.
 */
public final class TimerTemplate implements LiveTemplate {
    @Override
    public String name() {
        return "timer";
    }

    @Override
    public int smallIcon() {
        return R.drawable.nx_live_timer;
    }

    static String clock(long seconds) {
        long s = Math.max(0, seconds);
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public void render(Context ctx, Notification.Builder b, JSONObject attributes, JSONObject state, boolean live) {
        double started = state.optDouble("startedAt", 0);
        double ends = Math.max(state.optDouble("endsAt", 0), started);
        boolean paused = state.has("pausedRemaining") && !state.isNull("pausedRemaining");
        long now = System.currentTimeMillis();

        b.setContentTitle(attributes.optString("title", ""));
        b.setCategory(Notification.CATEGORY_STOPWATCH);
        String label = state.optString("label", "");
        if (paused) {
            String left = clock((long) state.optDouble("pausedRemaining", 0));
            b.setContentText(label.isEmpty() ? left : label + " · " + left);
            b.setShowWhen(false).setUsesChronometer(false);
            if (live) b.setShortCriticalText(left);
        } else {
            b.setContentText(label);
            b.setWhen((long) (ends * 1000)).setShowWhen(true).setUsesChronometer(true);
            if (android.os.Build.VERSION.SDK_INT >= 24) b.setChronometerCountDown(true);
        }

        long total = (long) ((ends - started) * 1000);
        int permille = total <= 0 ? 1000 : (int) Math.max(0, Math.min(1000, (now - (long) (started * 1000)) * 1000 / total));
        if (paused && total > 0) {
            permille = (int) Math.max(0, Math.min(1000, 1000 - (long) (state.optDouble("pausedRemaining", 0) * 1000) * 1000 / total));
        }
        if (live) {
            b.setStyle(new Notification.ProgressStyle()
                    .setProgressSegments(java.util.Collections.singletonList(new Notification.ProgressStyle.Segment(1000)))
                    .setProgress(permille));
        } else {
            b.setProgress(1000, permille, false);
        }
    }
}
