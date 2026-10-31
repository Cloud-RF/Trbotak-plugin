
package com.atakmap.android.trbotak.lrrp;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * One decoded LRRP response frame.
 * <p>
 * The gateway may answer with an ACK first and the actual position in a later
 * frame, so any of {@link #lat}/{@link #lon}/{@link #status} may be null.
 *
 * @param txId           Echoed transaction id; correlates the reply back to a radio.
 * @param alt            Height
 * @param status         LRRP status code, or null if the frame carried none.
 * @param firstDataToken First non-id token seen, useful when logging unexpected frames.
 */
public record LrrpResponse(int txId, Double lat, Double lon, Double alt,
        Integer status, int firstDataToken) {

    public boolean hasLocation() {
        return lat != null && lon != null;
    }

    public boolean hasAltitude() {
        return alt != null;
    }

    @NonNull
    @Override
    public String toString() {
        if (hasLocation())
            return String.format(Locale.ENGLISH,
                    "LOCATION tok=0x%02x lat=%.6f lon=%.6f alt=%s",
                    firstDataToken, lat, lon,
                    hasAltitude() ? String.format(Locale.ENGLISH, "%.2f", alt) : "none");
        if (status != null)
            return String.format(Locale.ENGLISH, "STATUS tok=0x%02x code=%d (%s)",
                    firstDataToken, status, LrrpCodec.statusName(status));
        return String.format("other tok=0x%02x", firstDataToken);
    }
}
