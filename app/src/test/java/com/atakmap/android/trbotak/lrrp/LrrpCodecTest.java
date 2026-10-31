
package com.atakmap.android.trbotak.lrrp;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * Malformed-frame behaviour of {@link LrrpCodec#parseResponse}.
 *
 * The parser runs on the main thread -- UdpTransport posts received packets to the
 * main looper, and the try/catch in its receive loop does not cover the listener.
 * An exception escaping here takes ATAK down, not just the plugin, so "never throws"
 * is the property these tests exist to hold.
 *
 * Several cases assert behaviour that is arguably wrong but is what the parser
 * currently does; they are marked so a deliberate change breaks them loudly.
 */
public class LrrpCodecTest {

    private static final int TX_ID = 0xC000771B;    // radio 30491

    // ------------------------------------------------------------- helpers

    /** Wraps a token body in the response header the parser expects. */
    private static byte[] frame(byte... body) {
        final byte[] out = new byte[2 + body.length];
        out[0] = (byte) LrrpCodec.TYPE_IMMEDIATE_LOCATION_RESPONSE;
        out[1] = (byte) body.length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    /** The id token every attributable frame must carry. */
    private static byte[] idToken(int txId) {
        return new byte[] {
                (byte) LrrpCodec.TOKEN_ID, 0x04,
                (byte) (txId >>> 24), (byte) (txId >>> 16),
                (byte) (txId >>> 8), (byte) txId
        };
    }

    private static byte[] concat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts)
            out.write(p, 0, p.length);
        return out.toByteArray();
    }

    // ------------------------------------------------------- length guards

    @Test
    public void nullPayloadReturnsNull() {
        assertNull(LrrpCodec.parseResponse(null));
    }

    @Test
    public void shorterThanFourBytesReturnsNull() {
        for (int len = 0; len < 4; len++)
            assertNull("length " + len, LrrpCodec.parseResponse(new byte[len]));
    }

    // ---------------------------------------------------------- id parsing

    @Test
    public void noIdTokenReturnsNull() {
        // status-only frame: parseable tokens, but nothing to attribute it to
        assertNull(LrrpCodec.parseResponse(
                frame((byte) LrrpCodec.TOKEN_STATUS_OK, (byte) 0x00)));
    }

    @Test
    public void zeroLengthIdReturnsNull() {
        assertNull(LrrpCodec.parseResponse(
                frame((byte) LrrpCodec.TOKEN_ID, (byte) 0x00)));
    }

    @Test
    public void idLengthOverrunningBufferReturnsNull() {
        // claims four id bytes, supplies two
        assertNull(LrrpCodec.parseResponse(
                frame((byte) LrrpCodec.TOKEN_ID, (byte) 0x04, (byte) 0x11, (byte) 0x22)));
    }

    // ------------------------------------------------------- control cases

    @Test
    public void validLocationRoundTrips() {
        final LrrpResponse r = LrrpCodec.parseResponse(
                LrrpCodec.buildLocationResponse(TX_ID, 51.5074, -0.1278, null));

        assertNotNull(r);
        assertEquals(TX_ID, r.txId());
        assertTrue(r.hasLocation());
        assertEquals(51.5074, r.lat(), 1e-6);
        assertEquals(-0.1278, r.lon(), 1e-6);
    }

    @Test
    public void southernAndWesternHemispheresSurvive() {
        // a positive-only read would flip these; cheap regression guard
        final LrrpResponse r = LrrpCodec.parseResponse(
                LrrpCodec.buildLocationResponse(TX_ID, -33.8688, -151.2093, null));

        assertNotNull(r);
        assertEquals(-33.8688, r.lat(), 1e-6);
        assertEquals(-151.2093, r.lon(), 1e-6);
    }

    @Test
    public void statusOkParsesAsZero() {
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_STATUS_OK })));

        assertNotNull(r);
        assertFalse(r.hasLocation());
        assertEquals(Integer.valueOf(0), r.status());
    }

    @Test
    public void singleByteStatusCodeParses() {
        // 0x010 = no-gps, fits in one varint byte
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_STATUS_CODE, 0x10 })));

        assertNotNull(r);
        assertEquals(Integer.valueOf(0x010), r.status());
        assertEquals("no-gps", LrrpCodec.statusName(r.status()));
    }

    @Test
    public void multiByteStatusCodeParses() {
        // 0x200 = not-enough-gps: 512 needs two varint bytes, high group first
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_STATUS_CODE,
                                (byte) 0x84, 0x00 })));

        assertNotNull(r);
        assertEquals(Integer.valueOf(0x200), r.status());
        assertEquals("not-enough-gps", LrrpCodec.statusName(r.status()));
    }

    @Test
    public void unknownStatusCodeIsNamedRatherThanThrowing() {
        assertEquals("unknown", LrrpCodec.statusName(0x7FF));
    }

    // -------------------------------------------------------------- altitude

    @Test
    public void bareLocationCarriesNoAltitude() {
        final LrrpResponse r = LrrpCodec.parseResponse(
                LrrpCodec.buildLocationResponse(TX_ID, 51.5074, -0.1278, null));

        assertNotNull(r);
        assertTrue(r.hasLocation());
        assertFalse("0x66 has no altitude field at all", r.hasAltitude());
        assertNull(r.alt());
    }

    @Test
    public void locationWithAltitudeIsDecoded() {
        // 0x69 = lat/lon + an LRRP float: varint 120, then varint 64 over 128^1
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_LOCATION_ALT,
                                0x40, 0x00, 0x00, 0x00,     // lat 45
                                0x40, 0x00, 0x00, 0x00,     // lon 90
                                0x78, 0x40 })));            // 120 + 64/128

        assertNotNull(r);
        assertTrue(r.hasLocation());
        assertEquals(45.0, r.lat(), 1e-6);
        assertTrue(r.hasAltitude());
        assertEquals(120.5, r.alt(), 1e-9);
    }

    @Test
    public void multiByteFractionUsesTheWiderDivisor() {
        // fraction 0x81 0x00 is a two-byte varint = 128, so the divisor is 128^2
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_LOCATION_ALT,
                                0x40, 0x00, 0x00, 0x00,
                                0x40, 0x00, 0x00, 0x00,
                                0x78, (byte) 0x81, 0x00 })));

        assertNotNull(r);
        assertTrue(r.hasAltitude());
        assertEquals(120.0 + 128.0 / 16384.0, r.alt(), 1e-9);
    }

    @Test
    public void altitudeTruncatedToItsIntegerPartStillDecodes() {
        // fractional byte missing: parseLrrpFloat must not read past the end
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_LOCATION_ALT,
                                0x40, 0x00, 0x00, 0x00,
                                0x40, 0x00, 0x00, 0x00,
                                0x78 })));

        assertNotNull(r);
        assertTrue(r.hasAltitude());
        assertEquals(120.0, r.alt(), 1e-9);
    }

    @Test
    public void builtAltitudeRoundTripsThroughTheParser() {
        // proves the encoder and decoder agree; says nothing about whether either
        // matches a real radio, which only a radio can settle
        final LrrpResponse r = LrrpCodec.parseResponse(
                LrrpCodec.buildLocationResponse(TX_ID, 51.5074, -0.1278, 123.5));

        assertNotNull(r);
        assertEquals(LrrpCodec.TOKEN_LOCATION_ALT, r.firstDataToken());
        assertTrue(r.hasAltitude());
        assertEquals(123.5, r.alt(), 1e-9);
    }

    @Test
    public void nullAltitudeBuildsTheBareLocationFrame() {
        // null must emit 0x66 with nothing trailing, not 0x69 carrying a zero --
        // a radio that reported no height must not be made to claim sea level
        final byte[] bare = LrrpCodec.buildLocationResponse(TX_ID, 51.5, -0.1, null);

        assertEquals((byte) LrrpCodec.TOKEN_LOCATION, bare[8]);
        assertEquals("id token + location token + lat/lon, and no more",
                17, bare.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeAltitudeIsRejected() {
        LrrpCodec.buildLocationResponse(TX_ID, 51.5, -0.1, -5.0);
    }
    
    @Test
    public void triggeredStartRequestsAltitudeBeforeTheTriggerTokens() {
        assertArrayEquals(new byte[] {
                (byte) LrrpCodec.TYPE_TRIGGERED_LOCATION_START_REQUEST,
                0x0A,                                   // body length
                (byte) LrrpCodec.TOKEN_ID, 0x04,
                (byte) 0xC0, 0x00, 0x77, 0x1B,          // tx id
                (byte) LrrpCodec.REQ_ALTITUDE,
                (byte) LrrpCodec.REQ_PERIODIC_TRIGGER,
                (byte) LrrpCodec.REQ_TRIGGER_INTERVAL,
                0x1E                                    // 30s as a varint
        }, LrrpCodec.buildTriggeredStart(TX_ID, 30, true));
    }

    @Test
    public void triggeredStartWithoutAltitudeMatchesThePythonDefault() {
        // what LrrpPoller.REQUEST_ALTITUDE = false reverts to
        assertArrayEquals(new byte[] {
                (byte) LrrpCodec.TYPE_TRIGGERED_LOCATION_START_REQUEST,
                0x09,
                (byte) LrrpCodec.TOKEN_ID, 0x04,
                (byte) 0xC0, 0x00, 0x77, 0x1B,
                (byte) LrrpCodec.REQ_PERIODIC_TRIGGER,
                (byte) LrrpCodec.REQ_TRIGGER_INTERVAL,
                0x1E
        }, LrrpCodec.buildTriggeredStart(TX_ID, 30, false));
    }

    @Test
    public void immediateRequestCanAskForAltitude() {
        // REQ_ALTITUDE trails the id here; there are no trigger tokens to precede
        assertArrayEquals(new byte[] {
                (byte) LrrpCodec.TYPE_IMMEDIATE_LOCATION_REQUEST,
                0x07,
                (byte) LrrpCodec.TOKEN_ID, 0x04,
                (byte) 0xC0, 0x00, 0x77, 0x1B,
                (byte) LrrpCodec.REQ_ALTITUDE
        }, LrrpCodec.buildImmediateRequest(TX_ID, true));
    }

    @Test
    public void immediateRequestWithoutAltitudeIsTheOriginalSixByteBody() {
        assertArrayEquals(new byte[] {
                (byte) LrrpCodec.TYPE_IMMEDIATE_LOCATION_REQUEST,
                0x06,
                (byte) LrrpCodec.TOKEN_ID, 0x04,
                (byte) 0xC0, 0x00, 0x77, 0x1B
        }, LrrpCodec.buildImmediateRequest(TX_ID, false));
    }

    @Test
    public void triggeredStopStaysFreeOfExtras() {
        // the stop request must not gain the altitude token: a radio that rejects
        // extras would then also reject the cancellation and keep transmitting
        final byte[] stop = LrrpCodec.buildTriggeredStop(TX_ID);

        for (byte b : stop)
            assertTrue("stop must carry no REQ_* tokens",
                    b != (byte) LrrpCodec.REQ_ALTITUDE);
    }

    // -------------------------------------------------------- malformed bodies

    @Test
    public void lyingBodyLengthIsIgnored() {
        // the parser walks to payload.length and never reads the length byte,
        // so a wrong one must not change the outcome
        final byte[] good = LrrpCodec.buildLocationResponse(TX_ID, 51.5, -0.1, null);
        final byte[] lying = good.clone();
        lying[1] = 0x00;

        final LrrpResponse r = LrrpCodec.parseResponse(lying);

        assertNotNull(r);
        assertTrue(r.hasLocation());
        assertEquals(51.5, r.lat(), 1e-6);
    }

    @Test
    public void unknownTokenReturnsWhatWasParsedSoFar() {
        // width of an unknown token is unknown, so the walk stops rather than
        // desynchronising -- the id already read is still returned
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID), new byte[] { (byte) 0xFE })));

        assertNotNull(r);
        assertEquals(TX_ID, r.txId());
        assertFalse(r.hasLocation());
        assertNull(r.status());
        assertEquals(0xFE, r.firstDataToken());
    }

    @Test
    public void truncatedTimeTokenDoesNotThrow() {
        // TOKEN_TIME advances by a fixed 5 with no bounds check of its own;
        // the loop condition is what stops the walk
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_TIME })));

        assertNotNull(r);
        assertEquals(TX_ID, r.txId());
    }

    @Test
    public void truncatedSpeedTokenDoesNotThrow() {
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_SPEED })));

        assertNotNull(r);
        assertEquals(TX_ID, r.txId());
    }

    // ------------------------------------------------- documented soft spots

    /**
     * A position frame cut short loses lat/lon, so hasLocation() is false and
     * LrrpPoller dispatches it to onStatus with a null code -- which the panel
     * renders as "ack". A corrupt fix is reported as a successful acknowledgement.
     */
    @Test
    public void truncatedLocationDegradesToAStatuslessFrame() {
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_LOCATION, 0x00, 0x00 })));

        assertNotNull(r);
        assertEquals(TX_ID, r.txId());
        assertFalse("truncated position must not be treated as a fix", r.hasLocation());
        assertNull("no status code was present", r.status());
        assertEquals(LrrpCodec.TOKEN_LOCATION, r.firstDataToken());
    }

    /**
     * A status token with its varint cut off yields 0, which statusName() maps to
     * "success". Truncation is reported as the radio being healthy.
     */
    @Test
    public void truncatedStatusVarintReadsAsSuccess() {
        final LrrpResponse r = LrrpCodec.parseResponse(
                frame(concat(idToken(TX_ID),
                        new byte[] { (byte) LrrpCodec.TOKEN_STATUS_CODE })));

        assertNotNull(r);
        assertEquals(Integer.valueOf(0), r.status());
        assertEquals("success", LrrpCodec.statusName(r.status()));
    }

    // --------------------------------------------------------------- fuzzing

    @Test
    public void randomBytesNeverThrow() {
        final Random rnd = new Random(42);           // seeded: failures reproduce
        final byte[] buf = new byte[64];

        for (int i = 0; i < 200_000; i++) {
            final int len = rnd.nextInt(buf.length + 1);
            final byte[] payload = new byte[len];
            rnd.nextBytes(payload);
            LrrpCodec.parseResponse(payload);        // must not throw
        }
    }

    /**
     * Pure noise rarely survives the id token, so most of it never reaches the
     * token walk. Prefixing a valid header drives the fuzz into the branches that
     * actually decode.
     */
    @Test
    public void structuredFuzzNeverThrows() {
        final Random rnd = new Random(1337);
        final byte[] header = concat(
                new byte[] { (byte) LrrpCodec.TYPE_IMMEDIATE_LOCATION_RESPONSE, 0x00 },
                idToken(TX_ID));

        for (int i = 0; i < 200_000; i++) {
            final byte[] tail = new byte[rnd.nextInt(32)];
            rnd.nextBytes(tail);
            LrrpCodec.parseResponse(concat(header, tail));
        }
    }

    /**
     * Every valid frame truncated at every possible point -- the shapes a dropped
     * or clipped datagram actually produces, rather than random noise.
     */
    @Test
    public void everyPrefixOfAValidFrameNeverThrows() {
        final byte[] full = LrrpCodec.buildLocationResponse(TX_ID, 51.5074, -0.1278, null);

        for (int len = 0; len <= full.length; len++) {
            final byte[] prefix = new byte[len];
            System.arraycopy(full, 0, prefix, 0, len);
            LrrpCodec.parseResponse(prefix);
        }
    }
}