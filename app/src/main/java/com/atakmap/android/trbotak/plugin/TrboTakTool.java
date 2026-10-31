
package com.atakmap.android.trbotak.plugin;

import android.content.Context;

import com.atak.plugins.impl.AbstractPluginTool;
import com.atakmap.android.trbotak.TrboTakDropDownReceiver;
import com.cloudrf.trbotak.R;

/** Toolbar icon; firing its intent is the only way to open the panel. */
public class TrboTakTool extends AbstractPluginTool {

    public TrboTakTool(final Context context) {
        super(context,
                context.getString(R.string.app_name),
                context.getString(R.string.app_desc),
                context.getResources().getDrawable(R.drawable.ic_launcher,
                        context.getTheme()),
                TrboTakDropDownReceiver.SHOW_PLUGIN);
    }
}