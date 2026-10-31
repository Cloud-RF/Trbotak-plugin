
package com.atakmap.android.trbotak.cot;

import android.content.Context;
import android.net.Network;

import com.atakmap.android.trbotak.net.RadioLink;
import com.atakmap.android.trbotak.net.UdpTransport;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.nio.charset.StandardCharsets;

/**
 * Pushes CoT XML to a UDP destination, so radios appear on other TAK clients rather
 * than only this tablet's map. Equivalent of lib/cot.py's CotSender.
 *
 * Still its own socket: the poller's is bound to the LRRP port to catch replies there,
 * which is no use for transmitting to an arbitrary destination.
 */
public class CotSender implements UdpTransport.Listener {

    public static final String TAG = "CotSender";

    /** ATAK's CoT input port, used when the address carries none. */
    public static final int DEFAULT_COT_PORT = 4242;

    /**
     * Python's sendto() spells the broadcast address this way, and lib/cot.py's
     * default target uses it. InetAddress has no such notion, so accept the
     * spelling and translate it rather than leaving operators a dead setting.
     */
    public static final String BROADCAST_SENTINEL = "<broadcast>";

    private static final String BROADCAST_ADDRESS = "255.255.255.255";

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    private final UdpTransport transport = new UdpTransport(this);

    private Context context;

    /** Link the socket is pinned to; null the default will be used. */
    private Network boundNetwork;

    /**
     * Broadcast address of {@link #boundNetwork}'s subnet, or null to keep using the
     * limited one. Cached with the network it was read from, so it is refreshed
     * whenever the link changes underneath us.
     */
    private String directedBroadcast;

    /**
     * Ephemeral local port, pinned to the radio's link.
     *
     * Pinned rather than left on the default network because Android will hand a
     * socket to cellular given the chance, and neither a PAN nor a wifi link the
     * operator picked is necessarily the default route.
     */
    public void start(Context context) {
        this.context = context;
        bind(RadioLink.find(context));
    }

    public void stop() {
        transport.stop();
        boundNetwork = null;
        directedBroadcast = null;
    }

    /**
     * Opens the socket on {@code network}; null leaves it on the default one.
     */
    private void bind(Network network) {
        boundNetwork = network;
        directedBroadcast = RadioLink.directedBroadcast(context, network);
        transport.setNetwork(network, RadioLink.interfaceName(context, network));
        transport.start(0);
    }

    /** @param destination host or host:port; blank means local map only */
    public void send(CotEvent event, String destination) {
        final Destination dest = parse(destination);
        if (dest == null)
            return;

        if (!transport.isRunning()) {
            Log.w(TAG, "not started; dropping CoT for " + event.getUID());
            return;
        }

        if (!rebindIfNetworkChanged())
            return;

        final byte[] xml = CotFactory.toXml(event).getBytes(StandardCharsets.UTF_8);
        transport.send(resolveHost(dest.host), dest.port, xml);
    }

    private String resolveHost(String host) {
        if (!BROADCAST_ADDRESS.equals(host))
            return host;

        // Re-read when we have a link but no address for it: on wifi the Network
        // arrives before DHCP finishes, so the address read at bind time was null
        // and would otherwise stay null for the life of the socket.
        if (directedBroadcast == null && boundNetwork != null)
            directedBroadcast = RadioLink.directedBroadcast(context, boundNetwork);

        return directedBroadcast != null ? directedBroadcast : host;
    }

    // -------------------------------------------------------- network selection

    /**
     * Follows the radio link across the new Network Android mints every time
     * Bluetooth PAN reconnects, the same way the poller does.
     *
     * Also how the NIC setting takes effect: {@link RadioLink#find} re-reads it on
     * every call, so switching to wifi moves this socket onto the wifi link at the
     * next report rather than at the next plugin reload.
     *
     * @return false only when the socket could not be moved onto the link, so the
     *         caller skips rather than sending over whatever the default happens
     *         to be.
     */
    private boolean rebindIfNetworkChanged() {
        if (context == null)
            return true;        // no way to look; leave the socket as start() left it

        final Network current = RadioLink.find(context);

        if (current == null) {
            // no radio link right now; the default network is the best guess left
            return true;
        }

        if (current.equals(boundNetwork))
            return true;

        Log.d(TAG, "CoT network changed: " + RadioLink.describe(context, boundNetwork)
                + " -> " + RadioLink.describe(context, current) + "; rebinding socket");

        transport.stop();
        bind(current);

        if (!transport.isRunning()) {
            Log.w(TAG, "rebind failed; will retry on the next report");
            boundNetwork = null;
            directedBroadcast = null;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------- address handling

    /** Where a settings string resolved to. */
    static final class Destination {
        final String host;
        final int port;

        Destination(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Splits "host" or "host:port".
     *
     * Deliberately more forgiving than {@link #isValidAddress}: a preference
     * saved by an older build, or hand-edited, should still get CoT out on the
     * default port rather than silently sending nothing.
     *
     * @return null when there is no usable host, which includes the blank
     *         setting meaning "this device only"
     */
    static Destination parse(String destination) {
        if (destination == null)
            return null;

        String host = destination.trim();
        if (host.isEmpty())
            return null;

        int port = DEFAULT_COT_PORT;

        final int colon = host.lastIndexOf(':');
        if (colon > 0) {
            final String portText = host.substring(colon + 1).trim();
            host = host.substring(0, colon).trim();
            try {
                final int parsed = Integer.parseInt(portText);
                if (parsed >= MIN_PORT && parsed <= MAX_PORT)
                    port = parsed;
                else
                    Log.w(TAG, "port " + parsed + " out of range in '"
                            + destination.trim() + "', using " + DEFAULT_COT_PORT);
            } catch (NumberFormatException e) {
                Log.w(TAG, "bad port in '" + destination.trim()
                        + "', using " + DEFAULT_COT_PORT);
            }
        }

        if (BROADCAST_SENTINEL.equalsIgnoreCase(host))
            host = BROADCAST_ADDRESS;

        return host.isEmpty() ? null : new Destination(host, port);
    }

    /**
     * Syntactic check for the settings field, so a typo is caught while the
     * operator is looking at it instead of becoming silence on the map. Blank
     * is valid and means "this device only".
     *
     * Does not resolve the host: DNS would block the UI thread. A well-formed
     * but wrong address still fails at send time and is logged by
     * {@link #onError}.
     */
    public static boolean isValidAddress(String destination) {
        if (destination == null || destination.trim().isEmpty())
            return true;

        final String dest = destination.trim();

        final int colon = dest.lastIndexOf(':');
        if (colon > 0) {
            try {
                final int port = Integer.parseInt(dest.substring(colon + 1).trim());
                if (port < MIN_PORT || port > MAX_PORT)
                    return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        final Destination parsed = parse(dest);
        return parsed != null && isPlausibleHost(parsed.host);
    }

    /**
     * Letters, digits, dots and hyphens. Enough to reject stray sentinels and
     * fat-fingered addresses without reimplementing hostname grammar.
     */
    private static boolean isPlausibleHost(String host) {
        for (int i = 0; i < host.length(); i++) {
            final char c = host.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '.' && c != '-')
                return false;
        }
        return true;
    }

    // ---------------------------------------------------- settings preview
    
    public enum DestinationKind {
        /** Blank: this device's map only, nothing on the wire. */
        LOCAL_ONLY,
        /** The limited broadcast, which {@link #resolveHost} swaps for the link's. */
        BROADCAST,
        /** A group in 224.0.0.0/4, e.g. ATAK's mesh SA address. */
        MULTICAST,
        /** A single host, on this link as far as can be told without DNS. */
        UNICAST,
        /** A single host that is demonstrably not on this link's subnet. */
        OFF_SUBNET,
        /** Rejected by {@link #isValidAddress}. */
        INVALID
    }

    /** A settings string resolved for display: what would actually be addressed. */
    public static final class Preview {
        public final DestinationKind kind;

        /** Where the datagram would go, broadcast already resolved; null if none. */
        public final String host;

        /** Destination port, or 0 when nothing would be sent. */
        public final int port;

        Preview(DestinationKind kind, String host, int port) {
            this.kind = kind;
            this.host = host;
            this.port = port;
        }
    }

    /**
     * Classifies a settings string against the link CoT would leave by.
     *
     * Deliberately free of {@link Log} and of any Android call, so it is testable on
     * the JVM: {@link #isValidAddress} rejects a bad port before {@link #parse} is
     * reached, which is the only path in either that would log.
     *
     * @param subnet the CoT link's subnet, or null when it is down or unreadable --
     *               in which case nothing can be said about reachability and a host
     *               is reported as an ordinary unicast rather than guessed at
     */
    public static Preview preview(String destination, RadioLink.Subnet subnet) {
        if (destination == null || destination.trim().isEmpty())
            return new Preview(DestinationKind.LOCAL_ONLY, null, 0);

        if (!isValidAddress(destination))
            return new Preview(DestinationKind.INVALID, null, 0);

        final Destination dest = parse(destination);
        if (dest == null)
            return new Preview(DestinationKind.INVALID, null, 0);

        if (BROADCAST_ADDRESS.equals(dest.host)) {
            // show what resolveHost would really put on the wire, not what was typed
            final String host = subnet != null ? subnet.broadcast : BROADCAST_ADDRESS;
            return new Preview(DestinationKind.BROADCAST, host, dest.port);
        }

        if (RadioLink.isMulticast(dest.host))
            return new Preview(DestinationKind.MULTICAST, dest.host, dest.port);

        // a hostname cannot be placed without DNS, which must not happen here, so it
        // is left as a plain unicast rather than called unreachable
        if (RadioLink.isIpv4(dest.host) && subnet != null
                && !subnet.contains(dest.host))
            return new Preview(DestinationKind.OFF_SUBNET, dest.host, dest.port);

        return new Preview(DestinationKind.UNICAST, dest.host, dest.port);
    }

    // ------------------------------------------------- UdpTransport.Listener

    @Override
    public void onPacketReceived(String sourceAddress, int sourcePort, byte[] data) {
        // transmit only
    }

    @Override
    public void onError(String message, Throwable t) {
        Log.e(TAG, "CoT send failed: " + message, t);
    }
}
