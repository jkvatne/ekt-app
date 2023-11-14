package no.jkvatne.android.motimeekthandler;

public class CircularBuffer
{

    public CircularBuffer( int size )
    {
        this.size = size;
        buf = new byte[size];
        clear();
    }

    private final int size;

    private final byte[] buf;

    private int length;

    public int nextGet;

    public int nextPut;

    public int size()
    {
        return size;
    }

    public int length()
    {
        return length;
    }

    public void clear()
    {
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
        return ((nextPut+1) == nextGet) || (nextPut==(size-1)) && (nextGet==0);
    }

    public boolean isEmpty() {
        return nextPut==nextGet;
    }
    public void put( byte b ) {
        if (!isFull()) {
            length++;
            buf[nextPut++] = b;
            if (nextPut >= size)
                nextPut = 0;
        }
    }

    public boolean foundMessage() {
        if (length()<59) {
            return false;
        }
        boolean ok = false;
        // Skip until we find four 0xFF in a row
        while (!ok && length>54) {
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
        int n = (nextGet+4) % size;
        int msgLen = (int)buf[n] & 0xFF;
        msgLen += 4;
        return length>=msgLen;
    }

    public byte peek(int i) {
        return buf[(nextGet+i)%size];
    }

    public void skip(int n) {
        if (length>n) {
            for (int i=0; i<n; i++) {
                length--;
                nextGet++;
                if (nextGet>=size) {
                    nextGet = 0;
                }
            }
        }
    }

    public int getInt() {
        int x=0;
        x = (int)get()&0xFF;
        x = x | ((int)get()&0xFF)<<8;
        x = x | ((int)get()&0xFF)<<16;
        x = x | ((int)get()&0xFF)<<24;
        return x;
    }
}