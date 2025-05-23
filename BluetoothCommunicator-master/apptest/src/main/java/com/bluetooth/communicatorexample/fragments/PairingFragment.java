package com.bluetooth.communicatorexample.fragments;

import android.animation.Animator;
import android.bluetooth.BluetoothAdapter;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;

import com.bluetooth.communicator.BluetoothCommunicator;
import com.bluetooth.communicator.Peer;
import com.bluetooth.communicator.tools.Timer;
import com.bluetooth.communicatorexample.Global;
import com.bluetooth.communicatorexample.MainActivity;
import com.bluetooth.communicatorexample.R;
import com.bluetooth.communicatorexample.gui.ButtonSearch;
import com.bluetooth.communicatorexample.gui.CustomAnimator;
import com.bluetooth.communicatorexample.gui.GuiTools;
import com.bluetooth.communicatorexample.gui.PeerListAdapter;
import com.bluetooth.communicatorexample.gui.RequestDialog;
import com.bluetooth.communicatorexample.tools.Tools;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

/**
 * Fragment for discovering and pairing with Bluetooth devices.
 * Handles both direct (bonded/strong signal) and indirect devices separately.
 */
public class PairingFragment extends Fragment {
    // Constants
    public static final int CONNECTION_TIMEOUT = 5000; // Timeout for connection attempts in ms
    public static final int BLE_SIGNAL_THRESHOLD = -70; // RSSI threshold in dBm for considering a device as direct

    // UI Components
    private RequestDialog connectionRequestDialog;
    private RequestDialog connectionConfirmDialog;
    private ConstraintLayout constraintLayout;
    private ListView listViewDirectDevices;
    private ListView listViewIndirectDevices;
    private TextView discoveryDescription;
    private TextView noDevices;
    private TextView noPermissions;
    private TextView noBluetoothLe;
    private TextView directDevicesTitle;
    private TextView indirectDevicesTitle;
    private ButtonSearch buttonSearch;
    private ProgressBar loading;

    // Data
    private Peer confirmConnectionPeer;
    private Peer connectingPeer;
    @Nullable private PeerListAdapter listViewDirect;
    @Nullable private PeerListAdapter listViewIndirect;
    private Timer connectionTimer;
    private final Object lock = new Object();
    private HashMap<String, Integer> deviceRssiValues = new HashMap<>(); // Stores RSSI values for devices

    // State Management
    protected Global global;
    protected MainActivity activity;
    private static final float LOADING_SIZE_DP = 24;
    protected boolean isLoadingVisible = false;
    private boolean appearSearchButton = false;
    protected boolean isLoadingAnimating;
    private ArrayList<CustomAnimator.EndListener> listeners = new ArrayList<>();

    // Callbacks
    private MainActivity.Callback communicatorCallback;
    private CustomAnimator animator = new CustomAnimator();

    public PairingFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initializeCommunicatorCallback();
    }

    /**
     * Initializes the communicator callback with all necessary event handlers
     */
    private void initializeCommunicatorCallback() {
        communicatorCallback = new MainActivity.Callback() {
            @Override
            public void onSearchStarted() {
                buttonSearch.setSearching(true, animator);
            }

            @Override
            public void onSearchStopped() {
                buttonSearch.setSearching(false, animator);
            }

            @Override
            public void onConnectionRequest(final Peer peer) {
                handleConnectionRequest(peer);
            }

            @Override
            public void onConnectionSuccess(Peer peer, int source) {
                handleConnectionSuccess(peer);
            }

            @Override
            public void onConnectionFailed(Peer peer, int errorCode) {
                handleConnectionFailure(peer, errorCode);
            }

            @Override
            public void onPeerFound(Peer peer) {
                processFoundPeer(peer);
            }

            @Override
            public void onPeerUpdated(Peer peer, Peer newPeer) {
                processPeerUpdate(peer, newPeer);
            }

            @Override
            public void onPeerLost(Peer peer) {
                removeLostPeer(peer);
            }

            @Override
            public void onRssiUpdated(Peer peer, int rssi) {
                updateDeviceRssi(peer, rssi);
            }

            @Override
            public void onBluetoothLeNotSupported() {
                showBluetoothLeNotSupported();
            }

            @Override
            public void onMissingSearchPermission() {
                handleMissingPermission();
            }

            @Override
            public void onSearchPermissionGranted() {
                handlePermissionGranted();
            }
        };
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_pairing, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initializeViews(view);
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        activity = (MainActivity) requireActivity();
        global = (Global) activity.getApplication();
        setupToolbar();
        applyWindowInsets();
        initializePeerLists();
        setupDeviceListClickListeners();
    }

    @Override
    public void onStart() {
        super.onStart();
        activateInputs();
        disappearLoading(true, null);
        if (!Tools.hasPermissions(activity, MainActivity.REQUIRED_PERMISSIONS)) {
            startSearch();
        }
        setupSearchButtonListener();
    }

    @Override
    public void onResume() {
        super.onResume();
        clearFoundPeers();
        activity.addCallback(communicatorCallback);
        if (Tools.hasPermissions(activity, MainActivity.REQUIRED_PERMISSIONS)) {
            startSearch();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        activity.removeCallback(communicatorCallback);
        stopSearch();
        if (connectingPeer != null) {
            activity.disconnect(connectingPeer);
            connectingPeer = null;
        }
    }

    /**
     * Initializes all view components
     * @param view The fragment's root view
     */
    private void initializeViews(View view) {
        constraintLayout = view.findViewById(R.id.container);
        listViewDirectDevices = view.findViewById(R.id.list_direct_devices);
        listViewIndirectDevices = view.findViewById(R.id.list_indirect_devices);
        discoveryDescription = view.findViewById(R.id.discoveryDescription);
        noDevices = view.findViewById(R.id.noDevices);
        noPermissions = view.findViewById(R.id.noPermission);
        noBluetoothLe = view.findViewById(R.id.noBluetoothLe);
        buttonSearch = view.findViewById(R.id.searchButton);
        loading = view.findViewById(R.id.progressBar2);
        directDevicesTitle = view.findViewById(R.id.direct_devices_title);
        indirectDevicesTitle = view.findViewById(R.id.indirect_devices_title);
    }

    /**
     * Sets up the toolbar for the fragment
     */
    private void setupToolbar() {
        Toolbar toolbar = activity.findViewById(R.id.toolbarPairing);
        activity.setActionBar(toolbar);
    }

    /**
     * Applies window insets to properly handle system UI
     */
    private void applyWindowInsets() {
        WindowInsets windowInsets = activity.getFragmentContainer().getRootWindowInsets();
        if (windowInsets != null) {
            constraintLayout.dispatchApplyWindowInsets(windowInsets.replaceSystemWindowInsets(
                    windowInsets.getSystemWindowInsetLeft(),
                    windowInsets.getSystemWindowInsetTop(),
                    windowInsets.getSystemWindowInsetRight(),
                    0));
        }
    }

    /**
     * Initializes the peer lists for direct and indirect devices
     */
    private void initializePeerLists() {
        final PeerListAdapter.Callback callback = new PeerListAdapter.Callback() {
            @Override
            public void onFirstItemAdded() {
                updateUIState();
            }

            @Override
            public void onLastItemRemoved() {
                updateUIState();
            }

            @Override
            public void onClickNotAllowed(boolean showToast) {
                Toast.makeText(activity, "Cannot interact with devices during connection", Toast.LENGTH_SHORT).show();
            }
        };

        listViewDirect = new PeerListAdapter(activity, new ArrayList<Peer>(), callback);
        listViewIndirect = new PeerListAdapter(activity, new ArrayList<Peer>(), callback);

        listViewDirectDevices.setAdapter(listViewDirect);
        listViewIndirectDevices.setAdapter(listViewIndirect);
    }

    /**
     * Sets up click listeners for both device lists
     */
    private void setupDeviceListClickListeners() {
        listViewDirectDevices.setOnItemClickListener(createDeviceClickListener());
        listViewIndirectDevices.setOnItemClickListener(createDeviceClickListener());
    }

    /**
     * Creates a standardized click listener for device list items
     * @return Configured OnItemClickListener
     */
    private AdapterView.OnItemClickListener createDeviceClickListener() {
        return new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                synchronized (lock) {
                    PeerListAdapter adapter = (PeerListAdapter) adapterView.getAdapter();
                    if (adapter != null && adapter.isClickable()) {
                        Peer item = adapter.get(i);
                        connect(item);
                    } else if (adapter != null) {
                        adapter.getCallback().onClickNotAllowed(adapter.getShowToast());
                    }
                }
            }
        };
    }

    /**
     * Sets up the search button click listener
     */
    private void setupSearchButtonListener() {
        buttonSearch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (activity.isSearching()) {
                    activity.stopSearch(false);
                    clearFoundPeers();
                } else {
                    startSearch();
                }
            }
        });
    }

    /**
     * Handles a connection request from a peer
     * @param peer The peer requesting connection
     */
    private void handleConnectionRequest(final Peer peer) {
        if (peer != null) {
            String time = DateFormat.getDateTimeInstance().format(new Date());
            connectionRequestDialog = new RequestDialog(activity,
                    "Accept connection request from " + peer.getName() + " ?",
                    15000,
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            activity.acceptConnection(peer);
                        }
                    },
                    new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            activity.rejectConnection(peer);
                        }
                    });
            connectionRequestDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
                @Override
                public void onCancel(DialogInterface dialog) {
                    connectionRequestDialog = null;
                }
            });
            connectionRequestDialog.show();
        }
    }

    /**
     * Handles successful connection to a peer
     * @param peer The connected peer
     */
    private void handleConnectionSuccess(Peer peer) {
        connectingPeer = null;
        resetConnectionTimer();
        activity.setFragment(MainActivity.CONVERSATION_FRAGMENT);
    }

    /**
     * Handles connection failure
     * @param peer The peer that failed to connect
     * @param errorCode The error code for the failure
     */
    private void handleConnectionFailure(Peer peer, int errorCode) {
        if (connectingPeer != null) {
            if (connectionTimer != null && !connectionTimer.isFinished() && errorCode != BluetoothCommunicator.CONNECTION_REJECTED) {
                activity.connect(peer);
            } else {
                clearFoundPeers();
                startSearch();
                activateInputs();
                disappearLoading(true, null);
                connectingPeer = null;
                if (errorCode == BluetoothCommunicator.CONNECTION_REJECTED) {
                    Toast.makeText(activity, peer.getName() + " refused the connection request", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(activity, "Connection error", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * Processes a newly found peer and categorizes it as direct or indirect
     * @param peer The found peer
     */
    private void processFoundPeer(Peer peer) {
        synchronized (lock) {
            BluetoothAdapter bluetoothAdapter = global.getBluetoothCommunicator().getBluetoothAdapter();
            boolean isDirect = isDirectDevice(peer, bluetoothAdapter);

            // Remove the peer from both lists first to ensure it's only in one list
            if (listViewDirect != null) {
                listViewDirect.remove(peer);
            }
            if (listViewIndirect != null) {
                listViewIndirect.remove(peer);
            }

            // Add to the appropriate list based on RSSI or bonded status
            if (isDirect) {
                handleDirectDevice(peer, bluetoothAdapter);
            } else {
                handleIndirectDevice(peer, bluetoothAdapter);
            }
            updateUIState();
        }
    }

    /**
     * Determines if a device should be considered direct
     * @param peer The peer to check
     * @param bluetoothAdapter The Bluetooth adapter
     * @return true if the device is direct (bonded or strong signal), false otherwise
     */
    private boolean isDirectDevice(Peer peer, BluetoothAdapter bluetoothAdapter) {
        // Bonded devices are always direct
        if (peer.isBonded(bluetoothAdapter)) {
            return true;
        }

        // Check RSSI if available
        if (deviceRssiValues.containsKey(peer.getUniqueName())) {
            int rssi = deviceRssiValues.get(peer.getUniqueName());
            // Use RSSI threshold to decide direct or indirect connection
            return rssi >= BLE_SIGNAL_THRESHOLD; // Ensure this threshold is appropriate for your use case
        }

        // Default to indirect if no RSSI data
        return false;
    }

    /**
     * Processes a peer update
     * @param peer The original peer
     * @param newPeer The updated peer
     */
    private void processPeerUpdate(Peer peer, Peer newPeer) {
        processFoundPeer(newPeer);
    }

    /**
     * Removes a lost peer from both lists
     */
    private void removeLostPeer(Peer peer) {
        synchronized (lock) {
            if (listViewDirect != null) {
                listViewDirect.remove(peer);
            }
            if (listViewIndirect != null) {
                listViewIndirect.remove(peer);
            }
            if (peer.equals(getConfirmConnectionPeer())) {
                RequestDialog requestDialog = getConnectionConfirmDialog();
                if (requestDialog != null) {
                    requestDialog.cancel();
                }
            }
            updateUIState();
        }
    }

    /**
     * Updates the RSSI value for a device
     * @param peer The peer to update
     * @param rssi The new RSSI value
     */
    private void updateDeviceRssi(Peer peer, int rssi) {
        deviceRssiValues.put(peer.getUniqueName(), rssi);
        processFoundPeer(peer); // Reprocess the peer to update its category
    }

    /**
     * Shows the Bluetooth LE not supported UI
     */
    private void showBluetoothLeNotSupported() {
        if (noBluetoothLe.getVisibility() != View.VISIBLE) {
            listViewDirectDevices.setVisibility(View.GONE);
            listViewIndirectDevices.setVisibility(View.GONE);
            directDevicesTitle.setVisibility(View.GONE);
            indirectDevicesTitle.setVisibility(View.GONE);
            noDevices.setVisibility(View.GONE);
            discoveryDescription.setVisibility(View.GONE);
            noBluetoothLe.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Handles missing permissions
     */
    private void handleMissingPermission() {
        clearFoundPeers();
        if (noPermissions.getVisibility() != View.VISIBLE) {
            listViewDirectDevices.setVisibility(View.GONE);
            listViewIndirectDevices.setVisibility(View.GONE);
            directDevicesTitle.setVisibility(View.GONE);
            indirectDevicesTitle.setVisibility(View.GONE);
            noDevices.setVisibility(View.GONE);
            discoveryDescription.setVisibility(View.GONE);
            noPermissions.setVisibility(View.VISIBLE);
        }
    }

    /**
     * Handles when permissions are granted
     */
    private void handlePermissionGranted() {
        if (noPermissions.getVisibility() == View.VISIBLE) {
            noPermissions.setVisibility(View.GONE);
            noDevices.setVisibility(View.VISIBLE);
            discoveryDescription.setVisibility(View.VISIBLE);
            initializePeerLists();
        } else {
            clearFoundPeers();
        }
        startSearch();
    }

    /**
     * Adds a direct device to the appropriate list
     * @param peer The peer to add
     * @param bluetoothAdapter The Bluetooth adapter
     */
    private void handleDirectDevice(Peer peer, BluetoothAdapter bluetoothAdapter) {
        if (listViewDirect != null) {
            int index = listViewDirect.indexOfPeer(peer.getUniqueName());
            if (index == -1) {
                listViewDirect.add(peer);
            } else {
                listViewDirect.set(index, peer);
            }
        }
    }

    /**
     * Adds an indirect device to the appropriate list
     * @param peer The peer to add
     * @param bluetoothAdapter The Bluetooth adapter
     */
    private void handleIndirectDevice(Peer peer, BluetoothAdapter bluetoothAdapter) {
        if (listViewIndirect != null) {
            int index = listViewIndirect.indexOfPeer(peer.getUniqueName());
            if (index == -1) {
                listViewIndirect.add(peer);
            } else {
                listViewIndirect.set(index, peer);
            }
        }
    }

    /**
     * Updates the UI state based on current device lists
     */
    private void updateUIState() {
        boolean hasDirectDevices = listViewDirect != null && !listViewDirect.isEmpty();
        boolean hasIndirectDevices = listViewIndirect != null && !listViewIndirect.isEmpty();

        if (hasDirectDevices || hasIndirectDevices) {
            discoveryDescription.setVisibility(View.GONE);
            noDevices.setVisibility(View.GONE);

            if (hasDirectDevices) {
                listViewDirectDevices.setVisibility(View.VISIBLE);
                directDevicesTitle.setVisibility(View.VISIBLE);
            } else {
                listViewDirectDevices.setVisibility(View.GONE);
                directDevicesTitle.setVisibility(View.GONE);
            }

            if (hasIndirectDevices) {
                listViewIndirectDevices.setVisibility(View.VISIBLE);
                indirectDevicesTitle.setVisibility(View.VISIBLE);
            } else {
                listViewIndirectDevices.setVisibility(View.GONE);
                indirectDevicesTitle.setVisibility(View.GONE);
            }
        } else {
            listViewDirectDevices.setVisibility(View.GONE);
            listViewIndirectDevices.setVisibility(View.GONE);
            directDevicesTitle.setVisibility(View.GONE);
            indirectDevicesTitle.setVisibility(View.GONE);

            if (noPermissions.getVisibility() != View.VISIBLE && noBluetoothLe.getVisibility() != View.VISIBLE) {
                discoveryDescription.setVisibility(View.VISIBLE);
                noDevices.setVisibility(View.VISIBLE);
            }
        }
    }

    /**
     * Initiates a connection to a peer
     * @param peer The peer to connect to
     */
    private void connect(final Peer peer) {
        connectingPeer = peer;
        confirmConnectionPeer = peer;
        connectionConfirmDialog = new RequestDialog(activity,
                "Are you sure to connect with " + peer.getName() + "?",
                new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        deactivateInputs();
                        appearLoading(null);
                        activity.connect(peer);
                        startConnectionTimer();
                    }
                },
                null);
        connectionConfirmDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                confirmConnectionPeer = null;
                connectionConfirmDialog = null;
            }
        });
        connectionConfirmDialog.show();
    }

    /**
     * Starts the device search
     */
    protected void startSearch() {
        int result = activity.startSearch();
        if (result != BluetoothCommunicator.SUCCESS) {
            if (result == BluetoothCommunicator.BLUETOOTH_LE_NOT_SUPPORTED && noBluetoothLe.getVisibility() != View.VISIBLE) {
                showBluetoothLeNotSupported();
            } else if (result != MainActivity.NO_PERMISSIONS && result != BluetoothCommunicator.ALREADY_STARTED) {
                Toast.makeText(activity, "Error starting search", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * Stops the device search
     */
    private void stopSearch() {
        activity.stopSearch(connectingPeer == null);
    }

    /**
     * Activates user inputs
     */
    private void activateInputs() {
        setListViewClickable(true, true);
    }

    /**
     * Deactivates user inputs
     */
    private void deactivateInputs() {
        setListViewClickable(false, true);
    }

    /**
     * Gets the peer awaiting connection confirmation
     * @return The confirm connection peer
     */
    public Peer getConfirmConnectionPeer() {
        return confirmConnectionPeer;
    }

    /**
     * Gets the connection confirmation dialog
     * @return The connection confirm dialog
     */
    public RequestDialog getConnectionConfirmDialog() {
        return connectionConfirmDialog;
    }

    /**
     * Starts the connection timer
     */
    private void startConnectionTimer() {
        connectionTimer = new Timer(CONNECTION_TIMEOUT);
        connectionTimer.start();
    }

    /**
     * Resets the connection timer
     */
    private void resetConnectionTimer() {
        if (connectionTimer != null) {
            connectionTimer.cancel();
            connectionTimer = null;
        }
    }

    /**
     * Clears all found peers from both lists
     */
    public void clearFoundPeers() {
        if (listViewDirect != null) {
            listViewDirect.clear();
        }
        if (listViewIndirect != null) {
            listViewIndirect.clear();
        }
        updateUIState();
    }

    /**
     * Sets whether the list views are clickable
     * @param isClickable true to enable clicks, false to disable
     * @param showToast whether to show toast messages when clicks are not allowed
     */
    public void setListViewClickable(boolean isClickable, boolean showToast) {
        if (listViewDirect != null) {
            listViewDirect.setClickable(isClickable, showToast);
        }
        if (listViewIndirect != null) {
            listViewIndirect.setClickable(isClickable, showToast);
        }
    }

    /**
     * Makes the loading animation appear
     * @param responseListener Listener for animation completion
     */
    public void appearLoading(@Nullable CustomAnimator.EndListener responseListener) {
        if (responseListener != null) {
            listeners.add(responseListener);
        }
        isLoadingVisible = true;
        if (!isLoadingAnimating) {
            if (loading.getVisibility() != View.VISIBLE) {
                isLoadingAnimating = true;
                buttonSearch.setVisible(false, new CustomAnimator.EndListener() {
                    @Override
                    public void onAnimationEnd() {
                        int loadingSizePx = GuiTools.convertDpToPixels(activity, LOADING_SIZE_DP);
                        Animator animation = animator.createAnimatorSize(loading, 1, 1, loadingSizePx, loadingSizePx, getResources().getInteger(R.integer.durationShort));
                        animation.addListener(new Animator.AnimatorListener() {
                            @Override
                            public void onAnimationStart(Animator animation) {
                                loading.setVisibility(View.VISIBLE);
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                isLoadingAnimating = false;
                                if (!isLoadingVisible) {
                                    disappearLoading(appearSearchButton, null);
                                } else {
                                    notifyLoadingAnimationEnd();
                                }
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {
                            }

                            @Override
                            public void onAnimationRepeat(Animator animation) {
                            }
                        });
                        animation.start();
                    }
                });
            } else {
                notifyLoadingAnimationEnd();
            }
        }
    }

    /**
     * Makes the loading animation disappear
     * @param appearSearchButton whether to show the search button after hiding loading
     * @param responseListener Listener for animation completion
     */
    public void disappearLoading(final boolean appearSearchButton, @Nullable CustomAnimator.EndListener responseListener) {
        if (responseListener != null) {
            listeners.add(responseListener);
        }
        this.isLoadingVisible = false;
        this.appearSearchButton = appearSearchButton;
        if (!isLoadingAnimating) {
            if (loading.getVisibility() != View.GONE) {
                isLoadingAnimating = true;
                int loadingSizePx = GuiTools.convertDpToPixels(activity, LOADING_SIZE_DP);
                Animator animation = animator.createAnimatorSize(loading, loadingSizePx, loadingSizePx, 1, 1, getResources().getInteger(R.integer.durationShort));
                animation.addListener(new Animator.AnimatorListener() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                    }

                    @Override
                    public void onAnimationEnd(Animator animation) {
                        loading.setVisibility(View.GONE);
                        CustomAnimator.EndListener listener = new CustomAnimator.EndListener() {
                            @Override
                            public void onAnimationEnd() {
                                isLoadingAnimating = false;
                                if (isLoadingVisible) {
                                    appearLoading(null);
                                } else {
                                    notifyLoadingAnimationEnd();
                                }
                            }
                        };
                        if (appearSearchButton) {
                            buttonSearch.setVisible(true, listener);
                        } else {
                            listener.onAnimationEnd();
                        }
                    }

                    @Override
                    public void onAnimationCancel(Animator animation) {
                    }

                    @Override
                    public void onAnimationRepeat(Animator animation) {
                    }
                });
                animation.start();
            } else {
                notifyLoadingAnimationEnd();
            }
        }
    }

    /**
     * Notifies all listeners that loading animation has ended
     */
    private void notifyLoadingAnimationEnd() {
        while (listeners.size() > 0) {
            listeners.remove(0).onAnimationEnd();
        }
    }
}
