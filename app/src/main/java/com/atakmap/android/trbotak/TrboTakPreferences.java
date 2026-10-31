
package com.atakmap.android.trbotak;

import android.content.Context;

import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.coremap.log.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Typed access to the plugin's persisted settings.
 *
 * Backed by {@link AtakPreferences}, ATAK's wrapper for SharedPreferences.
 * Values therefore survive plugin reloads and travel with ATAK's
 * settings backup.
 */
public class TrboTakPreferences {

    public static final String TAG = "TrboTakPreferences";

    public static final String PREF_NIC = "trbotak.nic";
    public static final String PREF_REFRESH_SECONDS = "trbotak.refreshSeconds";
    public static final String PREF_COT_ADDRESS = "trbotak.cotAddress";

    /** JSON array of {id, name}; the radios enrolled for polling. */
    public static final String PREF_RADIOS = "trbotak.radios";

    public static final String NIC_WIFI = "Wi-Fi";
    public static final String NIC_BLUETOOTH = "Bluetooth";
    public static final String NIC_USB = "USB";

    public static final String DEFAULT_NIC = NIC_BLUETOOTH;
    /** Limited broadcast -- reaches the local link whatever the subnet, never routed. */
    public static final String DEFAULT_COT_ADDRESS = "255.255.255.255";

    public static final int MIN_REFRESH_SECONDS = 5;
    public static final int MAX_REFRESH_SECONDS = 20;
    public static final int DEFAULT_REFRESH_SECONDS = 10;

    private final AtakPreferences prefs;

    /**
     * @param atakContext the ATAK context (MapView's), not the plugin context -- the
     *                    plugin context would write to the plugin APK's own private
     *                    prefs file instead of ATAK's.
     */
    public TrboTakPreferences(Context atakContext) {
        this.prefs = AtakPreferences.getInstance(atakContext);
    }

    public String getNic() {
        String nic = prefs.get(PREF_NIC, DEFAULT_NIC);
        // guard against a stale or hand-edited value
        if (!NIC_BLUETOOTH.equals(nic) && !NIC_USB.equals(nic) && !NIC_WIFI.equals(nic))
            return DEFAULT_NIC;
        return nic;
    }

    public void setNic(String nic) {
        prefs.set(PREF_NIC, nic);
    }

    public int getRefreshSeconds() {
        return clampRefreshSeconds(
                prefs.get(PREF_REFRESH_SECONDS, DEFAULT_REFRESH_SECONDS));
    }

    public void setRefreshSeconds(int seconds) {
        prefs.set(PREF_REFRESH_SECONDS, clampRefreshSeconds(seconds));
    }

    public String getCotAddress() {
        return prefs.get(PREF_COT_ADDRESS, DEFAULT_COT_ADDRESS);
    }

    public void setCotAddress(String address) {
        prefs.set(PREF_COT_ADDRESS, address);
    }

    public Map<String, String> getRadios() {
        final Map<String, String> out = new LinkedHashMap<>();
        final String raw = prefs.get(PREF_RADIOS, "");
        if (raw == null || raw.isEmpty())
            return out;

        try {
            final JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                final JSONObject o = arr.getJSONObject(i);
                final String id = o.optString("id", "");
                if (!id.isEmpty())
                    out.put(id, o.optString("name", id));
            }
        } catch (JSONException e) {
            Log.w(TAG, "unreadable radio list, starting empty", e);
        }
        return out;
    }

    public void setRadios(Map<String, String> radios) {
        final JSONArray arr = new JSONArray();
        try {
            for (Map.Entry<String, String> e : radios.entrySet()) {
                final JSONObject o = new JSONObject();
                o.put("id", e.getKey());
                o.put("name", e.getValue());
                arr.put(o);
            }
        } catch (JSONException e) {
            Log.w(TAG, "could not serialise radio list", e);
            return;
        }
        prefs.set(PREF_RADIOS, arr.toString());
    }

    public static int clampRefreshSeconds(int seconds) {
        return Math.max(Math.min(seconds, MAX_REFRESH_SECONDS), MIN_REFRESH_SECONDS);
    }
}
