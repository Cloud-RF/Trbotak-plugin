
package com.atakmap.android.trbotak.lrrp;

import java.util.HashMap;
import java.util.Map;

/**
 * MotoTRBO LRRP request building and response parsing.
 *
 * <pre>
 *   request  : 05 06 22 04 &lt;tx_id:4&gt;
 *   response : 07 XX 22 04 &lt;tx_id:4&gt; [66/69/51/55 &lt;lat:4&gt;&lt;lon:4&gt; ...]
 *                                     or [37 &lt;status:varint&gt;]
 *                                     or [38] (STATUS_OK)
 * </pre>
 */
public final class LrrpCodec {

    // ---- message types ----
    public static final int TYPE_IMMEDIATE_LOCATION_REQUEST = 0x05;
    public static final int TYPE_IMMEDIATE_LOCATION_RESPONSE = 0x07;
    public static final int TYPE_TRIGGERED_LOCATION_START_REQUEST = 0x09;
    public static final int TYPE_TRIGGERED_LOCATION_START_RESPONSE = 0x0B;
    /** Frames a subscribed radio pushes; parsed identically to 0x07. */
    public static final int TYPE_TRIGGERED_LOCATION_DATA = 0x0D;
    public static final int TYPE_TRIGGERED_LOCATION_STOP_REQUEST = 0x0F;
    public static final int TYPE_TRIGGERED_LOCATION_STOP_RESPONSE = 0x11;

    // ---- tokens ----
    public static final int TOKEN_ID = 0x22;
    public static final int TOKEN_LOCATION = 0x66;
    public static final int TOKEN_LOCATION_ALT = 0x69;
    public static final int TOKEN_LOCATION_3D = 0x51;
    public static final int TOKEN_LOCATION_3D_ALT = 0x55;
    public static final int TOKEN_TIME = 0x34;
    public static final int TOKEN_DIRECTION = 0x56;
    public static final int TOKEN_SPEED = 0x6C;
    public static final int TOKEN_SPEED_VRT = 0x70;
    public static final int TOKEN_STATUS_CODE = 0x37;
    public static final int TOKEN_STATUS_OK = 0x38;

    // ---- optional triggered-start parameters; see buildTriggeredStart ----
    public static final int REQ_TIME = 0x52;
    public static final int REQ_ALTITUDE = 0x54;
    public static final int REQ_SPEED_HORIZONTAL = 0x62;
    public static final int REQ_PERIODIC_TRIGGER = 0x34;
    public static final int REQ_TRIGGER_INTERVAL = 0x31;

    /** Both source and destination port: the radio replies to whatever we send from. */
    public static final int LRRP_PORT = 4001;

    public static final Map<Integer, String> STATUS_NAMES = new HashMap<>();

    static {
        STATUS_NAMES.put(0x000, "success");
        STATUS_NAMES.put(0x00A, "bad-command");
        STATUS_NAMES.put(0x010, "no-gps");          // radio up, no satellite lock yet
        STATUS_NAMES.put(0x200, "not-enough-gps");  // tracking, too few satellites
    }

    private LrrpCodec() {
    }

    public static String statusName(int code) {
        final String name = STATUS_NAMES.get(code);
        return name != null ? name : "unknown";
    }

    // ------------------------------------------------------------ addressing

    /** Radio ID to its address, e.g. 30491 -> 12.0.119.27. */
    public static String radioIdToDstIp(int radioId) {
        final long addr = (radioId & 0xFFFFFFFFL) + 0x0C000000L;
        return ((addr >> 24) & 0xFF) + "." + ((addr >> 16) & 0xFF) + "."
                + ((addr >> 8) & 0xFF) + "." + (addr & 0xFF);
    }

    /** Replies echo this back, which is how they are matched to a radio. */
    public static int txIdForRadioId(int radioId) {
        return 0xC0000000 | (radioId & 0x00FFFFFF);
    }

    /** Same, for a radio addressed by literal IP (e.g. the PAN gateway). */
    public static int txIdForIp(String ipv4) {
        final String[] parts = ipv4.split("\\.");
        if (parts.length != 4)
            throw new IllegalArgumentException("not an IPv4 address: " + ipv4);
        int packed = 0;
        for (String part : parts)
            packed = (packed << 8) | (Integer.parseInt(part.trim()) & 0xFF);
        return 0xC0000000 | (packed & 0x00FFFFFF);
    }

    // ------------------------------------------------------------- requests

    public static byte[] buildImmediateRequest(int txId, boolean requestAltitude) {
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(TOKEN_ID);
        body.write(0x04);
        body.write((txId >>> 24) & 0xFF);
        body.write((txId >>> 16) & 0xFF);
        body.write((txId >>> 8) & 0xFF);
        body.write(txId & 0xFF);

        if (requestAltitude)
            body.write(REQ_ALTITUDE);

        final byte[] bodyBytes = body.toByteArray();
        final byte[] out = new byte[2 + bodyBytes.length];
        out[0] = (byte) TYPE_IMMEDIATE_LOCATION_REQUEST;
        out[1] = (byte) bodyBytes.length;
        System.arraycopy(bodyBytes, 0, out, 2, bodyBytes.length);
        return out;
    }


    /**
     * Subscribes the radio to push a fix every {@code intervalSeconds} until stopped.
     */
    public static byte[] buildTriggeredStart(int txId, int intervalSeconds,
            boolean requestAltitude) {
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(TOKEN_ID);
        body.write(0x04);
        body.write((txId >>> 24) & 0xFF);
        body.write((txId >>> 16) & 0xFF);
        body.write((txId >>> 8) & 0xFF);
        body.write(txId & 0xFF);

        if (requestAltitude)
            body.write(REQ_ALTITUDE);

        body.write(REQ_PERIODIC_TRIGGER);
        body.write(REQ_TRIGGER_INTERVAL);
        for (byte b : toLrrpInt(intervalSeconds))
            body.write(b);

        final byte[] bodyBytes = body.toByteArray();
        final byte[] out = new byte[2 + bodyBytes.length];
        out[0] = (byte) TYPE_TRIGGERED_LOCATION_START_REQUEST;
        out[1] = (byte) bodyBytes.length;
        System.arraycopy(bodyBytes, 0, out, 2, bodyBytes.length);
        return out;
    }

    /** Cancels a subscription. Must be sent, or the radio transmits indefinitely. */
    public static byte[] buildTriggeredStop(int txId) {
        return new byte[] {
                (byte) TYPE_TRIGGERED_LOCATION_STOP_REQUEST,
                0x06,
                (byte) TOKEN_ID, 0x04,
                (byte) (txId >>> 24), (byte) (txId >>> 16),
                (byte) (txId >>> 8), (byte) txId
        };
    }

    /**
     * Fakes a status-only reply -- what a radio sends when it has no satellite lock.
     * See {@link #STATUS_NAMES} for the codes worth passing.
     */
    public static byte[] buildStatusResponse(int txId, int statusCode) {
        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(TOKEN_ID);
        body.write(0x04);
        body.write((txId >>> 24) & 0xFF);
        body.write((txId >>> 16) & 0xFF);
        body.write((txId >>> 8) & 0xFF);
        body.write(txId & 0xFF);

        body.write(TOKEN_STATUS_CODE);
        for (byte b : toLrrpInt(statusCode))
            body.write(b);

        final byte[] bodyBytes = body.toByteArray();
        final byte[] out = new byte[2 + bodyBytes.length];
        out[0] = (byte) TYPE_IMMEDIATE_LOCATION_RESPONSE;
        out[1] = (byte) bodyBytes.length;
        System.arraycopy(bodyBytes, 0, out, 2, bodyBytes.length);
        return out;
    }

    /**
     * Fakes a radio's reply, for the Simulate button. Lives here rather than in test
     * code so simulating exercises the real parser over the real socket.
     */
    public static byte[] buildLocationResponse(int txId, double lat, double lon,
            Double altitude) {
        if (altitude != null && altitude < 0)
            throw new IllegalArgumentException(
                    "LRRP floats have no sign bit; altitude must be >= 0");

        final int latRaw = (int) Math.round(lat * 2147483648.0 / 90.0);
        final int lonRaw = (int) Math.round(lon * 2147483648.0 / 180.0);

        final java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        body.write(TOKEN_ID);
        body.write(0x04);
        body.write((txId >>> 24) & 0xFF);
        body.write((txId >>> 16) & 0xFF);
        body.write((txId >>> 8) & 0xFF);
        body.write(txId & 0xFF);

        body.write(altitude != null ? TOKEN_LOCATION_ALT : TOKEN_LOCATION);

        body.write((latRaw >>> 24) & 0xFF);
        body.write((latRaw >>> 16) & 0xFF);
        body.write((latRaw >>> 8) & 0xFF);
        body.write(latRaw & 0xFF);
        body.write((lonRaw >>> 24) & 0xFF);
        body.write((lonRaw >>> 16) & 0xFF);
        body.write((lonRaw >>> 8) & 0xFF);
        body.write(lonRaw & 0xFF);

        if (altitude != null) {
            final int whole = (int) Math.floor(altitude);
            for (byte b : toLrrpInt(whole))
                body.write(b);

            body.write(Math.min(127,
                    (int) Math.round((altitude - whole) * 128.0)));
        }

        final byte[] bodyBytes = body.toByteArray();
        final byte[] out = new byte[2 + bodyBytes.length];
        out[0] = (byte) TYPE_IMMEDIATE_LOCATION_RESPONSE;
        out[1] = (byte) bodyBytes.length;
        System.arraycopy(bodyBytes, 0, out, 2, bodyBytes.length);
        return out;
    }

    // -------------------------------------------------------------- varints

    /** Base-128, most significant group first, continuation bit on all but the last. */
    public static byte[] toLrrpInt(int value) {
        if (value < 0)
            throw new IllegalArgumentException("LRRP int must be non-negative");
        if (value == 0)
            return new byte[] { 0x00 };

        final java.util.List<Integer> parts = new java.util.ArrayList<>();
        int v = value;
        while (v > 0) {
            parts.add(v & 0x7F);
            v >>>= 7;
        }
        // all but the least significant group, which was pushed first
        for (int i = 1; i < parts.size(); i++)
            parts.set(i, parts.get(i) | 0x80);

        final byte[] out = new byte[parts.size()];
        for (int i = 0; i < parts.size(); i++)
            out[i] = (byte) (int) parts.get(parts.size() - 1 - i);
        return out;
    }

    /** @return {value, bytesConsumed} */
    static int[] parseLrrpInt(byte[] buf, int pos) {
        int val = 0;
        int count = 0;
        while (pos + count < buf.length) {
            final int b = buf[pos + count] & 0xFF;
            count++;
            val |= b & 0x7F;
            // shift only when continuing, which accumulates most significant first
            if (b > 0x7F)
                val <<= 7;
            else
                return new int[] { val, count };
        }
        return new int[] { val, count };
    }

    /**
     * @return {value, bytes consumed}
     */
    static double[] parseLrrpFloat(byte[] buf, int pos) {
        final int[] whole = parseLrrpInt(buf, pos);
        int consumed = whole[1];
        double value = whole[0];

        final int[] frac = parseLrrpInt(buf, pos + consumed);
        consumed += frac[1];

        // nothing left to read: integer part alone, as in the Python
        if (frac[1] > 0)
            value += frac[0] / Math.pow(128.0, frac[1]);

        return new double[] { value, consumed };
    }

    /** Delegates so the skip and the decode can never disagree on the width. */
    static int skipLrrpFloat(byte[] buf, int pos) {
        return pos + (int) parseLrrpFloat(buf, pos)[1];
    }

    // -------------------------------------------------------------- parsing

    /**
     * Walks the token stream of a response.
     *
     * Ignores the type byte, so immediate responses (0x07) and pushed triggered data
     * (0x0D) parse identically -- which is why switching between the two modes needs
     * no decoder change.
     *
     * @return parsed frame, or null if it carries no id token and so cannot be
     *         attributed to a radio
     */
    public static LrrpResponse parseResponse(byte[] payload) {
        if (payload == null || payload.length < 4)
            return null;

        int pos = 2;                    // skip type + body length
        Integer txId = null;
        Double lat = null;
        Double lon = null;
        Double alt = null;          // only the *_ALT location variants carry one
        Integer status = null;
        int firstDataToken = -1;

        while (pos < payload.length) {
            final int tok = payload[pos] & 0xFF;
            pos++;

            if (tok == TOKEN_ID) {
                if (pos >= payload.length)
                    break;
                final int idLen = payload[pos] & 0xFF;
                pos++;
                if (idLen == 0 || pos + idLen > payload.length)
                    return null;
                int v = 0;
                for (int i = 0; i < idLen; i++)
                    v = (v << 8) | (payload[pos + i] & 0xFF);
                txId = v;
                pos += idLen;
                continue;
            }

            if (firstDataToken == -1)
                firstDataToken = tok;

            if (tok == TOKEN_LOCATION || tok == TOKEN_LOCATION_ALT
                    || tok == TOKEN_LOCATION_3D || tok == TOKEN_LOCATION_3D_ALT) {

                if (pos + 8 > payload.length)
                    break;

                // signed: a positive-only read would flip the southern and
                // western hemispheres
                final int latRaw = readSignedInt(payload, pos);
                final int lonRaw = readSignedInt(payload, pos + 4);
                lat = latRaw * (90.0 / 2147483648.0);    // full range over 2^31
                lon = lonRaw * (180.0 / 2147483648.0);
                pos += 8;

                // the four variants share lat/lon and differ only in trailing
                // floats; radius and accuracy are skipped
                if (tok == TOKEN_LOCATION_3D || tok == TOKEN_LOCATION_3D_ALT)
                    pos = skipLrrpFloat(payload, pos);   // radius
                if (tok == TOKEN_LOCATION_ALT || tok == TOKEN_LOCATION_3D_ALT) {
                    final double[] r = parseLrrpFloat(payload, pos);
                    alt = r[0];
                    pos += (int) r[1];
                }
                if (tok == TOKEN_LOCATION_3D_ALT)
                    pos = skipLrrpFloat(payload, pos);   // altitude accuracy
                continue;
            }

            if (tok == TOKEN_TIME) {
                pos += 5;               // packed date/time
                continue;
            }

            if (tok == TOKEN_DIRECTION) {
                pos += 1;               // heading
                continue;
            }

            if (tok == TOKEN_SPEED || tok == TOKEN_SPEED_VRT) {
                pos = skipLrrpFloat(payload, pos);
                continue;
            }

            if (tok == TOKEN_STATUS_OK) {
                status = 0;
                continue;
            }

            if (tok == TOKEN_STATUS_CODE) {
                final int[] r = parseLrrpInt(payload, pos);
                status = r[0];
                pos += r[1];
                continue;
            }

            // Unknown token: its width is unknown too, so continuing would
            // desynchronise the walk. Return what we have.
            break;
        }

        if (txId == null)
            return null;

        return new LrrpResponse(txId, lat, lon, alt, status, firstDataToken);
    }

    private static int readSignedInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** For logging frames we could not parse. */
    public static String toHex(byte[] data, int length) {
        final StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; i++)
            sb.append(String.format("%02x", data[i]));
        return sb.toString();
    }
}
