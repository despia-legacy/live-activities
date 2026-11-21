package despia.ext.liveactivity.templates;

import android.app.Notification;
import android.content.Context;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.json.JSONObject;

import despia.ext.liveactivity.LiveTemplate;
import despia.ext.liveactivity.R;

/**
 * Template "delivery": order or delivery tracking with step progress and an arrival time. The keys
 * are the iOS template's (DeliveryActivity.swift):
 * attributes {orderNumber, merchant}; state {status, step, stepCount, etaEpoch?, courier?}.
 */
public final class DeliveryTemplate implements LiveTemplate {
    @Override
    public String name() {
        return "delivery";
    }

    @Override
    public int smallIcon() {
        return R.drawable.nx_live_delivery;
    }

    @Override
    public void render(Context ctx, Notification.Builder b, JSONObject attributes, JSONObject state, boolean live) {
        int stepCount = Math.max(0, state.optInt("stepCount", 0));
        int step = Math.max(0, Math.min(state.optInt("step", 0), Math.max(stepCount, 0)));
        boolean done = stepCount > 0 && step >= stepCount;
        double eta = state.optDouble("etaEpoch", 0);
        String etaText = done ? "Delivered"
                : eta > 0 ? DateFormat.getTimeFormat(ctx).format(new Date((long) (eta * 1000)))
                : stepCount > 0 ? step + "/" + stepCount : "";

        b.setContentTitle(attributes.optString("merchant", ""));
        String courier = state.optString("courier", "");
        String status = state.optString("status", "");
        b.setContentText(courier.isEmpty() ? status : status + " · " + courier);
        b.setSubText("#" + attributes.optString("orderNumber", ""));
        if (eta > 0 && !done) {
            b.setWhen((long) (eta * 1000)).setShowWhen(true);
        }

        if (live) {
            Notification.ProgressStyle style = new Notification.ProgressStyle().setStyledByProgress(true);
            if (stepCount > 0) {
                List<Notification.ProgressStyle.Segment> segments = new ArrayList<>();
                for (int i = 0; i < stepCount; i++) segments.add(new Notification.ProgressStyle.Segment(100));
                style.setProgressSegments(segments).setProgress(step * 100);
            } else {
                style.setProgressIndeterminate(true);
            }
            b.setStyle(style);
            if (!etaText.isEmpty()) b.setShortCriticalText(etaText);
        } else {
            if (stepCount > 0) b.setProgress(stepCount, step, false);
            else b.setProgress(0, 0, true);
        }
    }
}
