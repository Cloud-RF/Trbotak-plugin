
package com.atakmap.android.trbotak;

import android.content.Context;
import android.content.Intent;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;
import com.cloudrf.trbotak.R;
import com.atakmap.coremap.log.Log;

public class TrboTakMapComponent extends DropDownMapComponent {

    public static final String TAG = "TrboTakMapComponent";

    private TrboTakDropDownReceiver receiver;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {
        // plugin resources resolve against this context, so it needs our theme
        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        receiver = new TrboTakDropDownReceiver(view, context);

        DocumentedIntentFilter filter = new DocumentedIntentFilter();
        filter.addAction(TrboTakDropDownReceiver.SHOW_PLUGIN,
                "Show the TrboTak UDP panel");
        registerDropDownReceiver(receiver, filter);

        Log.d(TAG, "registered");
    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        if (receiver != null) {
            receiver.dispose();
            receiver = null;
        }
        super.onDestroyImpl(context, view);
    }
}