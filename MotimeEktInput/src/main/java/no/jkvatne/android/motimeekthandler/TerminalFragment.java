package no.jkvatne.android.motimeekthandler;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.TimeUnit;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.android.volley.toolbox.Volley;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {
    private static final char[]b64chars = {'A','B','C','D','E','F','G','H','I','J','K','L','M',
            'N','O','P','Q','R','S','T','U','V','W','X','Y','Z','a','b','c','d','e','f','g','h',
            'i','j','k','l','m','n','o','p','q','r','s','t','u','v','w','x','y','z','0','1','2',
            '3','4','5','6','7','8','9','-','_'};

    private enum UsbPermission {Unknown, Requested, Granted, Denied}

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 200;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    //private ControlLines controlLines;
    private int CurrentSize;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;
    private RequestQueue mRequestQueue;
    private StringRequest mStringRequest;
    private RequestQueue queue;
    private int prevNo;
    private CircularBuffer cbuf;

    private String ServerUrl  = "https://tid.nook.no/tid.php?a=";


    public static String getTagValue(String xml, String tagName){
        return xml.split("<"+tagName+">")[1].split("</"+tagName+">")[0];
    }

    public void getUrlContent(String url) {
        // Request a string response from the provided URL.
        StringRequest stringRequest = new StringRequest(
            Request.Method.GET,
            url,
            new Response.Listener<String>() {
                @Override
                public void onResponse(String response) {
                    //receiveText.append(response+"\n");
                    try {
                        receiveText.append("Klasse:"+getTagValue(response, "class")+" ");
                        int failed = Integer.parseInt(getTagValue(response,"failed"));
                        if (failed==0) {
                            receiveText.append("Godkjent\n");
                        } else if (failed>0) {
                            receiveText.append("Feil post "+failed+"\n");
                        } else {
                            receiveText.append("\n");
                        }
                        receiveText.append("Navn:"+getTagValue(response, "name")+"\n");
                    } catch (Exception e) {
                        receiveText.append("Exception "+e);
                    }
                    // Blank line between reports
                    receiveText.append("\n");
                }
            },
            new Response.ErrorListener() {
                @Override
                public void onErrorResponse(VolleyError error) {
                    receiveText.append("Errror\n");
                }
            }
        );
        queue.add(stringRequest);
    }

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED,
                            false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        assert getArguments() != null;
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = 9600; // getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
        queue = Volley.newRequestQueue(this.requireActivity());
        cbuf = new CircularBuffer(2048);
        byte[] pwd = BuildConfig.SERVER_URL.getBytes();
        ServerUrl = pwd.toString();
    }

    @Override
    public void onResume() {
        super.onResume();
        requireActivity().registerReceiver(broadcastReceiver,
                new IntentFilter(INTENT_ACTION_GRANT_USB));
        //status("resume");
        if (!connected) {
            if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
                mainLooper.post(this::connect);
        }
    }

    @Override
    public void onPause() {
        if (connected) {
            status("pause");
            //disconnect();
        }
        requireActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    public void onSpool() {
        AlertDialog.Builder adb = new AlertDialog.Builder(getActivity());
        //adb.setView(alertDialogView);
        adb.setTitle("Vil du laste ned de siste avlesningene?");
        adb.setIcon(android.R.drawable.ic_dialog_alert);
        adb.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // Spool last race
                SpoolPackage(prevNo);
            }
        });
        adb.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int which) {
                // Do nothing
            }
        });
        adb.show();
    }

    public void getStatus() {
        receiveText.append("On click handler, get status\n");
        send("/ST");
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView
        // performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as
        // default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        View spoolBtn = view.findViewById(R.id.spool_btn);
        spoolBtn.setOnClickListener(v -> onSpool());
        View stsBtn = view.findViewById(R.id.status_btn);
        stsBtn.setOnClickListener(v -> getStatus());
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            cbuf.clear();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            try {
                receive(data);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status(e.getMessage());
            disconnect();
        });
    }

    /*
     * Serial + UI
     */
    private void connect() {
        int id = 0;
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values()) {
            deviceId = v.getDeviceId();
            device = v;
        }
        if (device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if (driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                    PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0,
                    new Intent(INTENT_ACTION_GRANT_USB), flags);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status("unsupport setparameters");
            }
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status("connected");
            connected = true;
            //receiveText.append("Just connected, query status\n");
            send("/ST");
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        if (usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    private void sendbytes(byte[] data) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void send(String str) {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] data = (str).getBytes();
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if (!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost 2" + e.getMessage());
            disconnect();
        } catch (InterruptedException e) {
            status("connection lost 3" + e.getMessage());
            disconnect();
        }
    }

    @SuppressLint("DefaultLocale")
    private void receive(byte[] data) throws InterruptedException {
        for (int i=0; i<data.length; i++) {
            cbuf.put(data[i]);
        }
        //receiveText.append(String.format("Got  %d bytes, cbuf=%d\n", data.length,data.length()));

        // Skip to FFFF and check length
        if (cbuf.foundMessage()) {
            byte[] buf = new byte[256];
            int CurrentSize;
            CurrentSize = (int)cbuf.peek(4)&0xFF;
            for (int i = 0; i< CurrentSize+4; i++) {
                buf[i] = cbuf.get();
            }
            //receiveText.append("\nHave just extracted package\n");
            //receiveText.append(String.format("nextPut=%d  nextGet=%d\n", cbuf.nextPut,
            // cbuf.nextGet));
            if (CurrentSize == 230) {
                char[] compressedData = new char[256];
                int totalTime = CompressTag(buf, compressedData);

                receiveText.append(String.format("%02d-%02d-%02d %02d:%02d:%02d ",
                        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13]));

                int ektNo = ((int) buf[20] & 0xFF) + (((int) buf[21] & 0xFF) << 8) + (((int) buf[22] & 0xFF) << 16);
                receiveText.append(String.format("Nr %d %d:%02d\n", ektNo,
                        totalTime / 60, totalTime % 60));
                String url =  ServerUrl + String.valueOf(compressedData);
                getUrlContent(url);
            } else if (CurrentSize == 55) {
                receiveText.append(String.format("Status 20%02d-%02d-%02d %02d:%02d:%02d\n",
                        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13]));
                int recNo = ((int) buf[17] & 0xFF) + (((int) buf[18] & 0xFF) << 8)
                            + (((int) buf[19] & 0xFF) << 16)+(((int) buf[20] & 0xFF) << 24);
                int oldestNo = ((int) buf[21] & 0xFF) + (((int) buf[22] & 0xFF) << 8)
                        + (((int) buf[23] & 0xFF) << 16)+(((int) buf[24] & 0xFF) << 24);
                prevNo = ((int) buf[25] & 0xFF) + (((int) buf[26] & 0xFF) << 8)
                        + (((int) buf[27] & 0xFF) << 16)+(((int) buf[28] & 0xFF) << 24);
                receiveText.append(String.format("Siste løp har %d brikker\n", recNo-prevNo+1));

                // Get current time and compare
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    LocalDateTime t = LocalDateTime.now();
                    int y = t.getYear() % 100;
                    int mo = t.getMonthValue();
                    int d = t.getDayOfMonth();
                    int h = t.getHour();
                    int mi = t.getMinute();
                    int s = t.getSecond();
                    int diff = s-buf[13];
                    if (diff<0) {
                        diff += 60;
                    }
                    if (y!=buf[8] || mo!=buf[9] || d!=buf[10] || h!=buf[11] || mi!=buf[12] || diff>2) {
                        receiveText.append("Oppdaterer MTR klokke\n");
                        send("/SC"+(char)y+(char)mo+(char)d+(char)h+(char)mi+(char)s);
                        TimeUnit.MILLISECONDS.sleep(100);
                    } else {
                        receiveText.append("MTR klokke OK\n\n");
                    }
                }
                // Re-read last package
                //receiveText.append("Har fått sts, henter nr " + recNo + "\n");
                //GetPackage(recNo);

            } else if (CurrentSize > 0) {
                receiveText.append(String.format("nextPut=%d  nextGet=%d\n", cbuf.nextPut,
                        cbuf.nextGet));
                receiveText.append("Ukjent pakke med " + CurrentSize + " bytes\n");
                CurrentSize = 0;
            }
        }
    }

    // GetEkt will read one record from the MTR3/4.
    void GetPackage(int no) {
        // Sending /GBxxxx with binary x
        byte[] data = {0x2F, 0x47, 0x42, (byte)(no&0xFF),(byte)((no>>8)&0xFF),(byte)((no>>16)&0xFF),
                (byte)((no>>24)&0xFF) };
        sendbytes(data);
    }

    // SpoolEkt will read all recoerds starting at given number from the MTR3/4.
    void SpoolPackage(int no) {
        receiveText.append("Henter pakker fra nr. " + no + "\n");
        byte[] data = {0x2F, 0x53, 0x42, (byte)(no&0xFF),(byte)((no>>8)&0xFF),
                (byte)((no>>16)&0xFF), (byte)((no>>24)&0xFF) };
        sendbytes(data);
    }

    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0,
                spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    private char b64(int x) {
        if (x<0) {
            x = 256+x;
        }
        x = x & 0x3F;
        return (char)b64chars[x];
    }

    private int bb(byte[] brikke_buffer, int i) {
        // Convert byte to an integer between 0 and 255
        return (int)brikke_buffer[i]&0xff;
    }

    int CompressTag(byte[] brikke_buffer, char[] compressedData) {
        int pos = 0;
        int i = 0;
        int prev_tid = 0;
        int totalTime = 0;
        //byte[] compressedData = new byte[256];
        // Protokollnummer er første byte
        compressedData[pos++] = b64(49);
        // Batterispenning, 1 tegn (x=0..63 = spenning/0.1 = 0..=6.3V
        int bv = (int)63;
        compressedData[pos++] = b64(bv);
        // Avlest tid, 5 tegn, år modulo 16, |YYYYMM|MMDDDD|DHHHHH|MMMMMM|SSSSSS
        // Year is years after 1900 mod 16, so 2022 is 10 (0xA). Add 2012
        // Month is 0-11
        compressedData[pos++] =
                b64((((bb(brikke_buffer,8) + 1900) & 0x0F) << 2) + ((bb(brikke_buffer,9) >> 2) & 3));
        compressedData[pos++] =
                b64((((bb(brikke_buffer,9)) & 3) << 4) + ((bb(brikke_buffer,10) >> 1) & 0x0F));
        compressedData[pos++] =
                b64(((bb(brikke_buffer,10) & 1) << 5) + (bb(brikke_buffer,11) & 31));
        compressedData[pos++] =
                b64(bb(brikke_buffer,12) & 0x3f);  // Minutes
        compressedData[pos++] =
                b64(bb(brikke_buffer,13) & 0x3f);  // Seconds
        // Antall poster (fylles inn senere)
        compressedData[pos++] =b64(0);
        // Brikkenummer
        //    3       2      1     0
        // |cccccc|ccbbbb|bbbbaa|aaaaaa|
        compressedData[pos++] =
                b64(bb(brikke_buffer,20) & 0x3F);
        compressedData[pos++] =
                b64(((bb(brikke_buffer,20) >> 6) & 0x03) | ((bb(brikke_buffer,21) & 0x0f) << 2));
        compressedData[pos++] =
                b64(((bb(brikke_buffer,21) >> 4) & 0x0f) | ((bb(brikke_buffer,22) & 0x03) << 4));
        compressedData[pos++] =
                b64(((bb(brikke_buffer,22) >> 2) & 0x3f));
        // Control codes
        for (i = 0; i < 50; i++) {
            int post = bb(brikke_buffer,3 * i + 26);
            int posttid = ((int) bb(brikke_buffer,27 + 3 * i) & 0xFF) + (((int) bb(brikke_buffer,28 + 3 * i) & 0xFF) << 8);
            int tid = posttid - prev_tid;
            prev_tid = posttid;
            if (i > 0 && post == 0) break;
            if (tid <= 511) {
                // |0ttttt|ttttpp|pppppp|
                compressedData[pos++] =b64((tid >> 4) & 0x1f);
                compressedData[pos++] =b64(((post >> 6) & 0x03) | ((tid & 0x0f) << 2));
                compressedData[pos++] =b64((post & 0x3F));
            } else {
                // |1ttttt|tttttt|ttttpp|pppppp|
                compressedData[pos++] =b64(32 | ((tid >> 10) & 0x1F));
                compressedData[pos++] =b64(((tid >> 4) & 0x3F));
                compressedData[pos++] =b64(((post >> 6) & 0x03) | ((tid & 0x0f) << 2));
                compressedData[pos++] =b64((post & 0x3F));
            }
            if (post < 250) {
                totalTime = posttid;
            }
        }

        // Number of controls is stored at offset 7
        compressedData[7] =b64(i);

        // Add 2 character checksum on data part
        int checksum = 0;
        for (int j = 0; j < pos; j++) {
            checksum += compressedData[j];
        }
        compressedData[pos++] =b64(checksum & 0x3F);
        compressedData[pos++] =b64((checksum >> 6) & 0x3F);
        compressedData[pos++] = 0; //String termination
        return totalTime;
    }
}

