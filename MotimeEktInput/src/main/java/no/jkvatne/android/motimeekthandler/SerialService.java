package no.jkvatne.android.motimeekthandler;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Locale;

/**
 * create notification and queue serial data while activity is not in the foreground
 * use listener chain: SerialSocket -> SerialService -> UI fragment
 */
public class SerialService extends Service implements SerialListener {

    private static final String CHANNEL_ID = "UsbServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private boolean isRunning = false;

    class SerialBinder extends Binder {
        SerialService getService() { return SerialService.this; }
    }

    private final Handler mainLooper;
    private final IBinder binder;

    private SerialSocket socket;
    private SerialListener listener;
    private boolean connected;
    private CircularBuffer cBuf;
    private String batterySts = "";
    private String ecbTime = "";
    private String ecbDate = "";
    private String messNo = "";
    private String ServerUrl = "<loaded from apikey.properties by gradle>";
    ToneGenerator toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
    private static final char[] b64chars = {'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
            'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z', 'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h',
            'i', 'j', 'k', 'l', 'm', 'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z', '0', '1', '2',
            '3', '4', '5', '6', '7', '8', '9', '-', '_'};
    private int day;
    private int month;
    private int year;
    private long lastMessageMs;
    public boolean eScanOk = false;
    public boolean eScan2Ok = false;
    public boolean mtrOk = false;
    private int bb(byte[] badge_buffer, int i) {
        // Convert byte to an integer between 0 and 255
        return (int) badge_buffer[i] & 0xff;
    }

    public void getUrlContent(String url) {
           HttpURLConnection urlConnection = null;
        try {
            URL urlc = new URL(url);
            urlConnection = (HttpURLConnection) urlc.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.setRequestProperty("Content-Type", "application/json; utf-8");
            urlConnection.setDoOutput(true);
            urlConnection.setConnectTimeout(3000);
            urlConnection.setReadTimeout(3000);
            int responseCode = urlConnection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                Log.i("ECB","Got HTTP response, "+ responseCode);
            } else {
                Log.i("ECB", "Wrong response code: "+responseCode);
            }
        } catch (Exception e) {
            Log.e("ECB", "Got exception in getUrlContent, ", e);
        } finally {
            if (urlConnection != null) {
                urlConnection.disconnect();
            }
        }
    }

    private char b64(int x) {
        if (x < 0) {
            x = 256 + x;
        }
        x = x & 0x3F;
        return b64chars[x];
    }

    int CompressTag(byte[] badge_buffer, char[] compressedData) {
        int pos = 0;
        int i;
        int prev_tid = 0;
        int totalTime = 0;
        //byte[] compressedData = new byte[256];
        // Protocol number is in first byte
        compressedData[pos++] = b64(49);
        // Battery voltage, 1 character (x=0..63 = voltage/0.1 = 0..6.3. Default to max
        int bv = 63;
        compressedData[pos++] = b64(bv);
        // Measured time, 5 char, year modulo 16, |YYYYMM|MMDDDD|DHHHHH|MMMMMM|SSSSSS
        // WRONG: Year is years after 1900 mod 16, so 2022 is 10 (0xA). Add 2012
        // buf[8] bits 2..7 is year mod 16, so 2026 is 10. Add 2016.
        // Month is 0-11
        compressedData[pos++] =
                b64((((bb(badge_buffer, 8) + 1900) & 0x0F) << 2) + ((bb(badge_buffer, 9) >> 2) & 3));
        compressedData[pos++] =
                b64((((bb(badge_buffer, 9)) & 3) << 4) + ((bb(badge_buffer, 10) >> 1) & 0x0F));
        compressedData[pos++] =
                b64(((bb(badge_buffer, 10) & 1) << 5) + (bb(badge_buffer, 11) & 31));
        compressedData[pos++] =
                b64(bb(badge_buffer, 12) & 0x3f);  // Minutes
        compressedData[pos++] =
                b64(bb(badge_buffer, 13) & 0x3f);  // Seconds
        // Number of controls (filled in later)
        compressedData[pos++] = b64(0);
        // Badge number
        //    3       2      1     0
        // |cccccc|ccbbbb|bbbbaa|aaaaaa|
        compressedData[pos++] =
                b64(bb(badge_buffer, 20) & 0x3F);
        compressedData[pos++] =
                b64(((bb(badge_buffer, 20) >> 6) & 0x03) | ((bb(badge_buffer, 21) & 0x0f) << 2));
        compressedData[pos++] =
                b64(((bb(badge_buffer, 21) >> 4) & 0x0f) | ((bb(badge_buffer, 22) & 0x03) << 4));
        compressedData[pos++] =
                b64(((bb(badge_buffer, 22) >> 2) & 0x3f));
        // Control codes
        for (i = 0; i < 50; i++) {
            int post = bb(badge_buffer, 3 * i + 26);
            int controlTime = (bb(badge_buffer, 27 + 3 * i) & 0xFF) + ((bb(badge_buffer, 28 + 3 * i) & 0xFF) << 8);
            int tid = controlTime - prev_tid;
            prev_tid = controlTime;
            if (i > 0 && post == 0) break;
            if (tid <= 511) {
                // |0ttttt|ttttpp|pppppp|
                compressedData[pos++] = b64((tid >> 4) & 0x1f);
            } else {
                // |1ttttt|tttttt|ttttpp|pppppp|
                compressedData[pos++] = b64(32 | ((tid >> 10) & 0x1F));
                compressedData[pos++] = b64(((tid >> 4) & 0x3F));
            }
            compressedData[pos++] = b64(((post >> 6) & 0x03) | ((tid & 0x0f) << 2));
            compressedData[pos++] = b64((post & 0x3F));
            if (post < 250) {
                totalTime = controlTime;
            }
        }

        // Number of controls is stored at offset 7
        compressedData[7] = b64(i);

        // Add 2 character checksum on data part
        int checksum = 0;
        for (int j = 0; j < pos; j++) {
            checksum += compressedData[j];
        }
        compressedData[pos++] = b64(checksum & 0x3F);
        compressedData[pos++] = b64((checksum >> 6) & 0x3F);
        compressedData[pos] = 0; //String termination
        return totalTime;
    }

    private void receiveEcb(byte[] data) {
        int messLen;
        int ektNo;
        int tagNo=0;
        int eScanCtrlNo = 0;
        int recordNo = 0;
        int protocolType = 0;
        // Copy data into circular buffer
        for (byte c : data) {
            cBuf.put(c);
        }
        // Move to 0x02 (STX) if possible
        while ((!cBuf.isEmpty()) && (cBuf.peek(0) != 0x02)) {
            cBuf.get();
        }
        if (cBuf.isEmpty()) return;
        // Search for end of message (ETX = 0x03)
        boolean ok = false;
        for (messLen = 0; messLen < cBuf.length(); messLen++) {
            if (cBuf.peek(messLen) == 0x03) {
                ok = true;
                break;
            }
        }
        if (!ok) return;
        // We now have a message ready, with length messLen
        byte id;
        cBuf.skip(1);    // Get STX and ignore it
        id = cBuf.get();    // get first char
        if (id == 'I') {
            toneGen.startTone(ToneGenerator.TONE_CDMA_PIP,100);
            while (!cBuf.isEmpty() && cBuf.peek(0) >= 0x20) {
                byte ch = cBuf.peek(0);
                String s = cBuf.getString();
                if (s.isEmpty()) break;
                if (ch == 'A') {
                    batterySts = s.substring(s.length()-4);
                    if (batterySts.charAt(0)=='-') batterySts = batterySts.substring(1);
                    if (batterySts.endsWith("B")) {
                        batterySts = batterySts.substring(0, batterySts.length()-1);
                    }
                } else if (ch == 'W') {
                    ecbTime = s.substring(1);
                } else if (ch == 'U') {
                    ecbDate = s.substring(1);
                } else if (ch == 'M') {
                    messNo = s.substring(1);
                }
            }
            if (ecbTime.length()>4) {
                onSerialStatus( getString(R.string.escan_sts, ecbTime.substring(0, ecbTime.length() - 4), batterySts, messNo));
            }
        } else if (id == '/') {
            while (true) {
                byte b = cBuf.get();
                if (b==10 || b==0) {
                    break;
                }
            }

        } else if (id == 'N') {
            int tStart = 0;
            int n = 0;
            int no;
            byte[] buf = new byte[256];
            Log.i("ECB","Badge message");
            while (!cBuf.isEmpty() && cBuf.last() != 0x03 && cBuf.last() != 0x00 && cBuf.last()!=0x02) {
                n++;
                if (n>260) {
                    Log.e("ECB", "Hanging in receiveEcb()");
                    cBuf.clear();
                    break;
                }
                byte b = cBuf.last();
                if (b == 'N') {
                    // N  is the custom tag number, normally equal to the internal, permanent tag number
                    cBuf.skip(1);
                    ektNo = cBuf.parseInt();
                    buf[20] = (byte) (ektNo & 0xFF);
                    buf[21] = (byte) ((ektNo >> 8) & 0xFF);
                    buf[22] = (byte) ((ektNo >> 16) & 0xFF);
                } else if (b == '/') {
                    while (b!=10 && b!=0) {
                        b = cBuf.last();
                    }
                } else if (b == 'M') {
                    // M is Tag passing number (eScan record number, starting at 0 for a new race)
                    cBuf.skip(1);
                    recordNo = cBuf.parseInt();
                } else if (b == 'C') {
                    // Control code of the eScan reader, typically 250.
                    cBuf.skip(1);
                    eScanCtrlNo = cBuf.parseInt();
                } else if (b == 'L') {
                    // Emitag version, skip the text (0120)
                    cBuf.getString();
                } else if (b == 'X') {
                    // Protocol type 0-7
                    cBuf.skip(1);
                    protocolType = cBuf.parseInt();
                } else if (b == 'V') {
                    // Power information, just skip it
                    cBuf.getString();
                } else if (b == 'S') {
                    // S is the permanent tag number/serial number. Normally equal to the custom tag number.
                    cBuf.skip(1);
                    // N or S is the ekt number
                    tagNo = cBuf.parseInt();
                    buf[20] = (byte) (tagNo & 0xFF);
                    buf[21] = (byte) ((tagNo >> 8) & 0xFF);
                    buf[22] = (byte) ((tagNo >> 16) & 0xFF);
                } else if (b == 'W') {
                    cBuf.skip(1);
                    // Time when badge was read
                    buf[8] = (byte) (year - 1900);
                    buf[9] = (byte) month;
                    buf[10] = (byte) day;
                    buf[11] = (byte) cBuf.parseInt();  // hr
                    cBuf.found((byte) ':');
                    buf[12] = (byte) cBuf.parseInt();  // min
                    cBuf.found((byte) ':');
                    buf[13] = (byte) cBuf.parseInt();  // sec
                    cBuf.found((byte) '.');
                    // Skip milliseconds
                    cBuf.getNumeric();
                } else if (b == 'U') {
                    cBuf.skip(1);
                    // Date, i.e. 21.01.2021
                    day = cBuf.parseInt();
                    cBuf.skip(1);  // Skip "."
                    month = cBuf.parseInt();
                    cBuf.skip(1);  // Skip "."
                    year = cBuf.parseInt();
                } else if (b == 'P') {
                    // Punching data for one control
                    cBuf.skip(1);
                    no = cBuf.parseInt();
                    cBuf.skip(1);  // Skip "-"
                    int control = cBuf.parseInt();
                    cBuf.skip(1);  // Skip "-"
                    int hrs = cBuf.parseInt();
                    cBuf.skip(1);  // Skip ":"
                    int min = cBuf.parseInt();
                    cBuf.skip(1);  // Skip ":"
                    int sec = cBuf.parseInt();
                    cBuf.skip(1);  // Skip "."
                    //int milliSecond = cBuf.parseInt();
                    // Save data to buffer
                    buf[3 * no + 26] = (byte) control;
                    int t = sec + min * 60 + hrs * 3600;
                    if (no == 0) tStart = t;
                    buf[3 * no + 27] = (byte) ((t - tStart) & 0xFF);
                    buf[3 * no + 28] = (byte) (((t - tStart) >> 8) & 0xFF);

                } else {
                    // Skip the text
                    cBuf.getString();
                }
            }
            Log.i("ECB",getString(R.string.badge_message, tagNo, eScanCtrlNo, recordNo));

            char[] compressedData = new char[256];
            int totalTime = CompressTag(buf, compressedData);

            if (protocolType>0) {
                onSerialProgress(getString(R.string.wrong_protocol));
            }
            if (n == 0) {
                onSerialProgress(getString(R.string.empty_message,  tagNo, eScanCtrlNo, recordNo));
                Log.e("ECB",getString(R.string.empty_message, tagNo, eScanCtrlNo, recordNo));
            }

            if (n>0) {
                ektNo = ((int) buf[20] & 0xFF) + (((int) buf[21] & 0xFF) << 8) + (((int) buf[22] & 0xFF) << 16);
                onSerialProgress(String.format(Locale.ROOT, "%02d:%02d:%02d: Tag %7d %3d:%02d\n",
                        buf[11], buf[12], buf[13], ektNo, totalTime / 60, totalTime % 60));
                String url = ServerUrl + "a=" + String.valueOf(compressedData);
                url = url.trim();
                Log.i("ECB","Url="+url+"\n");
                getUrlContent(url);
            }
        }
    }

    private void send(String str) {
        str = str + "\r\n";
        try {
            Log.i("ECB", "send() '"+str.trim()+"'");
            byte[] data = (str).getBytes();
            write(data);
        } catch (Exception e) {
            Log.e("ECB", "send() exception on service.write");
            onSerialProgress(e.getMessage());
        }
    }

    private void receiveMtr(byte[] data) {
        for (byte datum : data) {
            cBuf.put(datum);
        }
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
                onSerialProgress(String.format(Locale.ROOT, "EKT %02d-%02d-%02d %02d:%02d:%02d ",
                        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13]));

                int ektNo = ((int) buf[20] & 0xFF) + (((int) buf[21] & 0xFF) << 8) + (((int) buf[22] & 0xFF) << 16);
                onSerialProgress(String.format(Locale.ROOT, "Nr %d %d:%02d\n", ektNo,
                        totalTime / 60, totalTime % 60));
                String url =  ServerUrl + "a=" + String.valueOf(compressedData);
                getUrlContent(url);
            } else if (CurrentSize == 55) {
                onSerialStatus("MTR ok");
                onSerialProgress(String.format(Locale.ROOT, "Status 20%02d-%02d-%02d %02d:%02d:%02d\n",
                        buf[8], buf[9], buf[10], buf[11], buf[12], buf[13]));
                int recNo = ((int) buf[17] & 0xFF) + (((int) buf[18] & 0xFF) << 8)
                        + (((int) buf[19] & 0xFF) << 16)+(((int) buf[20] & 0xFF) << 24);
                int prevNo = ((int) buf[25] & 0xFF) + (((int) buf[26] & 0xFF) << 8)
                        + (((int) buf[27] & 0xFF) << 16)+(((int) buf[28] & 0xFF) << 24);
                onSerialProgress(getString(R.string.last_count, recNo-prevNo+1));

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
                    // receiveText.append(getString(R.string.update_mtr_clock));
                    send("/SC"+(char)y+(char)mo+(char)d+(char)h+(char)mi+(char)s);
                    try { MILLISECONDS.sleep(100);} catch (Exception ignored) {}
                } else {
                    onSerialProgress(getString(R.string.mtr_clock_ok));
                }
            } else if (CurrentSize > 0) {
                onSerialProgress(getString(R.string.unknown_package, CurrentSize));
            }
        }
    }

    private void receive(byte[] data) {
        lastMessageMs = android.os.SystemClock.elapsedRealtime();
            if (eScanOk || eScan2Ok) {
                receiveEcb(data);
            } else if (mtrOk) {
                receiveMtr(data);
            } else if ((data.length>2)&&(data[0]==-1) && (data[1]==-1)) {
                mtrOk = true;
                receiveMtr(data);
            } else if ((data.length>3)&&(data[0]==2)&&(data[1]=='I')&&(data[2]=='E')&&(data[3]=='S')&&(data[7]=='2')) {
                eScan2Ok = true;
                Log.i("ECB","received first eScan2 message");
                receiveEcb(data);
            } else if ((data.length>3)&&(data[0]==2)&&(data[1]=='I')&&(data[2]=='e')&&(data[3]=='S')) {
                eScanOk = true;
                Log.i("ECB","received first eScan1 message");
                receiveEcb(data);
            }
    }

    // GetPackage will read one record from the MTR3/4.
    void GetPackage(int no) {
        // Sending /GBxxxx with binary x
        byte[] data = {0x2F, 0x47, 0x42, (byte) (no & 0xFF), (byte) ((no >> 8) & 0xFF), (byte) ((no >> 16) & 0xFF),
                (byte) ((no >> 24) & 0xFF)};
        //SendBytes(data);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {


        // 1. Instantly promote the service to Foreground to avoid OS death
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("USB Serial Monitor")
                .setContentText("Reading data in background...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        try {
            startForeground(NOTIFICATION_ID, notification);
            Log.i("ECB","onStartCommand: Notification started");
        } catch (Exception e) {
            Log.e("ECB", "onStartCommand exception in startForgoround(): "+e);
        }

        // 2. Start your background thread logic if not running
        if (!isRunning) {
            isRunning = true;
            // startUsbReading();
        }

        return START_STICKY; // Tells the OS to recreate the service if killed under memory pressure
    }

    private void createNotificationChannel() {
        //if (Build.VERSION.SDK_INT >= 1) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "USB Background Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        //}
    }

    /**
     * Lifecycle
     */

    @Override
    public void onCreate() {
        super.onCreate();
        byte[] buf = BuildConfig.SERVER_URL.getBytes();
        ServerUrl = new String(buf, StandardCharsets.UTF_8);
        cBuf = new CircularBuffer(20480);
        createNotificationChannel();
        mtrOk = false;
        eScanOk = false;
        eScan2Ok = false;
    }

    public SerialService() {
        mainLooper = new Handler(Looper.getMainLooper());
        binder = new SerialBinder();
    }

    @Override
    public void onDestroy() {
        cancelNotification();
        disconnect();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Api
     */
    public void connect(SerialSocket socket) throws IOException {
        Log.i("ECB", "SerialService connect");
        socket.connect(this);
        this.socket = socket;
        connected = true;
        mtrOk = false;
        eScanOk = false;
        eScan2Ok = false;
    }

    public void disconnect() {
        Log.i("ECB", "SerialService disconnect");
        connected = false; // ignore data,errors while disconnecting
        cancelNotification();
        if(socket != null) {
            socket.disconnect();
            socket = null;
        }
        mtrOk = false;
        eScanOk = false;
        eScan2Ok = false;
    }

    public void write(byte[] data) throws IOException {
        if(!connected)
            throw new IOException("not connected");
        socket.write(data);
    }

    void attach(SerialListener listener) {
        Log.i("ECB","attach SerialListener");
        if(Looper.getMainLooper().getThread() != Thread.currentThread()) {
            Log.e("ECB", "SerialListener: IllegalArgumentException, not in main thread.");
            throw new IllegalArgumentException("not in main thread");
        }
        initNotification();

        // use synchronized() to prevent new items in queue2
        // new items will not be added to queue1 because mainLooper.post and attach() run in main thread
        synchronized (this) {
            this.listener = listener;
        }
        if(connected) {
            createNotification();
        }

    }

    public void detach() {
        Log.i("ECB","detach SerialListener");
        // items already in event queue (posted before detach() to mainLooper) will end up in queue1
        // items occurring later, will be moved directly to queue2
        // detach() and mainLooper.post run in the main thread, so all items are caught
        listener = null;
    }

    private void initNotification() {
        NotificationChannel nc = new NotificationChannel(Constants.NOTIFICATION_CHANNEL, "Background service", NotificationManager.IMPORTANCE_LOW);
        nc.setShowBadge(false);
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(nc);
    }

    public boolean notificationsNotEnabled() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        NotificationChannel nc = nm.getNotificationChannel(Constants.NOTIFICATION_CHANNEL);
        return !(nm.areNotificationsEnabled() && nc != null && nc.getImportance() > NotificationManager.IMPORTANCE_NONE);
    }

    private void createNotification() {
        Intent disconnectIntent = new Intent()
                .setPackage(getPackageName())
                .setAction(Constants.INTENT_ACTION_DISCONNECT);
        Intent restartIntent = new Intent()
                .setClassName(this, Constants.INTENT_CLASS_MAIN_ACTIVITY)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        int flags = PendingIntent.FLAG_IMMUTABLE;
        PendingIntent disconnectPendingIntent = PendingIntent.getBroadcast(this, 1, disconnectIntent, flags);
        PendingIntent restartPendingIntent = PendingIntent.getActivity(this, 1, restartIntent,  flags);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(getResources().getColor(R.color.colorPrimary, null))
                .setContentTitle(getResources().getString(R.string.app_name))
                .setContentText(socket != null ? "Connected to "+socket.getName() : "Background Service")
                .setContentIntent(restartPendingIntent)
                .setOngoing(true)
                .addAction(new NotificationCompat.Action(R.drawable.ic_send_white_24dp,
                        "Disconnect", disconnectPendingIntent));
        // @drawable/ic_notification created with Android Studio -> New -> Image Asset using @color/colorPrimaryDark as background color
        // Android < API 21 does not support vectorDrawables in notifications, so both drawables used here, are created as .png instead of .xml
        Notification notification = builder.build();
        startForeground(Constants.NOTIFY_MANAGER_START_FOREGROUND_SERVICE, notification);
    }

    private void cancelNotification() {
        Log.i("ECB","cancelNotification for SerialService");
        stopForeground(true);
    }



    /**
     * SerialListener
     */
    public void onSerialConnect() {
        Log.i("ECB","onSerialConnect()");
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnect();
                        }
                    });
                } else {
                    Log.e("ECB", "No listener");
                }
            }
        }
    }

    public void onSerialConnectError(Exception e) {
        Log.i("ECB","onSerialConnectError()");
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialConnectError(e);
                        }
                    });
                } else {
                    Log.e("ECB", "onSerialConnectError: No listener");
                }
            }
        }
    }

    public void onSerialRead(ArrayDeque<byte[]> dataStrings) { throw new UnsupportedOperationException(); }

    /**
     * reduce number of UI updates by merging data chunks.
     * Data can arrive at >100 chunks per second, but the UI can only
     * perform a dozen updates if receiveText already contains much text.
     * On new data inform UI thread once (1).
     * While not consumed (2), add more data (3).
     */
    public void onSerialRead(byte[] data) {
        if(connected) {
            receive(data);
        } else {
            Log.e("ECB","onSerialRead() but not connected");
        }
    }

    public void onSerialIoError(Exception e) {
        Log.i("ECB","onSerialIoError()");
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialIoError(e);
                        }
                    });
                } else {
                    Log.e("ECB", "onSerialIoError: No listener");
                }
            }
        }
    }

    public void onSerialProgress(String s) {
        Log.i("ECB","onSerialProgress "+s);
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialProgress(s);
                        }
                    });
                } else {
                    Log.e("ECB", "onSerialProgress: No listener");
                }
            }
        }
    }

    public void onSerialStatus(String s) {
        Log.i("ECB","onSerialStatus "+s);
        if(connected) {
            synchronized (this) {
                if (listener != null) {
                    mainLooper.post(() -> {
                        if (listener != null) {
                            listener.onSerialStatus(s);
                        }
                    });
                } else {
                    Log.e("ECB", "onSerialStatus: No listener");
                }
            }
        }
    }

}
