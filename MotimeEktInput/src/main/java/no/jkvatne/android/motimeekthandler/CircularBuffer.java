package no.jkvatne.android.motimeekthandler;

public class CircularBuffer {

    public CircularBuffer(int size) {
        this.size = size;
        buf = new byte[size];
        clear();
    }

    private final int size;

    private final byte[] buf;

    private int length;

    public int nextGet;

    public int nextPut;

    public int size() {
        return size;
    }

    public int length() {
        return length;
    }

    public void clear() {
        length = 0;
        nextGet = 0;
        nextPut = 0;
    }

    public byte get() {
        if (nextGet != nextPut) {
            length--;
            byte b = buf[nextGet++];
            if (nextGet >= size)
                nextGet = 0;
            return b;
        }
        return 0;
    }

    public boolean isFull() {
        return ((nextPut + 1) == nextGet) || (nextPut == (size - 1)) && (nextGet == 0);
    }

    public boolean isEmpty() {
        return nextPut == nextGet;
    }

    public void put(byte b) {
        if (!isFull()) {
            length++;
            buf[nextPut++] = b;
            if (nextPut >= size)
                nextPut = 0;
        }
    }

    public boolean Has(int c) {
        for (int i = 0; i<length(); i++) {
            if (peek(i)==c) return true;
        }
        return false;
    }

    // Skip until we find the byte given or no more bytes left in buffer
    public boolean SkipTo(int c) {
        while (length()>0) {
            if (get()==(byte)c) return true;
        }
        return false;
    }

    public boolean foundMessage() {
        if (length() < 59) {
            return false;
        }
        boolean ok = false;
        // Skip until we find four 0xFF in a row
        while (!ok && length > 54) {
            ok = true;
            for (int i = 0; i < 4; i++) {
                ok = ok && ((buf[(nextGet + i) % size]) == (byte) 0xFF);
            }
            if (!ok) {
                get();
            }
        }
        // If the flag FFFFFFFF are not found, just exit false
        if (!ok) {
            return false;
        }
        // Now check length
        int n = (nextGet + 4) % size;
        int msgLen = (int) buf[n] & 0xFF;
        msgLen += 4;
        return length >= msgLen;
    }

    public byte peek(int i) {
        return buf[(nextGet + i) % size];
    }

    public byte last() {
        return buf[nextGet];
    }

    public void skip(int n) {
        if (length > n) {
            for (int i = 0; i < n; i++) {
                length--;
                nextGet++;
                if (nextGet >= size) {
                    nextGet = 0;
                }
            }
        }
    }

    public void skipSpace() {
        while ((last() == ' ') || (last() == 0x09)) {
            skip(1);
        }
    }

    public int getInt() {
        int x = 0;
        x = (int) get() & 0xFF;
        x = x | ((int) get() & 0xFF) << 8;
        x = x | ((int) get() & 0xFF) << 16;
        x = x | ((int) get() & 0xFF) << 24;
        return x;
    }

    public boolean found(byte b) {
        if (last() == b) {
            skip(1);
            return true;
        }
        return false;
    }

    // getString will return a string from the circular buffer.
    // The string ends when a control character (<32) is reached. (normally TAB).
    // A terminating TAB is skipped
    public String getString() {
        if (peek(0)==0x09) skip(1);
        StringBuilder s = new StringBuilder();
        while (peek(0) >= 0x20) {
            s.append((char) get());
        }
        // Skip any terminating TAB
        found((byte)9);
        return s.toString();
    }

    // getNumeric will read numeric characters up to the first non-numeric character
    // Skips any terminating TAB character
    public String getNumeric() {
        StringBuilder s = new StringBuilder();
        while ((last() >= (byte)'0') && (last() <= (byte)'9')) {
            s.append((char) get());
        }
        // Skip any terminating TAB
        found((byte)9);
        return s.toString();
    }

    // parseInt will read in numeric characters and return the corresponding integer
    // It will stop at the first non-numeric character
    public int parseInt() {
        skipSpace();
        String s = getNumeric();
        if (!s.isEmpty()) return Integer.parseInt(s);
        return 0;
    }
}
