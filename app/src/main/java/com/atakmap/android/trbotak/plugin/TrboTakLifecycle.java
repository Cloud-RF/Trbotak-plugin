
package com.atakmap.android.trbotak.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.trbotak.TrboTakMapComponent;

import gov.tak.api.plugin.IServiceController;

/**
 * Plugin entry point.
 */
public class TrboTakLifecycle extends AbstractPlugin {

    public TrboTakLifecycle(IServiceController serviceController) {
        super(serviceController,
                new TrboTakTool(serviceController
                        .getService(PluginContextProvider.class)
                        .getPluginContext()),
                new TrboTakMapComponent());
    }
}