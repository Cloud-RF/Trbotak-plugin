
package com.atakmap.android.trbotak.cot;

import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

/**
 * Builds Cursor on Target events for a radio.
 *
 * Built as objects rather than XML strings so ATAK owns the escaping, time formats
 * and coordinate precision.
 */
public final class CotFactory {

    /** atoms / friendly / Ground / Unit -- no specialization, renders as a blue track. */
    public static final String TYPE_FRIENDLY_GROUND = "a-f-G-U";

    /** ATAK greys out and drops a marker past this. Must exceed the poll interval. */
    public static final int DEFAULT_STALE_SECONDS = 60;

    private CotFactory() {
    }

    public static String toXml(CotEvent event) {
        final StringBuilder sb = new StringBuilder();
        event.buildXml(sb);
        return sb.toString();
    }

    public static CotEvent create(String uid, String callsign,
            double lat, double lon, double altHae) {
        return create(uid, callsign, lat, lon, altHae,
                TYPE_FRIENDLY_GROUND, DEFAULT_STALE_SECONDS);
    }

    /**
     * @param uid      identity. Repeat it and ATAK moves that marker; change it and
     *                 you get a second one. Also the key deepFindUID() looks up.
     * @param callsign display name only, carried in &lt;contact&gt;. Renaming a radio
     *                 cannot duplicate its marker.
     * @param altHae   height according to WGS84. Pass CotPoint.UNKNOWN rather than 0, which would claim
     *                 sea level.
     */
    public static CotEvent create(String uid, String callsign,
            double lat, double lon, double altHae,
            String type, int staleSeconds) {

        CotEvent event = new CotEvent();

        event.setUID(uid);
        event.setType(type);
        event.setVersion(CotEvent.VERSION_2_0);
        event.setHow(CotEvent.HOW_MACHINE_GENERATED);   // device, not hand-entered

        final CoordinatedTime now = new CoordinatedTime();
        event.setTime(now);
        event.setStart(now);
        event.setStale(now.addSeconds(staleSeconds));

        // ce/le are circular/linear error; LRRP gives us no accuracy figure
        event.setPoint(new CotPoint(lat, lon, altHae,
                CotPoint.UNKNOWN, CotPoint.UNKNOWN));

        final CotDetail detail = new CotDetail("detail");
        final CotDetail contact = new CotDetail("contact");
        contact.setAttribute("callsign", callsign);
        detail.addChild(contact);
        event.setDetail(detail);

        return event;
    }
}
