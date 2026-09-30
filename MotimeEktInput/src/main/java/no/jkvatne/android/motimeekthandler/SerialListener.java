package no.jkvatne.android.motimeekthandler;

import java.util.ArrayDeque;

interface SerialListener {
    void onSerialConnect      ();
    void onSerialConnectError (Exception e);
    void onSerialRead         (byte[] data);                // socket -> service
    void onSerialIoError      (Exception e);
    void onSerialProgress     (String s);
    void onSerialStatus     (String s);
}
