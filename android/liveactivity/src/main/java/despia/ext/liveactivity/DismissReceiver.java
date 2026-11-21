package despia.ext.liveactivity;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The person swiped a Live Update away or tapped Remove. Recorded, so the next update push does not
 * bring it back (only a new start does). Not exported: only this app's PendingIntents reach it.
 */
public final class DismissReceiver extends BroadcastReceiver {
    static final String EXTRA_ID = "despia.ext.liveactivity.id";
    /** The notification id: which life of the activity id was removed. */
    static final String EXTRA_NID = "despia.ext.liveactivity.nid";

    @Override
    public void onReceive(Context context, Intent intent) {
        String id = intent.getStringExtra(EXTRA_ID);
        int nid = intent.getIntExtra(EXTRA_NID, 0);
        if (LiveCore.validId(id) && nid != 0) LiveUpdates.dismissed(context, id, nid);
    }
}
