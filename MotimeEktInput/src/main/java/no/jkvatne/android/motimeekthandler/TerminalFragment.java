package no.jkvatne.android.motimeekthandler;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Arrays;

import static java.util.concurrent.TimeUnit.*;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {
    private static final char[] b64chars = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
            'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '0', '1', '2',
            '3', '4', '5', '6', '7', '8', '9', '-', '_'};

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    private SerialService service;
    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private int portNum, baudRate;
    private final BroadcastReceiver broadcastReceiver;
    private TextView receiveText;
    private TextView statusText;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private enum Connected {False, Pending, True}
    private Connected connected = Connected.False;
    private int prevNo;
    private CircularBuffer cBuf;
    private String ServerUrl = "<loaded from apikey.properties by gradle>";
    private boolean noWeb = true;
    private  boolean initialStart = true;
    private boolean statusOk;
    final Handler handler = new Handler();
    private long lastMessageMs;
    ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
    private PowerManager.WakeLock wakeLock;

    public AlertDialog myAlertDialog = null;
    public static void HandleBleString(String s) {
        // TODO: Handle BLE messages here
        Log.i("BLE", "Got BLE message: "+s);
    }

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,
                            false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    Log.i("ECB", "broadcastReceiver onReceive: Connecting to USB");
                    connect();
                }
            }
        };
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        assert getArguments() != null;
        portNum = getArguments().getInt("port");
        String deviceName = getArguments().getString("name");
        if ((deviceName!=null) && deviceName.equals("FT232R USB UART")) {
            baudRate = 115200;
        } else {
            baudRate = 9600;
        }
        Log.i("ECB","onCreate, portNum="+portNum+" deviceName="+deviceName);
        cBuf = new CircularBuffer(20480);
        byte[] buf = BuildConfig.SERVER_URL.getBytes();
        ServerUrl = new String(buf, StandardCharsets.UTF_8);
        lastMessageMs = 0;

        final int delay = 2000; // milliseconds
        handler.postDelayed(new Runnable() {
            public void run() {
                //Log.i("ECB","Running handler each 2 sec");
                handler.postDelayed(this, delay);
                long elapsedMs = android.os.SystemClock.elapsedRealtime();
                if (lastMessageMs!=0) {
                    if (elapsedMs>(lastMessageMs+600000)) {  // OBS SHould be 6000 for 6 sec timout
                        // Log.e("ECB",">>>>>>>>>>>>> Timeout, no data <<<<<<<<<<<<<<<<");
                        // lastMessageMs = 0;
                        // status((String) getText(R.string.connection_lost));
                        // disconnect();
                        // statusOk = false;
                    }
                }
            }
        }, delay);
    }

    @Override
    public void onDestroy() {
        Log.i("ECB", "TerminalFragment.onDestroy()");
        if (connected != Connected.False)
            disconnect();
        requireActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onStart() {
        Log.i("ECB", "TerminalFragment.onStart()");
        super.onStart();
        if (service != null)
            service.attach(this);
        else
            requireActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
        ContextCompat.registerReceiver(requireActivity(), broadcastReceiver, new IntentFilter(Constants.INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_NOT_EXPORTED);

        AlertDialog.Builder builder = new AlertDialog.Builder(requireActivity());
        builder.setTitle(getString(R.string.connection_lost));
        builder.setIcon(android.R.drawable.ic_dialog_alert);
        builder.setPositiveButton(android.R.string.ok, (dialog, which) -> { });
        myAlertDialog = builder.create();
    }

    @Override
    public void onStop() {
        Log.i("ECB", "TerminalFragment.onStop()");
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        Log.i("ECB", "TerminalFragment.onAttach()");
        requireActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        Log.i("ECB", "TerminalFragment.onDetach()");
        try {requireActivity().unbindService(this);} catch (Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        Log.i("ECB", "TerminalFragment.onResume()");
        if(initialStart && service != null) {
            initialStart = false;
            Log.i("ECB", "Connecting to USB");
            requireActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onPause() {
        Log.i("ECB", "TerminalFragment.onPause()");
        super.onPause();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        Log.i("ECB", "TerminalFragment.onServiceConnected()");
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            Log.i("ECB", "Connecting to USB");
            requireActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.i("ECB", "TerminalFragment.onServiceDisconnected()");
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        Log.i("ECB", "TerminalFragment.onCreateView()");
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);
        statusText = view.findViewById(R.id.statusTextView);
        // TextView performance decreases with number of spans
        // set as default color to reduce number of spans
        receiveText.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        if (!ServerUrl.startsWith("http")) {
            receiveText.setText(R.string.url_error);
            noWeb = true;
        } else {
            noWeb = false;
            try { MILLISECONDS.sleep(100);} catch (Exception ignored) {}
            verifyServer();
        }
        View spoolBtn = view.findViewById(R.id.spool_btn);
        spoolBtn.setOnClickListener(v -> onSpool());
        View stsBtn = view.findViewById(R.id.status_btn);
        stsBtn.setOnClickListener(v -> getStatus());
        View clearBtn = view.findViewById(R.id.clear_btn);
        clearBtn.setOnClickListener(v -> onClear());
        return view;
    }

    public void verifyServer() {
        // Request a string response from the provided URL.
        /* TODO
        StringRequest stringRequest = new StringRequest(
                Request.Method.GET,
                ServerUrl + "ack=1",
                response -> {
                    try {
                        receiveText.append(getText(R.string.server_ok));
                        receiveText.append(" \n");
                    } catch (Exception e) {
                        receiveText.append("Error checking for internet connection\n");   // "Exception " + response);
                    }
                },
                error -> receiveText.append("Internet error\n")  //+ error + "\n")
        );
        queue.add(stringRequest);
        // Update cached date
        LocalDate d = LocalDate.now();
        year = d.getYear();
        month = d.getMonthValue();
        day = d.getDayOfMonth();
        */
    }
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            Log.i("ECB", "TerminalFragment option clear");
            receiveText.setText("");
            cBuf.clear();
            return true;
        }
        return false;
    }

    /*
     * Serial + UI
     */
    public void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values()) {
            device = v;
        }
        if (device == null) {
            Log.e("ECB", "TerminalFragment.connect() failed");
            status(getString(R.string.connection_failed));
            return;
        }
        Log.i("ECB", "TerminalFragment.connect()");
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver.getPorts().size() < portNum) {
            Log.e("ECB", "TerminalFragment.connect() failed, not enouth ports");
            status(getString(R.string.connection_failed));
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0,
                    new Intent(INTENT_ACTION_GRANT_USB), PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (service.notificationsNotEnabled() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 0);
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice())) {
                Log.e("ECB", "TerminalFragment, no permission for USB");
                status(getString(R.string.perm_missing));
            }  else {
                Log.e("ECB", "TerminalFragment.connect() failed usb");
                status(getString(R.string.connection_failed));
            }
            return;
        }

        connected = Connected.Pending;
        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status(getString(R.string.unsupported_parameters));
            }
            SerialSocket socket = new SerialSocket(requireActivity().getApplicationContext(), usbConnection, usbSerialPort);
            service.connect(socket);
            // usb connect is not asynchronous. connect-success and connect-error are returned immediately from socket.connect
            // for consistency to bluetooth/bluetooth-LE app use same SerialListener and SerialService classes
            onSerialConnect();
            status(getString(R.string.connected_ok));
            receiveText.append(getString(R.string.connected_ok));
            handler.postDelayed(this::getStatus, 200);
        } catch (Exception e) {
            status(getString(R.string.connection_failed) + e.getMessage());
            Log.e("ECB", "TerminalFragment.connect() failed open");
            disconnect();
        }
    }

    private void disconnect() {
        Log.i("ECB", "TerminalFragment.disconnect()");
        connected = Connected.False;
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        if (usbSerialPort!=null) {
            try {
                usbSerialPort.close();
            } catch (Exception ignored){
            }
        }
        service.disconnect();
        usbSerialPort = null;
    }

    private void SendBytes(byte[] data) {
        if (connected != Connected.True) {
            Log.e("ECB", "send() not connected");
            Toast.makeText(getActivity(), getString(R.string.no_contact), Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Log.i("ECB", "SendBytes");
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
            Log.i("ECB", "SendBytes done");
        } catch (Exception e) {
            status(e.getMessage());
        }
    }

    private void SendString(String str) {
        str = str + "\r\n";
        SendBytes(str.getBytes(StandardCharsets.UTF_8));
    }


    public void onClear() {
        AlertDialog.Builder adb = new AlertDialog.Builder(requireActivity());
        adb.setTitle(getString(R.string.do_clear));
        adb.setIcon(android.R.drawable.ic_dialog_alert);
        adb.setPositiveButton(android.R.string.ok, (dialog, which) -> ClearAll());
        adb.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
            // Do nothing
        });
        adb.show();
    }

    public void onSpool() {
        if (noWeb) {
            Log.e("ECB", "onSpool() - no web connection");
            return;
        }
        AlertDialog.Builder adb = new AlertDialog.Builder(requireActivity());
        adb.setTitle(getString(R.string.do_download));
        adb.setIcon(android.R.drawable.ic_dialog_alert);
        adb.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            // Spool last race
            if (service.mtrOk) {
                Log.i("ECB", "SpoolPackage called on MTR. Send /SBnnnn for n="+prevNo);
                receiveText.append(getString(R.string.spool_from) + prevNo + "\n");
                // Send /SBnnnn
                byte[] data = {0x2F, 0x53, 0x42, (byte) (prevNo & 0xFF), (byte) ((prevNo >> 8) & 0xFF),
                        (byte) ((prevNo >> 16) & 0xFF), (byte) ((prevNo >> 24) & 0xFF)};
                SendBytes(data);
            } else if (service.eScanOk) {
                Log.i("ECB", "SpoolPackage on old eScan, sending /QD");
                receiveText.append(getString(R.string.spool_all));
                //  Send Spool all = /QD<cr><lf>
                byte[] data = {0x2F, 0x51, 0x44, 0x0D, 0x0A};
                SendBytes(data);
            } else if (service.eScan2Ok) {
                Log.i("ECB", "Spool all todays records from eScan2, sending /QM");
                receiveText.append(getString(R.string.spool_today));
                //  Send spool today /QM<cr><lf>
                byte[] data = {0x2F, 0x51, 0x4D, 0x0D, 0x0A};
                SendBytes(data);
            } else {
                receiveText.append(getString(R.string.no_contact));
            }
        });
        adb.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
           // Do nothing
        });
        adb.show();
    }

    public void getStatus() {
        Log.i("ECB", "getStatus()");
        SendString("/ST");
    }

    void ClearAll() {
        // Set clock  /SCHH:MM:SS
        LocalDateTime ldt = LocalDateTime.now();
        String str = ldt.format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        byte[] b = str.getBytes();
        byte[] data2 = {0x2F, 0x53, 0x43, b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], 0x0D, 0x0A };
        SendBytes(data2);
        receiveText.append(getString(R.string.clock_is_updated));
        try { MILLISECONDS.sleep(100);} catch (Exception ignored) {}

        // Set date /SD
        str = ldt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        b = str.getBytes();
        byte[] data3 = {0x2F, 0x53, 0x44, b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7], b[8], b[9], 0x0D, 0x0A };
        SendBytes(data3);
        receiveText.append(getString(R.string.date_is_updated));
        try { MILLISECONDS.sleep(100);} catch (Exception ignored) {}

        // Send /CL
        byte[] data = {0x2F, 0x43, 0x4C, 0x0D, 0x0A};
        receiveText.append(getString(R.string.all_deleted));
        SendBytes(data);
    }

    void status(String str) {
        statusText.setText(str);
    }

    /*
     * starting with Android 14, notifications are not shown in notification bar by default when App is in background
     */

    private void showNotificationSettings() {
        Intent intent = new Intent();
        intent.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
        intent.putExtra("android.provider.extra.APP_PACKAGE", requireActivity().getPackageName());
        startActivity(intent);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(Arrays.equals(permissions, new String[]{Manifest.permission.POST_NOTIFICATIONS}) && service.notificationsNotEnabled()) {
            showNotificationSettings();
        }
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        Log.i("ECB", "onSerialConnect()");
        status((String) getText(R.string.usb_connected));
        ((Activity) requireContext()).getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        Log.i("ECB", "onSerialConnect()");
        status(getText(R.string.connection_failed) + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        ArrayDeque<byte[]> dataStrings = new ArrayDeque<>();
        dataStrings.add(data);
        try {
            //receive(dataStrings);
        } catch (Exception ignored) {

        }
    }

    @Override
    public void onSerialProgress(String s) {
        try {
            receiveText.append(s);   // Works ok
        } catch (Exception e) {
            Log.e("ECB", "onSerialProgress() exception "+e);
        }
    }

    @Override
    public void onSerialStatus(String s) {
        try {
            status(s);
        } catch (Exception e) {
            Log.e("ECB", "onSerialStatus exception "+e);
        }
    }

    @Override
    public void onSerialIoError(Exception e) {
        Log.e("ECB", "onSerialIoError()");
        status((String) getText(R.string.connection_lost));
        myAlertDialog.show();
        ((Activity) requireContext()).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        disconnect();
    }

// End SerialListener

}
