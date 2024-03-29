package no.jkvatne.android.motimeekthandler;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
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
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import com.android.volley.Request;
import com.android.volley.RequestQueue;
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
    private int portNum, baudRate;
    private boolean withIoManager;
    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;
    private RequestQueue queue;
    private int prevNo;
    private CircularBuffer cBuf;
    private String ServerUrl  = "<loaded from apikey.properties by gradle>";
    private boolean noWeb = true;
    public static String getTagValue(String xml, String tagName){
        return xml.split("<"+tagName+">")[1].split("</"+tagName+">")[0];
    }

    public void getUrlContent(String url) {
        // Request a string response from the provided URL.
        StringRequest stringRequest = new StringRequest(
            Request.Method.GET,
            url,
                response -> {
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
                },
                error -> receiveText.append("Errror\n")
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

    public void verifyServer(){
        // Request a string response from the provided URL.
        StringRequest stringRequest = new StringRequest(
                Request.Method.GET,
                ServerUrl+"ack=1",
                response -> {
                    try {
                        receiveText.append(getText(R.string.server_ok));
                        receiveText.append(" \n\n");
                    } catch (Exception e) {
                        receiveText.append("Exception "+response);
                    }
                },
                error -> receiveText.append("Error response "+error+"\n")
        );
        queue.add(stringRequest);
}

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        //setRetainInstance(true);
        assert getArguments() != null;
        //deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = 9600; // getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
        queue = Volley.newRequestQueue(this.requireActivity());
        cBuf = new CircularBuffer(2048);
        byte[] buf = BuildConfig.SERVER_URL.getBytes();
        ServerUrl = new String(buf, StandardCharsets.UTF_8);
    }

    @Override
    public void onResume() {
        super.onResume();
        ContextCompat.registerReceiver(requireContext(), broadcastReceiver,
                new IntentFilter(INTENT_ACTION_GRANT_USB), ContextCompat.RECEIVER_EXPORTED);
        //status("resume");
        if (!connected) {
            if (usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
                mainLooper.post(this::connect);
        }
    }

    @Override
    public void onPause() {
        if (connected) {
            //status("pause");
            disconnect();
        }
        requireActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    public void onSpool() {
        if (noWeb) {
            return;
        }
        AlertDialog.Builder adb = new AlertDialog.Builder(requireActivity());
        adb.setTitle(getString(R.string.do_download));
        adb.setIcon(android.R.drawable.ic_dialog_alert);
        adb.setPositiveButton(android.R.string.ok, (dialog, which) -> {
            // Spool last race
            SpoolPackage(prevNo);
        });
        adb.setNegativeButton(android.R.string.cancel, (dialog, which) -> {
            // Do nothing
        });
        adb.show();
    }

    public void getStatus() {
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
        // set as default color to reduce number of spans
        receiveText.setTextColor(ContextCompat.getColor(requireContext(), R.color.colorRecieveText));
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        if (!ServerUrl.startsWith("http")) {
            receiveText.setText(R.string.url_error);
            noWeb = true;
        } else {
            noWeb = false;
            verifyServer();
        }
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
            cBuf.clear();
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
        if (noWeb) {
            return;
        }
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        for (UsbDevice v : usbManager.getDeviceList().values()) {
            //deviceId = v.getDeviceId();
            device = v;
        }
        if (device == null) {
            //status("connection failed: device not found");
            status(getString(R.string.connection_failed));
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if (driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if (driver == null) {
            //status("connection failed: no driver for device");
            status(getString(R.string.connection_failed));
            return;
        }
        if (driver.getPorts().size() < portNum) {
            //status("connection failed: not enough ports at device");
            status(getString(R.string.connection_failed));
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if (usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0,
                    new Intent(INTENT_ACTION_GRANT_USB), PendingIntent.FLAG_MUTABLE);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if (usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status(getString(R.string.perm_missing));
            else
                status(getString(R.string.connection_failed));
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            try {
                usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            } catch (UnsupportedOperationException e) {
                status(getString(R.string.unsupported_parameters));
            }
            if (withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                usbIoManager.start();
            }
            status(getString(R.string.usb_connected));
            connected = true;
            send("/ST");
        } catch (Exception e) {
            status(getString(R.string.connection_failed) + e.getMessage());
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

    @SuppressLint("DefaultLocale")
    private void receive(byte[] data) throws InterruptedException {
        for (byte datum : data) {
            cBuf.put(datum);
        }

        // Skip to FFFF and check length
        if (cBuf.foundMessage()) {
            byte[] buf = new byte[256];
            int CurrentSize;
            CurrentSize = (int) cBuf.peek(4)&0xFF;
            for (int i = 0; i< CurrentSize+4; i++) {
                buf[i] = cBuf.get();
            }
            if (CurrentSize == 230) {
                char[] compressedData = new char[256];
                int totalTime = CompressTag(buf, compressedData);

                receiveText.append(String.format("%02d-%02d-%02d %02d:%02d:%02d ",
                        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13]));

                int ektNo = ((int) buf[20] & 0xFF) + (((int) buf[21] & 0xFF) << 8) + (((int) buf[22] & 0xFF) << 16);
                receiveText.append(String.format("Nr %d %d:%02d\n", ektNo,
                        totalTime / 60, totalTime % 60));
                String url =  ServerUrl + "a=" + String.valueOf(compressedData);
                getUrlContent(url);
            } else if (CurrentSize == 55) {
                receiveText.append(String.format("Status 20%02d-%02d-%02d %02d:%02d:%02d\n",
                        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13]));
                int recNo = ((int) buf[17] & 0xFF) + (((int) buf[18] & 0xFF) << 8)
                            + (((int) buf[19] & 0xFF) << 16)+(((int) buf[20] & 0xFF) << 24);
                //int oldestNo = ((int) buf[21] & 0xFF) + (((int) buf[22] & 0xFF) << 8)
                //        + (((int) buf[23] & 0xFF) << 16)+(((int) buf[24] & 0xFF) << 24);
                prevNo = ((int) buf[25] & 0xFF) + (((int) buf[26] & 0xFF) << 8)
                        + (((int) buf[27] & 0xFF) << 16)+(((int) buf[28] & 0xFF) << 24);
                receiveText.append(String.format("Siste løp har %d brikker\n", recNo-prevNo+1));

                // Get current time and compare
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
            } else if (CurrentSize > 0) {
                receiveText.append(String.format("nextPut=%d  nextGet=%d\n", cBuf.nextPut,
                        cBuf.nextGet));
                receiveText.append("Ukjent pakke med " + CurrentSize + " bytes\n");
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
        spn.setSpan(new ForegroundColorSpan(ContextCompat.getColor(requireContext(),R.color.colorStatusText)), 0,
                spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    private char b64(int x) {
        if (x<0) {
            x = 256+x;
        }
        x = x & 0x3F;
        return b64chars[x];
    }

    private int bb(byte[] brikke_buffer, int i) {
        // Convert byte to an integer between 0 and 255
        return (int)brikke_buffer[i]&0xff;
    }

    int CompressTag(byte[] brikke_buffer, char[] compressedData) {
        int pos = 0;
        int i;
        int prev_tid = 0;
        int totalTime = 0;
        //byte[] compressedData = new byte[256];
        // Protokollnummer er første byte
        compressedData[pos++] = b64(49);
        // Batterispenning, 1 tegn (x=0..63 = spenning/0.1 = 0..=6.3V
        int bv = 63;
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
            int posttid = (bb(brikke_buffer,27 + 3 * i) & 0xFF) + ((bb(brikke_buffer,28 + 3 * i) & 0xFF) << 8);
            int tid = posttid - prev_tid;
            prev_tid = posttid;
            if (i > 0 && post == 0) break;
            if (tid <= 511) {
                // |0ttttt|ttttpp|pppppp|
                compressedData[pos++] =b64((tid >> 4) & 0x1f);
            } else {
                // |1ttttt|tttttt|ttttpp|pppppp|
                compressedData[pos++] =b64(32 | ((tid >> 10) & 0x1F));
                compressedData[pos++] =b64(((tid >> 4) & 0x3F));
            }
            compressedData[pos++] =b64(((post >> 6) & 0x03) | ((tid & 0x0f) << 2));
            compressedData[pos++] =b64((post & 0x3F));
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
        compressedData[pos] = 0; //String termination
        return totalTime;
    }
}

