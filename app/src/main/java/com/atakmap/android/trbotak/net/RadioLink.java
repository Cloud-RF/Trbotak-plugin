package com.atakmap.android.trbotak.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import com.atakmap.android.trbotak.TrboTakPreferences;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;

/**
 * Picks the link that carries radio traffic, and describes it.
 *
 * Android sends over the <i>default</i> network unless a socket is pinned, so a
 * datagram meant for a radio on Bluetooth PAN would leave via wifi and vanish --
 * and, now that wifi is a supported link, a datagram meant for wifi would leave via
 * cellular. Both the poller and the CoT sender resolve their link here rather than
 * restating the rule, so the two sockets can never disagree about where the radio is.
 *
 * Which link counts is the operator's NIC setting:
 * <ul>
 *   <li>{@link TrboTakPreferences#NIC_WIFI} -- the radio is paired to the same wifi
 *       network as the tablet. Radios must then be enrolled by their wifi IP: the
 *       12.0.0.0/8 address synthesised from a radio id only exists on a tethered
 *       link.</li>
 *   <li>{@link TrboTakPreferences#NIC_BLUETOOTH} / {@link TrboTakPreferences#NIC_USB}
 *       -- the radio tethers the tablet, so its link is the one that is neither wifi,
 *       cellular nor VPN.</li>
 * </ul>
 */
public final class RadioLink {

    public static final String TAG = "RadioLink";

    /**
     * Interface-name fragments per NIC, used to disambiguate when a tablet has more
     * than one tethered link up. Only a preference: an unrecognised name still
     * qualifies, because these are conventions rather than guarantees.
     */
    private static final String[] BLUETOOTH_HINTS = { "bt-", "bnep", "pan" };
    private static final String[] USB_HINTS = { "rndis", "usb", "eth" };

    private RadioLink() {
    }

    /** Resolves against the NIC the operator selected. */
    public static Network find(Context context) {
        return find(context, new TrboTakPreferences(context).getNic());
    }

    /**
     * Silent by design: called on a timer, so logging here would flood logcat.
     * Callers log the transitions they care about.
     *
     * @return null when nothing matched, leaving sockets on the default network
     */
    public static Network find(Context context, String nic) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)
            return null;

        final ConnectivityManager cm = connectivity(context);
        if (cm == null)
            return null;

        return TrboTakPreferences.NIC_WIFI.equals(nic)
                ? findWifi(cm)
                : findTethered(cm, nic);
    }

    /**
     * The wifi link the radio shares with us.
     *
     * A wifi network with no IPv4 address is one that is still associating, or is
     * IPv6-only; binding to it would fail every send until DHCP finished, so it is
     * used only when there is nothing better.
     */
    private static Network findWifi(ConnectivityManager cm) {
        Network fallback = null;

        for (Network n : cm.getAllNetworks()) {
            final NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null)
                continue;
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))
                continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                continue;

            if (ipv4AddressOf(cm, n) != null)
                return n;
            if (fallback == null)
                fallback = n;
        }
        return fallback;
    }

    /**
     * First network that is not wifi, cellular or VPN -- on a tablet tethered by a
     * radio that is the Bluetooth PAN link, which appears as "bt-pan".
     */
    private static Network findTethered(ConnectivityManager cm, String nic) {
        final String[] hints = TrboTakPreferences.NIC_USB.equals(nic)
                ? USB_HINTS
                : BLUETOOTH_HINTS;

        Network fallback = null;

        for (Network n : cm.getAllNetworks()) {
            final NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null)
                continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
                continue;

            if (matches(interfaceName(cm, n), hints))
                return n;
            if (fallback == null)
                fallback = n;
        }
        return fallback;
    }

    private static boolean matches(String interfaceName, String[] hints) {
        if (interfaceName == null)
            return false;
        final String name = interfaceName.toLowerCase();
        for (String hint : hints) {
            if (name.contains(hint))
                return true;
        }
        return false;
    }

    // ------------------------------------------------------------- describing

    /** Interface name, e.g. "bt-pan" or "wlan0"; null when it cannot be read. */
    public static String interfaceName(Context context, Network network) {
        return interfaceName(connectivity(context), network);
    }

    private static String interfaceName(ConnectivityManager cm, Network network) {
        final LinkProperties lp = linkProperties(cm, network);
        return lp != null ? lp.getInterfaceName() : null;
    }

    /** Interface name for logging; never null. */
    public static String describe(Context context, Network network) {
        if (network == null)
            return "default network";
        final String name = interfaceName(context, network);
        return name != null ? name : "?";
    }

    /**
     * Subnet-directed broadcast for the link, e.g. "192.168.1.255".
     *
     * Wifi is where this matters: an access point may drop the limited broadcast
     * 255.255.255.255 while forwarding a directed one, so CoT sent to the limited
     * address reaches nobody and there is nothing to see at either end. On a
     * tethered link both work, and the limited address is left alone.
     *
     * @return null when the link has no usable IPv4 subnet
     */
    public static String directedBroadcast(Context context, Network network) {

        final Subnet subnet = subnetOf(context, network);
        return subnet != null ? subnet.broadcast : null;
    }

    /**
     * The link's IPv4 subnet.
     *
     * Exists so the settings dialog can say where a typed address will actually go:
     * a CoT address is only useful if it is reachable on the link CoT leaves by, and
     * an address on the wrong subnet is the one mistake that produces no error
     * anywhere -- the datagram is sent successfully and dropped by something else.
     *
     * @return null when the link is down or has no usable IPv4 subnet
     */
    public static Subnet subnetOf(Context context, Network network) {
        if (network == null)
            return null;

        final ConnectivityManager cm = connectivity(context);
        final LinkProperties lp = linkProperties(cm, network);
        if (lp == null)
            return null;

        final List<LinkAddress> addresses = lp.getLinkAddresses();
        if (addresses == null)
            return null;

        for (LinkAddress la : addresses) {
            final InetAddress addr = la.getAddress();
            if (!(addr instanceof Inet4Address))
                continue;
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress())
                continue;

            final String broadcast =
                    directedBroadcast(addr.getAddress(), la.getPrefixLength());
            if (broadcast != null)
                return new Subnet(addr.getHostAddress(), la.getPrefixLength(),
                        broadcast);
        }
        return null;
    }

    /** One link's IPv4 subnet, and the questions the settings dialog asks of it. */
    public static final class Subnet {

        /** This device's address on the link, e.g. "192.168.1.37". */
        public final String address;

        /** Mask width, e.g. 24. */
        public final int prefixLength;

        /** Subnet-directed broadcast, e.g. "192.168.1.255". */
        public final String broadcast;

        public Subnet(String address, int prefixLength, String broadcast) {
            this.address = address;
            this.prefixLength = prefixLength;
            this.broadcast = broadcast;
        }

        /**
         * Whether {@code host} is on this subnet.
         *
         * Two addresses share a subnet exactly when they share a directed broadcast,
         * so this reuses the arithmetic rather than repeating it.
         *
         * @param host dotted quad; callers must screen out hostnames with
         *             {@link RadioLink#isIpv4} first, since a name cannot be placed
         *             without a DNS lookup and would be reported as off-subnet
         */
        public boolean contains(String host) {
            final byte[] addr = parseIpv4(host);
            if (addr == null)
                return false;
            return broadcast.equals(directedBroadcast(addr, prefixLength));
        }

        /** The subnet itself, e.g. "192.168.1.0/24", for naming it to the operator. */
        public String cidr() {
            final byte[] addr = parseIpv4(address);
            if (addr == null)
                return "?";

            int packed = 0;
            for (byte b : addr)
                packed = (packed << 8) | (b & 0xFF);

            // host bits cleared; the /0 shift is taken as a long for the same reason
            // directedBroadcast does it
            final int hostMask = (int) ((1L << (32 - prefixLength)) - 1L);
            final int network = packed & ~hostMask;

            return ((network >>> 24) & 0xFF) + "."
                    + ((network >>> 16) & 0xFF) + "."
                    + ((network >>> 8) & 0xFF) + "."
                    + (network & 0xFF) + "/" + prefixLength;
        }
    }

    /** Whether {@code host} is a literal dotted quad rather than a hostname. */
    public static boolean isIpv4(String host) {
        return parseIpv4(host) != null;
    }

    /**
     * Whether {@code host} is in 224.0.0.0/4 -- a multicast group rather than a
     * single machine. ATAK's mesh SA group 239.2.3.1 is the one that matters here.
     */
    public static boolean isMulticast(String host) {
        final byte[] addr = parseIpv4(host);
        if (addr == null)
            return false;
        final int first = addr[0] & 0xFF;
        return first >= 224 && first <= 239;
    }

    /**
     * Four bytes from a dotted quad, or null if it is not one.
     *
     * Deliberately strict -- InetAddress.getByName would accept "12" and shorthand
     * forms, and would hit DNS for anything else, which must never happen on the UI
     * thread.
     */
    static byte[] parseIpv4(String host) {
        if (host == null)
            return null;

        final String[] parts = host.split("\\.", -1);
        if (parts.length != 4)
            return null;

        final byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3)
                return null;
            for (int c = 0; c < parts[i].length(); c++) {
                if (parts[i].charAt(c) < '0' || parts[i].charAt(c) > '9')
                    return null;
            }
            final int value = Integer.parseInt(parts[i]);
            if (value > 255)
                return null;
            out[i] = (byte) value;
        }
        return out;
    }

    /**
     * Host bits set, which is the broadcast address of the subnet {@code address}
     * sits in.
     *
     * Split out from the Android plumbing above so it can be tested: getting this
     * wrong sends CoT to one wrong host instead of every right one, and the
     * difference is invisible without a packet capture.
     *
     * @param address      four bytes, network order
     * @param prefixLength 0..30; /31 and /32 have no broadcast address
     * @return dotted quad, or null when there is no broadcast address to form
     */
    static String directedBroadcast(byte[] address, int prefixLength) {
        if (address == null || address.length != 4)
            return null;
        if (prefixLength < 0 || prefixLength > 30)
            return null;

        int packed = 0;
        for (byte b : address)
            packed = (packed << 8) | (b & 0xFF);

        // 32 - prefixLength host bits, all set. Shifted as a long because a /0 asks
        // for a shift of 32, and Java would take that modulo 32 -- shifting by 0 and
        // setting no host bits at all, so the "broadcast" came back as the host's own
        // address and every report went to a single machine.
        final int hostMask = (int) ((1L << (32 - prefixLength)) - 1L);
        final int broadcast = packed | hostMask;

        return ((broadcast >>> 24) & 0xFF) + "."
                + ((broadcast >>> 16) & 0xFF) + "."
                + ((broadcast >>> 8) & 0xFF) + "."
                + (broadcast & 0xFF);
    }

    // ---------------------------------------------------------------- helpers

    private static InetAddress ipv4AddressOf(ConnectivityManager cm, Network network) {
        final LinkProperties lp = linkProperties(cm, network);
        if (lp == null || lp.getLinkAddresses() == null)
            return null;

        for (LinkAddress la : lp.getLinkAddresses()) {
            final InetAddress addr = la.getAddress();
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress())
                return addr;
        }
        return null;
    }

    private static LinkProperties linkProperties(ConnectivityManager cm, Network network) {
        if (cm == null || network == null)
            return null;
        try {
            return cm.getLinkProperties(network);
        } catch (Exception e) {
            return null;
        }
    }

    private static ConnectivityManager connectivity(Context context) {
        if (context == null)
            return null;
        return (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }
}
