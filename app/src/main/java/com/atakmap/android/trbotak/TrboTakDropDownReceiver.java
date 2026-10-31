
package com.atakmap.android.trbotak;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.atak.plugins.impl.PluginLayoutInflater;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.dropdown.DropDown;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.trbotak.cot.CotFactory;
import com.atakmap.android.trbotak.cot.CotSender;
import com.atakmap.android.trbotak.lrrp.LrrpCodec;
import com.atakmap.android.trbotak.lrrp.LrrpPoller;
import com.atakmap.android.trbotak.net.RadioLink;
import com.cloudrf.trbotak.R;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.map.CameraController;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The dropdown panel: the radio list, its status dots, and the add and settings
 * dialogs.
 *
 * {@link LrrpPoller} owns the socket and the polling; this class only turns fixes
 * into rows and CoT markers.
 */
public class TrboTakDropDownReceiver extends DropDownReceiver
        implements DropDown.OnStateListener {

    public static final String TAG = "TrboTakDropDownReceiver";

    public static final String SHOW_PLUGIN = "com.atakmap.android.trbotak.SHOW_PLUGIN";

    private final View rootView;

    private final Context pluginContext;

    private final TrboTakPreferences prefs;

    private final LrrpPoller poller;

    /** Pushes CoT to the address in Settings, if one is set. */
    private final CotSender cotSender = new CotSender();

    private static final long GREEN_WITHIN_MS = 20_000L;

    private static final long AMBER_WITHIN_MS = 60_000L;

    /** How often the dots are re-evaluated. */
    private static final long STATUS_TICK_MS = 2_000L;

    /** Rows keyed by radio id, so repeat reports update in place. */
    private final Map<String, View> rowsById = new LinkedHashMap<>();

    /** id -> callsign, mirrored to preferences so the list survives a reload. */
    private final Map<String, String> radioNames = new LinkedHashMap<>();

    /** Wall-clock ms of the last successful fix per radio; absent = never. */
    private final Map<String, Long> lastFixMs = new HashMap<>();

    /** Last known position per radio. */
    private final Map<String, GeoPoint> lastPos = new HashMap<>();
    
    private final Map<String, Double> lastAlt = new HashMap<>();

    private final Map<String, String> lastStatus = new HashMap<>();

    /** Seeds the simulate button. */
    private int simulateCount = 0;

    /**
     * Height the simulate button claims, in metres. Deliberately a value nothing
     * else would produce, so it is obvious on the marker whether the altitude came
     * from here.
     */
    private static final Double SIMULATE_ALTITUDE_M = 123.5;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());

    /** Re-colours every dot on a timer, so status decays without new traffic. */
    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            refreshAllRowStatus();
            statusHandler.postDelayed(this, STATUS_TICK_MS);
        }
    };

    public TrboTakDropDownReceiver(final MapView mapView, final Context context) {
        super(mapView);
        this.pluginContext = context;

        this.prefs = new TrboTakPreferences(mapView.getContext());

        rootView = PluginLayoutInflater.inflate(context, R.layout.layout, null);

        rootView.findViewById(R.id.add_button).setOnClickListener(v -> showAddUserDialog());

        rootView.findViewById(R.id.settings_button).setOnClickListener(
                v -> showSettingsDialog());

        rootView.findViewById(R.id.simulate_button).setOnClickListener(
                v -> sendSimulatedReport());

        poller = new LrrpPoller(mapView.getContext(), new LrrpPoller.Listener() {
            @Override
            public void onFix(String radioKey, double lat, double lon, Double altHae) {
                onRadioFix(radioKey, lat, lon, altHae);
            }

            @Override
            public void onStatus(String radioKey, Integer statusCode) {
                final String name = statusCode != null
                        ? LrrpCodec.statusName(statusCode) : "ack";
                lastStatus.put(radioKey, name);
                refreshRowStatus(radioKey);
                Log.d(TAG, "radio " + radioKey + " status=" + name);
            }

            @Override
            public void onPollError(String message, Throwable t) {
                Log.e(TAG, "poll error: " + message, t);
                toast(message);
            }
        }, prefs.getRefreshSeconds(), LrrpPoller.MODE_TRIGGERED);

        cotSender.start(mapView.getContext());

        poller.start(RadioLink.find(mapView.getContext()));

        restoreRadios();

        statusHandler.post(statusTick);
    }

    /** Rebuilds the row list from preferences and re-enrols each radio. */
    private void restoreRadios() {
        final Map<String, String> saved = prefs.getRadios();
        for (Map.Entry<String, String> e : saved.entrySet()) {
            upsertRow(e.getKey(), e.getValue());
            refreshRowStatus(e.getKey());   // red until something reports
            poller.addRadio(e.getKey());
        }
        Log.d(TAG, "restored " + saved.size() + " radio(s)");
    }

    /** Dot colour is purely a function of fix age, so status decays on its own. */
    private int dotForAge(Long lastFix) {
        if (lastFix == null)
            return R.drawable.dot_red;

        final long age = System.currentTimeMillis() - lastFix;
        if (age < GREEN_WITHIN_MS)
            return R.drawable.dot_green;
        if (age < AMBER_WITHIN_MS)
            return R.drawable.dot_amber;
        return R.drawable.dot_red;
    }

    private void refreshRowStatus(String id) {
        final Long lastFix = lastFixMs.get(id);
        setRowStatus(id, dotForAge(lastFix));

        final View row = rowsById.get(id);
        if (row == null)
            return;

        final String detail;
        if (lastFix != null) {
            final long ageSec = (System.currentTimeMillis() - lastFix) / 1000L;
            detail = pluginContext.getString(R.string.row_fix_age, ageSec);
        } else {
            // never had a position: say why, if the radio told us
            final String status = lastStatus.get(id);
            detail = status != null ? status
                    : pluginContext.getString(R.string.row_no_reply);
        }

        ((TextView) row.findViewById(R.id.row_detail)).setText(detail);
    }

    private void refreshAllRowStatus() {
        for (String id : rowsById.keySet())
            refreshRowStatus(id);
    }

    private void panToRadio(String id) {
        final GeoPoint point = lastPos.get(id);
        if (point == null) {
            final String name = radioNames.get(id);
            toast(pluginContext.getString(R.string.no_position_yet,
                    name != null ? name : id));
            return;
        }

        CameraController.Programmatic.panTo(
                getMapView().getRenderer3(), point, true);
        Log.d(TAG, "panned to " + id + " at " + point);
    }

    private void confirmRemoveRadio(final String id) {
        final String name = radioNames.get(id);

        new AlertDialog.Builder(getMapView().getContext())
                .setTitle(pluginContext.getString(R.string.remove_radio_title))
                .setMessage(pluginContext.getString(R.string.remove_radio_message,
                        name != null ? name : id, id))
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .setPositiveButton(pluginContext.getString(R.string.remove),
                        (d, which) -> removeRadio(id))
                .show();
    }

    private void removeRadio(String id) {
        // stop polling first, so nothing can re-add a row mid-removal
        poller.removeRadio(id);

        final View row = rowsById.remove(id);
        if (row != null) {
            final LinearLayout list = rootView.findViewById(R.id.user_list);
            list.removeView(row);
        }

        radioNames.remove(id);
        lastFixMs.remove(id);
        lastPos.remove(id);
        lastAlt.remove(id);         // else a re-added radio inherits an old height
        prefs.setRadios(radioNames);

        // the CoT uid is the radio id, so the marker is findable by the same key
        final MapItem marker = getMapView().getRootGroup().deepFindUID(id);
        if (marker != null)
            marker.removeFromGroup();

        Log.d(TAG, "removed radio " + id);
    }

    /** A radio reported in: refresh its row and move its marker. */
    private void onRadioFix(String radioKey, double lat, double lon, Double altHae) {
        final View row = rowsById.get(radioKey);
        final String callsign = row != null
                ? ((TextView) row.findViewById(R.id.row_name)).getText().toString()
                : radioKey;

        upsertRow(radioKey, callsign);
        lastFixMs.put(radioKey, System.currentTimeMillis());
        lastPos.put(radioKey, new GeoPoint(lat, lon));
        refreshRowStatus(radioKey);

        // only overwrite on a reply that actually carried one, so a 2D fix following
        // a 3D one keeps the last height rather than blanking it
        if (altHae != null)
            lastAlt.put(radioKey, altHae);

        // create() takes a primitive, so this must never be left null -- and unknown
        // rather than sea level when nothing has ever reported a height
        final Double held = lastAlt.get(radioKey);
        final CotEvent event = CotFactory.create(radioKey, callsign, lat, lon,
                held != null ? held : CotPoint.UNKNOWN);

        if (!event.isValid()) {
            Log.w(TAG, "refusing to dispatch invalid CoT for " + radioKey);
            return;
        }

        CotMapComponent.getInternalDispatcher().dispatch(event);   // this tablet

        // and out to other TAK clients, if an address is configured
        cotSender.send(event, prefs.getCotAddress());
    }

    /** Real LRRP frame over the real socket, so everything but the radio runs. */
    private void sendSimulatedReport() {
        final List<String> keys = poller.getRadioKeys();
        if (keys.isEmpty()) {
            toast("Add a radio first");
            return;
        }

        // walk away from ATAK's current centre so the marker is visible and moves
        final GeoPoint centre = getMapView().getCenterPoint().get();
        final double lat = centre.getLatitude() + (simulateCount * 0.0002);
        final double lon = centre.getLongitude() + (simulateCount * 0.0002);
        simulateCount++;

        final String key = keys.get(0);
        poller.simulateLocation(key, lat, lon, SIMULATE_ALTITUDE_M);
        Log.d(TAG, "simulated LRRP location for radio " + key);
    }

    private void toast(String text) {
        Toast.makeText(getMapView().getContext(), text, Toast.LENGTH_SHORT).show();
    }

    private void showSettingsDialog() {
        final View dialogView = PluginLayoutInflater.inflate(
                pluginContext, R.layout.settings_dialog, null);

        final Spinner nicSpinner = dialogView.findViewById(R.id.settings_nic);
        final SeekBar refreshBar = dialogView.findViewById(R.id.settings_refresh_bar);
        final TextView refreshValue = dialogView.findViewById(R.id.settings_refresh_value);
        final EditText cotField = dialogView.findViewById(R.id.settings_cot);

        // NIC
        selectSpinnerValue(nicSpinner, prefs.getNic());

        // Refresh
        final int seconds = prefs.getRefreshSeconds();
        refreshBar.setMax(TrboTakPreferences.MAX_REFRESH_SECONDS
                - TrboTakPreferences.MIN_REFRESH_SECONDS);
        refreshBar.setProgress(seconds - TrboTakPreferences.MIN_REFRESH_SECONDS);
        refreshValue.setText(pluginContext.getString(
                R.string.settings_seconds_format, seconds));

        refreshBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                refreshValue.setText(pluginContext.getString(
                        R.string.settings_seconds_format,
                        progress + TrboTakPreferences.MIN_REFRESH_SECONDS));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });

        cotField.setText(prefs.getCotAddress());

        // The address box is the one setting whose mistakes are invisible, so it
        // gets a line saying what the typed value resolves to on the selected link.
        final TextView cotPreview = dialogView.findViewById(R.id.settings_cot_preview);
        final Runnable refreshPreview = () -> showCotPreview(cotPreview,
                String.valueOf(nicSpinner.getSelectedItem()),
                cotField.getText().toString());

        cotField.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                refreshPreview.run();
            }

            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }
        });

        // the NIC decides which link the address is resolved against, so the same
        // text can mean different things before and after this changes
        nicSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos,
                    long id) {
                refreshPreview.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        refreshPreview.run();

        dialogView.findViewById(R.id.settings_cot_help).setOnClickListener(
                v -> showCotHelpDialog());

        final AlertDialog dialog = new AlertDialog.Builder(getMapView().getContext())
                .setTitle(pluginContext.getString(R.string.settings_title))
                .setView(dialogView)
                .setNegativeButton(pluginContext.getString(R.string.cancel), null)
                .setPositiveButton(pluginContext.getString(R.string.save), null)
                .create();

        // Save is wired after show() rather than through setPositiveButton: the
        // built-in handler always dismisses, and a rejected CoT address has to
        // leave the dialog open so it can be corrected.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String cotAddress = cotField.getText().toString().trim();
                    if (!CotSender.isValidAddress(cotAddress)) {
                        toast(pluginContext.getString(R.string.settings_cot_invalid));
                        return;
                    }

                    final String previousNic = prefs.getNic();
                    final String nic = String.valueOf(nicSpinner.getSelectedItem());

                    prefs.setNic(nic);
                    prefs.setRefreshSeconds(refreshBar.getProgress()
                            + TrboTakPreferences.MIN_REFRESH_SECONDS);
                    prefs.setCotAddress(cotAddress);

                    poller.setPollPeriodSeconds(prefs.getRefreshSeconds());

                    if (!nic.equals(previousNic)) {
                        poller.recheckNetwork();
                        warnIfRadiosUnreachableOnWifi();
                    }

                    Log.d(TAG, "settings saved: nic=" + prefs.getNic()
                            + " refresh=" + prefs.getRefreshSeconds()
                            + " cot=" + prefs.getCotAddress());

                    dialog.dismiss();
                }));

        dialog.show();
    }

    private void warnIfRadiosUnreachableOnWifi() {
        if (!TrboTakPreferences.NIC_WIFI.equals(prefs.getNic()))
            return;

        int byId = 0;
        for (String key : poller.getRadioKeys()) {
            if (!key.contains("."))
                byId++;
        }
        if (byId == 0)
            return;

        Log.w(TAG, byId + " radio(s) enrolled by UID will not be reachable over wifi");
        toast(pluginContext.getString(R.string.settings_wifi_radio_ids, byId));
    }

    private void showCotPreview(TextView view, String nic, String typed) {
        final Context atakContext = getMapView().getContext();
        final RadioLink.Subnet subnet =
                RadioLink.subnetOf(atakContext, RadioLink.find(atakContext, nic));

        final CotSender.Preview preview = CotSender.preview(typed, subnet);

        final String text;
        int colour = R.color.text_grey;

        switch (preview.kind) {
            case LOCAL_ONLY:
                text = pluginContext.getString(R.string.settings_cot_preview_local);
                break;
            case BROADCAST:
                // subnet unknown means the link is down; say so rather than quoting
                // 255.255.255.255 back as though it were the resolved address
                text = subnet != null
                        ? pluginContext.getString(
                                R.string.settings_cot_preview_broadcast,
                                preview.host, preview.port)
                        : pluginContext.getString(
                                R.string.settings_cot_preview_broadcast_unknown,
                                preview.port);
                break;
            case MULTICAST:
                text = pluginContext.getString(
                        R.string.settings_cot_preview_multicast,
                        preview.host, preview.port);
                break;
            case UNICAST:
                text = pluginContext.getString(R.string.settings_cot_preview_unicast,
                        preview.host, preview.port);
                break;
            case OFF_SUBNET:
                text = pluginContext.getString(
                        R.string.settings_cot_preview_off_subnet,
                        preview.host, subnet.cidr());
                colour = R.color.status_amber;
                break;
            default:
                text = pluginContext.getString(R.string.settings_cot_preview_invalid);
                colour = R.color.status_amber;
                break;
        }

        view.setText(text);
        view.setTextColor(pluginContext.getResources().getColor(colour));
    }

    private void showCotHelpDialog() {
        new AlertDialog.Builder(getMapView().getContext())
                .setTitle(pluginContext.getString(R.string.settings_cot_help_title))
                .setMessage(pluginContext.getString(R.string.settings_cot_help))
                .setPositiveButton(pluginContext.getString(R.string.close), null)
                .show();
    }

    private void selectSpinnerValue(Spinner spinner, String value) {
        SpinnerAdapter adapter = spinner.getAdapter();
        if (adapter == null || value == null)
            return;
        for (int i = 0; i < adapter.getCount(); i++) {
            if (value.equals(String.valueOf(adapter.getItem(i)))) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private void showAddUserDialog() {
        final View dialogView = PluginLayoutInflater.inflate(
                pluginContext, R.layout.add_user_dialog, null);

        final EditText idField = dialogView.findViewById(R.id.input_id);
        final EditText nameField = dialogView.findViewById(R.id.input_name);

        final AlertDialog dialog = new AlertDialog.Builder(getMapView().getContext())
                .setTitle("Add MotoTRBO radio")
                .setView(dialogView)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Add", (d, which) -> {

                    String id = idField.getText().toString().trim();
                    String name = nameField.getText().toString().trim();

                    addRow(id, name);
                }).create();

        dialog.show();

        final Button add = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        add.setEnabled(false);

        TextWatcher watcher = new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                add.setEnabled(!idField.getText().toString().trim().isEmpty()
                        && !nameField.getText().toString().trim().isEmpty());
            }
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
        };
        idField.addTextChangedListener(watcher);
        nameField.addTextChangedListener(watcher);
    }

    private void addRow(String id, String name) {
        upsertRow(id, name);
        refreshRowStatus(id);       // red: nothing has reported yet

        // the ID field is the radio UID (or a literal IP): adding enrols it
        poller.addRadio(id);

        prefs.setRadios(radioNames);

        // a UID enrolled while on wifi will never reply; say so now rather than
        // leaving a permanently red dot to explain itself
        warnIfRadiosUnreachableOnWifi();
    }

    /** Creates row if {@code id} is new, otherwise refreshes it in place. */
    private void upsertRow(final String id, String name) {
        View row = rowsById.get(id);

        if (row == null) {
            final LinearLayout list = rootView.findViewById(R.id.user_list);

            // attachToRoot=false keeps the row's own layout params (width,
            // margins) instead of having them dropped by a null parent
            row = PluginLayoutInflater.inflate(pluginContext, R.layout.user_row,
                    list, false);

            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    panToRadio(id);
                }
            });

            row.findViewById(R.id.row_delete).setOnClickListener(
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            confirmRemoveRadio(id);
                        }
                    });

            list.addView(row);
            rowsById.put(id, row);
        }

        ((TextView) row.findViewById(R.id.row_id)).setText(id);
        ((TextView) row.findViewById(R.id.row_name)).setText(name);
        radioNames.put(id, name);
    }

    private void setRowStatus(String id, int dotDrawable) {
        final View row = rowsById.get(id);
        if (row != null)
            row.findViewById(R.id.row_status).setBackgroundResource(dotDrawable);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (SHOW_PLUGIN.equals(intent.getAction())) {
            Log.d(TAG, "showing dropdown");
            showDropDown(rootView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT,
                    false, this);
        }
    }

    @Override
    protected void disposeImpl() {
        statusHandler.removeCallbacks(statusTick);

        // stop() also cancels any triggered subscriptions, so the radio does not
        // keep transmitting after the plugin goes away
        poller.stop();
        cotSender.stop();
    }

    @Override
    public void onDropDownSelectionRemoved() {
    }

    @Override
    public void onDropDownVisible(boolean v) {
    }

    @Override
    public void onDropDownSizeChanged(double width, double height) {
    }

    @Override
    public void onDropDownClose() {
    }
}