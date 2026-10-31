package com.atakmap.android.trbotak.net;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Subnet arithmetic behind {@link RadioLink#directedBroadcast(byte[], int)}.
 *
 * This is what makes CoT work over wifi: an access point that drops the limited
 * broadcast 255.255.255.255 will still flood a subnet-directed one. Getting the
 * mask wrong sends every report to one wrong host instead of every right one, and
 * the difference is invisible without a packet capture -- there is no error, the
 * datagram simply arrives nowhere useful.
 */
public class RadioLinkTest {

    /** The common case: a /24 wifi network. */
    @Test
    public void slash24() {
        assertEquals("192.168.1.255", broadcast(192, 168, 1, 37, 24));
    }

    @Test
    public void slash16() {
        assertEquals("172.16.255.255", broadcast(172, 16, 4, 9, 16));
    }

    /** Tethered links are often narrower than a /24. */
    @Test
    public void slash30() {
        assertEquals("12.0.0.3", broadcast(12, 0, 0, 1, 30));
    }

    /** Every bit a host bit; the shift here is the one that overflows if unguarded. */
    @Test
    public void slash0() {
        assertEquals("255.255.255.255", broadcast(10, 0, 0, 1, 0));
    }

    /** Already the broadcast address: idempotent, not incremented. */
    @Test
    public void addressIsAlreadyBroadcast() {
        assertEquals("192.168.1.255", broadcast(192, 168, 1, 255, 24));
    }

    /** High bytes must not sign-extend: 192 as a byte is negative in Java. */
    @Test
    public void highOctetsAreUnsigned() {
        assertEquals("223.255.255.255", broadcast(223, 1, 2, 3, 8));
    }

    /**
     * A /31 is a point-to-point link and a /32 a single host: neither has a
     * broadcast address, so the caller must fall back to the limited one rather
     * than send to the peer and call it a broadcast.
     */
    @Test
    public void noBroadcastAddressToForm() {
        assertNull(broadcast(10, 0, 0, 1, 31));
        assertNull(broadcast(10, 0, 0, 1, 32));
    }

    /** Nonsense prefixes are rejected, not clamped into a plausible answer. */
    @Test
    public void prefixOutOfRange() {
        assertNull(broadcast(10, 0, 0, 1, -1));
        assertNull(broadcast(10, 0, 0, 1, 33));
    }

    /** IPv6, or a truncated address: no answer rather than a wrong one. */
    @Test
    public void wrongAddressLength() {
        assertNull(RadioLink.directedBroadcast(null, 24));
        assertNull(RadioLink.directedBroadcast(new byte[0], 24));
        assertNull(RadioLink.directedBroadcast(new byte[16], 64));
    }

    // ------------------------------------------------------- subnet membership

    /** A /24 wifi link, as the settings preview would see it. */
    private static final RadioLink.Subnet WIFI =
            new RadioLink.Subnet("192.168.68.113", 24, "192.168.68.255");

    @Test
    public void subnetContainsItsOwnHosts() {
        assertTrue(WIFI.contains("192.168.68.1"));
        assertTrue(WIFI.contains("192.168.68.113"));
        assertTrue(WIFI.contains("192.168.68.255"));
    }

    /** One octet out is a different network, however similar it looks. */
    @Test
    public void subnetExcludesEverythingElse() {
        assertFalse(WIFI.contains("192.168.1.50"));
        assertFalse(WIFI.contains("10.0.2.2"));
        assertFalse(WIFI.contains("192.168.69.1"));
    }

    /** A hostname cannot be placed without DNS; callers screen it out beforehand. */
    @Test
    public void subnetCannotPlaceHostnames() {
        assertFalse(WIFI.contains("takserver.local"));
        assertFalse(WIFI.contains(null));
    }

    /** Names the network for the operator, so the warning says which one it means. */
    @Test
    public void cidrNamesTheNetwork() {
        assertEquals("192.168.68.0/24", WIFI.cidr());
        assertEquals("172.16.0.0/16",
                new RadioLink.Subnet("172.16.4.9", 16, "172.16.255.255").cidr());
        assertEquals("12.0.0.0/30",
                new RadioLink.Subnet("12.0.0.1", 30, "12.0.0.3").cidr());
    }

    // ------------------------------------------------------ address predicates

    @Test
    public void recognisesDottedQuads() {
        assertTrue(RadioLink.isIpv4("192.168.1.1"));
        assertTrue(RadioLink.isIpv4("0.0.0.0"));
        assertTrue(RadioLink.isIpv4("255.255.255.255"));
    }

    /**
     * Strict on purpose: InetAddress would accept "12" and shorthand forms, and
     * would hit DNS for a name -- which must never happen on the UI thread.
     */
    @Test
    public void rejectsAnythingElse() {
        assertFalse(RadioLink.isIpv4("takserver.local"));
        assertFalse(RadioLink.isIpv4("12"));
        assertFalse(RadioLink.isIpv4("1.2.3"));
        assertFalse(RadioLink.isIpv4("1.2.3.4.5"));
        assertFalse(RadioLink.isIpv4("1.2.3.256"));
        assertFalse(RadioLink.isIpv4("1.2.3."));
        assertFalse(RadioLink.isIpv4("1.2.3.-1"));
        assertFalse(RadioLink.isIpv4("fe80::1"));
        assertFalse(RadioLink.isIpv4(null));
    }

    /** 224.0.0.0/4 -- the boundaries are what a hand-typed group gets wrong. */
    @Test
    public void recognisesMulticastRange() {
        assertTrue(RadioLink.isMulticast("239.2.3.1"));     // ATAK mesh SA
        assertTrue(RadioLink.isMulticast("224.0.0.0"));
        assertTrue(RadioLink.isMulticast("239.255.255.255"));

        assertFalse(RadioLink.isMulticast("223.255.255.255"));
        assertFalse(RadioLink.isMulticast("240.0.0.0"));
        assertFalse(RadioLink.isMulticast("192.168.1.1"));
        assertFalse(RadioLink.isMulticast("takserver.local"));
    }

    private static String broadcast(int a, int b, int c, int d, int prefixLength) {
        final byte[] addr = { (byte) a, (byte) b, (byte) c, (byte) d };
        return RadioLink.directedBroadcast(addr, prefixLength);
    }
}
