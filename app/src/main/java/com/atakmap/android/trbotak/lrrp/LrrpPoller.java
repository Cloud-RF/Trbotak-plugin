
package com.atakmap.android.trbotak.lrrp;

import android.content.Context;
import android.net.Network;

import com.atakmap.android.trbotak.net.RadioLink;
import com.atakmap.android.trbotak.net.UdpTransport;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls a set of MotoTRBO radios for their location over LRRP.
 *
 * Replies are matched by transaction id, not source address: the PAN gateway may
 * answer from its own IP or the radio's synthetic one, and may split a reply into an
 * ACK plus a later frame carrying the position.
 */
public class LrrpPoller implements UdpTransport.Listener {

    public static final String TAG = "LrrpPoller";

    /** Subscribe once, radio pushes on its own timer. Keeps its GNSS awake. */
    public static final String MODE_TRIGGERED = "triggered";

    /** Ask each time. Leaves nothing running on the radio if we die uncleanly. */
    public static final String MODE_IMMEDIATE = "immediate";
    
    public static final boolean REQUEST_ALTITUDE = true;

    public interface Listener {
        /**
         * A fresh position was decoded.  Called on the main thread.
         *
         * @param altHae height, or null when the frame carried none -- a 2D GPS fix
         *               reports position without one, and a bare TOKEN_LOCATION has
         *               no field to put it in even if the radio knew it
         */
        void onFix(String radioKey, double lat, double lon, Double altHae);

        /** An ACK or status frame arrived without a position. */
        void onStatus(String radioKey, Integer statusCode);

        void onPollError(String message, Throwable t);
    }

    /** How to address one radio, and how to recognise its replies. */
    private static class Radio {
        final String key;           // what the user typed: radio id or IP
        final String dstIp;
        final int txId;
        byte[] requestPayload;      // rebuilt when the interval changes
        final byte[] stopPayload;   // triggered mode only

        long lastAttempt = 0L;      // ms; 0 = never sent
        long lastReply = 0L;

        Radio(String key, String dstIp, int txId,
                byte[] requestPayload, byte[] stopPayload) {
            this.key = key;
            this.dstIp = dstIp;
            this.txId = txId;
            this.requestPayload = requestPayload;
            this.stopPayload = stopPayload;
        }
    }

    private final Context context;
    private final Listener listener;
    private final UdpTransport transport;
    private final String mode;

    private volatile long pollPeriodMs;
    private volatile long pollMaxWaitMs;

    private final Map<String, Radio> radios = new HashMap<>();
    private final Map<Integer, Radio> byTxId = new HashMap<>();
    private final Object lock = new Object();

    private ScheduledExecutorService scheduler;

    /** The network the socket is currently bound to; null = default network. */
    private volatile Network boundNetwork;

    /** Ticks since the last network re-check. Written by both threads. */
    private volatile int ticksSinceNetworkCheck = 0;

    private static final int NETWORK_CHECK_EVERY_TICKS = 5;

    public LrrpPoller(Context context, Listener listener,
            int pollPeriodSeconds, String mode) {
        this.context = context;
        this.listener = listener;
        this.mode = mode;
        this.pollPeriodMs = pollPeriodSeconds * 1000L;
        this.pollMaxWaitMs = pollPeriodMs * 3L;
        this.transport = new UdpTransport(this);
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * @param network the radio's link -- Bluetooth PAN, USB or, when the radio is
     *                paired to the same wifi network as the tablet, wifi. Null uses
     *                the default network. Without this, packets for a tethered radio
     *                leave via wifi, and packets for a wifi radio leave via cellular.
     */
    public void start(Network network) {
        boundNetwork = network;
        transport.setNetwork(network, RadioLink.interfaceName(context, network));
        transport.start(LrrpCodec.LRRP_PORT);

        if (!transport.isRunning()) {
            Log.e(TAG, "transport failed to bind port " + LrrpCodec.LRRP_PORT);
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
        Log.d(TAG, "started, mode=" + mode + " period=" + (pollPeriodMs / 1000) + "s");
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        // Best effort: cancel any triggered subscriptions so the radio does not
        // keep transmitting after we are gone.
        if (MODE_TRIGGERED.equals(mode) && transport.isRunning()) {
            synchronized (lock) {
                for (Radio r : radios.values()) {
                    if (r.stopPayload != null) {
                        transport.send(r.dstIp, LrrpCodec.LRRP_PORT, r.stopPayload);
                        Log.d(TAG, "sent TriggeredLocationStop to " + r.key);
                    }
                }
            }
        }

        transport.stop();
    }

    public boolean isRunning() {
        return transport.isRunning();
    }

    /**
     * Changes the poll interval while running; takes effect on the next tick.
     *
     * In triggered mode the interval lives inside the subscription payload, so each
     * radio's payload is rebuilt and re-sent -- otherwise the radio would keep
     * reporting at the old rate until it was removed and re-added.
     */
    public void setPollPeriodSeconds(int seconds) {
        this.pollPeriodMs = seconds * 1000L;
        this.pollMaxWaitMs = pollPeriodMs * 3L;

        if (MODE_TRIGGERED.equals(mode)) {
            synchronized (lock) {
                for (Radio r : radios.values()) {
                    r.requestPayload = LrrpCodec.buildTriggeredStart(
                            r.txId, seconds, REQUEST_ALTITUDE);
                    r.lastAttempt = 0L;     // due now: re-subscribe at the new rate
                }
            }
            Log.d(TAG, "re-subscribing " + radios.size() + " radio(s) at " + seconds + "s");
        }

        Log.d(TAG, "poll period now " + seconds + "s");
    }

    /**
     * Brings the next network check forward to the next tick.
     *
     * Called when the operator changes the NIC setting: the periodic check would
     * pick it up anyway, but several seconds of sending to the old link looks like
     * the setting did not take.
     */
    public void recheckNetwork() {
        ticksSinceNetworkCheck = NETWORK_CHECK_EVERY_TICKS;
    }

    // --------------------------------------------------------------- radios

    /**
     * Registers a radio.  {@code key} is either a numeric radio UID (mapped into
     * 12.0.0.0/8) or a literal IPv4 address -- the latter needs no route, so
     * pointing straight at the PAN gateway is the simplest thing that can work.
     */
    public void addRadio(String key) {
        if (key == null || key.trim().isEmpty())
            return;
        final String k = key.trim();

        final String dstIp;
        final int txId;
        try {
            if (k.contains(".")) {
                dstIp = k;
                txId = LrrpCodec.txIdForIp(k);
            } else {
                final int radioId = Integer.parseInt(k);
                dstIp = LrrpCodec.radioIdToDstIp(radioId);
                txId = LrrpCodec.txIdForRadioId(radioId);
            }
        } catch (Exception e) {
            Log.w(TAG, "not a radio id or IP, ignoring: " + k, e);
            return;
        }

        final byte[] request;
        final byte[] stop;
        if (MODE_TRIGGERED.equals(mode)) {
            request = LrrpCodec.buildTriggeredStart(txId,
                    (int) (pollPeriodMs / 1000L), REQUEST_ALTITUDE);
            stop = LrrpCodec.buildTriggeredStop(txId);
        } else {
            request = LrrpCodec.buildImmediateRequest(txId, REQUEST_ALTITUDE);
            stop = null;
        }

        synchronized (lock) {
            if (radios.containsKey(k))
                return;
            final Radio existing = byTxId.get(txId);
            if (existing != null) {
                Log.w(TAG, "tx_id collision between " + k + " and " + existing.key
                        + "; ignoring " + k);
                return;
            }
            final Radio radio = new Radio(k, dstIp, txId, request, stop);
            radios.put(k, radio);
            byTxId.put(txId, radio);
        }

        Log.d(TAG, "added radio " + k + " -> " + dstIp
                + " tx_id=0x" + Integer.toHexString(txId));
    }

    public void removeRadio(String key) {
        synchronized (lock) {
            final Radio r = radios.remove(key);
            if (r != null) {
                byTxId.remove(r.txId);
                if (r.stopPayload != null && transport.isRunning())
                    transport.send(r.dstIp, LrrpCodec.LRRP_PORT, r.stopPayload);
            }
        }
    }

    public List<String> getRadioKeys() {
        synchronized (lock) {
            return new ArrayList<>(radios.keySet());
        }
    }

    /**
     * Fakes a reply from {@code radioKey} over the real socket, so the parser,
     * correlation and everything downstream run for real. Tests all but the radio.
     */
    public void simulateLocation(String radioKey, double lat, double lon,
            Double altitude) {
        final Radio r;
        synchronized (lock) {
            r = radios.get(radioKey);
        }
        if (r == null) {
            Log.w(TAG, "cannot simulate unknown radio " + radioKey);
            return;
        }
        if (!transport.isRunning()) {
            Log.w(TAG, "cannot simulate: transport not running");
            return;
        }

        transport.send("127.0.0.1", LrrpCodec.LRRP_PORT,
                LrrpCodec.buildLocationResponse(r.txId, lat, lon, altitude));
    }

    /**
     * Injects arbitrary bytes as though a radio had sent them.
     *
     * Unlike a unit test this crosses the real socket and the main-thread hand-off,
     * which is the part that matters: the receive loop's try/catch does not cover
     * the listener, so a parser that throws would take ATAK down rather than log.
     */
    public void simulateRaw(byte[] payload) {
        if (!transport.isRunning()) {
            Log.w(TAG, "cannot simulate: transport not running");
            return;
        }
        Log.d(TAG, "injecting " + (payload == null ? 0 : payload.length)
                + " raw bytes");
        transport.send("127.0.0.1", LrrpCodec.LRRP_PORT, payload);
    }

    /** Convenience for simulateRaw: a status-only reply, e.g. 0x010 for no-gps. */
    public void simulateStatus(String radioKey, int statusCode) {
        final Radio r;
        synchronized (lock) {
            r = radios.get(radioKey);
        }
        if (r == null) {
            Log.w(TAG, "cannot simulate unknown radio " + radioKey);
            return;
        }
        simulateRaw(LrrpCodec.buildStatusResponse(r.txId, statusCode));
    }

    // ------------------------------------------------------------ scheduling

    /** Sends whatever is due. Runs on the scheduler thread, once a second. */
    private void tick() {
        try {
            if (++ticksSinceNetworkCheck >= NETWORK_CHECK_EVERY_TICKS) {
                ticksSinceNetworkCheck = 0;
                maybeRebindNetwork();
            }

            final long now = System.currentTimeMillis();
            final List<Radio> due = new ArrayList<>();

            synchronized (lock) {
                for (Radio r : radios.values()) {
                    if (now >= nextDue(r))
                        due.add(r);
                }
            }

            // sends are deliberately outside the lock: send() only queues, but
            // holding a lock across it invites deadlock as this grows
            for (Radio r : due) {
                transport.send(r.dstIp, LrrpCodec.LRRP_PORT, r.requestPayload);
                synchronized (lock) {
                    r.lastAttempt = System.currentTimeMillis();
                }
            }
        } catch (Throwable t) {
            // an escaping exception cancels all future runs of a scheduled task
            Log.e(TAG, "poll tick failed", t);
        }
    }

    /**
     * Triggered: stay off the air unless the radio has been silent for
     * poll_max_wait, then assume the subscription died and re-subscribe.
     *
     * Immediate: next request is poll_period after the last *reply*, so a slow radio
     * gets genuine spacing rather than a fixed drumbeat; poll_max_wait after the
     * last attempt if nothing came back at all.
     */
    private long nextDue(Radio r) {
        if (r.lastAttempt == 0L)
            return 0L;                          // never sent -> send now

        if (MODE_TRIGGERED.equals(mode)) {
            final long anchor = r.lastReply > r.lastAttempt ? r.lastReply : r.lastAttempt;
            return anchor + pollMaxWaitMs;
        }

        if (r.lastReply > r.lastAttempt)
            return r.lastReply + pollPeriodMs;
        return r.lastAttempt + pollMaxWaitMs;
    }

    // ------------------------------------------------- UdpTransport.Listener

    /** On the main thread: UdpTransport marshals for us. */
    @Override
    public void onPacketReceived(String sourceAddress, int sourcePort, byte[] data) {
        final LrrpResponse response = LrrpCodec.parseResponse(data);

        if (response == null) {
            Log.d(TAG, "rx " + sourceAddress + " unrecognised frame hex="
                    + LrrpCodec.toHex(data, data.length));
            return;
        }

        final Radio radio;
        synchronized (lock) {
            radio = byTxId.get(response.txId());
            if (radio != null)
                radio.lastReply = System.currentTimeMillis();
        }

        if (radio == null) {
            Log.d(TAG, "rx for unknown tx_id 0x" + Integer.toHexString(response.txId()));
            return;
        }

        Log.d(TAG, "rx " + sourceAddress + " radio=" + radio.key + " " + response);

        if (response.hasLocation())
            listener.onFix(radio.key, response.lat(), response.lon(), response.alt());
        else
            listener.onStatus(radio.key, response.status());
    }

    @Override
    public void onError(String message, Throwable t) {
        listener.onPollError(message, t);
    }

    // ------------------------------------------------------------- networks

    /**
     * Re-binds when the radio's link appears, vanishes or is replaced.
     *
     * Android mints a new Network every time Bluetooth PAN reconnects, and a socket
     * bound to the old one fails with ENETUNREACH forever. Resolving once at startup
     * meant the plugin only worked if the radio was connected before ATAK launched.
     *
     * {@link RadioLink#find} reads the NIC preference on every call, so switching
     * the setting between wifi and a tethered link moves the socket here too --
     * within one network check -- with nothing else to wire up.
     */
    private void maybeRebindNetwork() {
        final Network current = RadioLink.find(context);

        final boolean same = current == null
                ? boundNetwork == null
                : current.equals(boundNetwork);
        if (same && transport.isRunning())
            return;

        Log.d(TAG, "radio network changed: " + boundNetwork + " -> " + current
                + " (" + RadioLink.describe(context, current) + "); rebinding socket");

        transport.stop();
        transport.setNetwork(current, RadioLink.interfaceName(context, current));
        transport.start(LrrpCodec.LRRP_PORT);
        boundNetwork = current;

        if (!transport.isRunning())
            Log.w(TAG, "rebind failed; will retry when the network next changes");
    }
}
