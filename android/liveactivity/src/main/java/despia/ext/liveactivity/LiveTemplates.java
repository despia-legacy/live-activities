package despia.ext.liveactivity;

import despia.ext.liveactivity.templates.DeliveryTemplate;
import despia.ext.liveactivity.templates.ScoreTemplate;
import despia.ext.liveactivity.templates.TimerTemplate;

/** The templates the page and OneSignal pushes can use. One line per template. */
public final class LiveTemplates {
    private static final LiveTemplate[] ALL = {
        new DeliveryTemplate(),
        new ScoreTemplate(),
        new TimerTemplate(),
    };

    private LiveTemplates() {
    }

    public static LiveTemplate find(String name) {
        if (name == null) return null;
        for (LiveTemplate t : ALL) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    public static String[] names() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = ALL[i].name();
        return out;
    }
}
