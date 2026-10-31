
package com.atakmap.android.trbotak.net;

import android.net.Network;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.coremap.log.Log;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal UDP send/receive engine.
 *
 * One socket handles both directions, so replies come back on the port we are
 * already listening on -- which is what LRRP expects.
 *
 * The socket is a {@link MulticastSocket} even though nothing here joins a group:
 * it is a DatagramSocket in every other respect, and it is the only way to say
 * which interface multicast leaves by. CoT to a group such as 239.2.3.1:6969 --
 * how ATAK meshes over wifi -- otherwise follows the kernel's multicast route,
 * which is not necessarily the link the radio is on.
 *
 * Receiving blocks its own thread; sends are queued to a single-thread executor so
 * callers never block on the network. Listener callbacks are posted to the main
 * looper, so handlers can touch views directly.
 */
public class UdpTransport {

    public static final String TAG = "UdpTransport";

    /** Maximum a single UDP datagram can carry. */
    private static final int MAX_DATAGRAM = 64 * 1024;

    private static final int MULTICAST_TTL = 1;

    public interface Listener {
        void onPacketReceived(String sourceAddress, int sourcePort, byte[] data);

        void onError(String message, Throwable t);
    }

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Link to pin the socket to; null uses the default network. */
    private Network network;

    /** Interface behind {@link #network}, for multicast egress; null to leave it. */
    private String interfaceName;

    private ExecutorService sendExecutor;
    private MulticastSocket socket;
    private Thread receiveThread;

    private final Object lock = new Object();

    /** Written by callers, read by the receive thread. */
    private volatile boolean running;

    public UdpTransport(Listener listener) {
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** Call before {@link #start}; binding happens when the socket is created. */
    public void setNetwork(Network network) {
        setNetwork(network, null);
    }

    /**
     * Call before {@link #start}; binding happens when the socket is created.
     *
     * @param network       link to pin the socket to, or null for the default one
     * @param interfaceName that link's interface, e.g. "wlan0". Only multicast
     *                      needs it -- unicast and broadcast follow the pinned
     *                      network on their own. Null skips the call.
     */
    public void setNetwork(Network network, String interfaceName) {
        this.network = network;
        this.interfaceName = interfaceName;
    }

    /** @param listenPort local port to bind, or 0 for an ephemeral one */
    public void start(final int listenPort) {
        synchronized (lock) {
            if (running) {
                Log.d(TAG, "already listening, ignoring start request");
                return;
            }

            try {
                // null address -> unbound, so the options below can be set before
                // the bind rather than after it, when several no longer take
                socket = new MulticastSocket(null);

                // Off, so a port clash throws here rather than binding "successfully"
                // and silently receiving nothing -- datagrams go to whichever socket
                // bound first. MulticastSocket turns this on for us, hence the
                // explicit reset.
                socket.setReuseAddress(false);

                // Broadcast destinations are refused with EACCES without this.
                socket.setBroadcast(true);

                socket.setTimeToLive(MULTICAST_TTL);

                socket.bind(new InetSocketAddress(listenPort));

                // Android sends over the *default* network unless a socket is pinned,
                // so traffic for a radio on Bluetooth PAN would leave via wifi and
                // vanish -- and traffic for a radio on wifi would leave via cellular.
                // This is the rootless equivalent of a routing table entry.
                if (network != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    network.bindSocket(socket);
                    Log.d(TAG, "socket bound to network " + network);
                }

                bindMulticastInterface();

                running = true;
                sendExecutor = Executors.newSingleThreadExecutor();

                receiveThread = new Thread(this::receiveLoop, "trbotak-udp-rx");
                receiveThread.start();

                Log.d(TAG, "listening on port " + listenPort);
            } catch (Exception e) {
                closeQuietly();
                running = false;
                postError("Unable to listen on port " + listenPort, e);
            }
        }
    }

    /** Safe to call when not running. */
    public void stop() {
        final Thread rx;
        synchronized (lock) {
            if (!running)
                return;
            running = false;
            rx = receiveThread;
            receiveThread = null;

            if (sendExecutor != null) {
                sendExecutor.shutdownNow();
                sendExecutor = null;
            }
            // there is no way to interrupt a blocked receive(); closing the socket
            // makes it throw, which is how the loop below exits
            closeQuietly();
        }

        if (rx != null) {
            try {
                rx.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        Log.d(TAG, "stopped");
    }

    /** Returns immediately; failures arrive via {@link Listener#onError}. */
    public void send(final String host, final int port, final byte[] payload) {
        final ExecutorService exec;
        synchronized (lock) {
            if (!running || sendExecutor == null) {
                postError("Not listening -- start the socket before sending", null);
                return;
            }
            exec = sendExecutor;
        }

        exec.execute(() -> {
            try {
                final InetAddress dest = InetAddress.getByName(host);
                final DatagramPacket packet =
                        new DatagramPacket(payload, payload.length, dest, port);

                // stop() may have run since this was queued
                final DatagramSocket s;
                synchronized (lock) {
                    s = socket;
                }
                if (s == null || s.isClosed()) {
                    postError("Socket closed before send completed", null);
                    return;
                }
                s.send(packet);
                Log.d(TAG, "sent " + payload.length + " bytes to " + host + ":" + port);
            } catch (Exception e) {
                postError("Send to " + host + ":" + port + " failed", e);
            }
        });
    }

    // ------------------------------------------------------------------ internals

    /**
     * Points multicast at the radio's link.
     */
    private void bindMulticastInterface() {
        if (interfaceName == null)
            return;

        try {
            final NetworkInterface nif = NetworkInterface.getByName(interfaceName);
            if (nif == null) {
                Log.w(TAG, "no interface named " + interfaceName
                        + "; leaving multicast on the default route");
                return;
            }
            socket.setNetworkInterface(nif);
            Log.d(TAG, "multicast will leave via " + interfaceName);
        } catch (Exception e) {
            Log.w(TAG, "could not send multicast via " + interfaceName, e);
        }
    }

    private void receiveLoop() {
        final byte[] buffer = new byte[MAX_DATAGRAM];   // reused every iteration

        while (running) {
            try {
                final DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                // must copy: the listener runs later on another thread, by which
                // point the shared buffer holds the next datagram
                final byte[] data = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(),
                        data, 0, packet.getLength());

                final String srcAddr = packet.getAddress().getHostAddress();
                final int srcPort = packet.getPort();

                mainHandler.post(() -> listener.onPacketReceived(srcAddr, srcPort, data));
            } catch (Exception e) {
                // if stopping, this is the expected close-during-receive
                if (running)
                    postError("Receive failed", e);
            }
        }
    }

    /** Call holding {@link #lock}. */
    private void closeQuietly() {
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    private void postError(final String message, final Throwable t) {
        Log.e(TAG, message, t);
        mainHandler.post(() -> listener.onError(message, t));
    }
}
