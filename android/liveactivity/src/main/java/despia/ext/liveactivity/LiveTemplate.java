package despia.ext.liveactivity;

import android.app.Notification;
import android.content.Context;

import org.json.JSONObject;

/**
 * One Live Update template: it fills an ongoing notification from the same JSON the iOS template of
 * the same name decodes (ios/shared/templates/), so one server payload drives both platforms.
 *
 * <p>To add a template, copy one under templates/, rename it and its {@link #name()}, and add it to
 * {@link LiveTemplates}.
 *
 * <p>Keep the notification promotable to a Live Update (Android 16): no custom RemoteViews, no
 * {@code setColorized(true)}, a content title, and the standard, BigText or Progress style.
 */
public interface LiveTemplate {
    /** The {@code template} value the page and the server use, lower case. */
    String name();

    /** A monochrome status-bar icon of this module. */
    int smallIcon();

    /**
     * Sets title, text, progress, chronometer and the status-bar chip on {@code b}.
     *
     * @param live true on API 36 and later: {@code Notification.ProgressStyle} and
     *             {@code setShortCriticalText} exist. Below that, use {@code setProgress} and
     *             the plain fields; the notification still updates in place, without promotion.
     */
    void render(Context ctx, Notification.Builder b, JSONObject attributes, JSONObject state, boolean live);
}
