package no.jkvatne.android.motimeekthandler;
/*

import android.content.DialogInterface;
import android.os.Bundle;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Handler;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Message;

import java.security.SecureRandom;
import java.util.ArrayList;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;


public class BleDevices extends AppCompatActivity {
private String mac_address;
private final static String TAG = "MyBle";
private BluetoothAdapter mBluetoothAdapter;
private BleListAdapter mBleListAdapter;
private boolean mScanning;
private Handler mHandler;
private static final int REQUEST_ENABLE_BT = 1;
private static final long SCAN_PERIOD = 10000;
private ListView listView;
private BluetoothLeScanner bleScanner;
static class ViewHolder {
    TextView deviceName;
    TextView deviceAddress;
}
private Ble ble;
private FloatingActionButton fab_open;
private FloatingActionButton fab_close;
private FloatingActionButton fab_set_keys;

private static String toHex(final byte[] data) {
    final StringBuilder sb = new StringBuilder(data.length * 2);
    for (final byte b : data) {
        sb.append(String.format("%02X", b));
    }
    return sb.toString();
}

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    Log.d(TAG, "onCreate()");

    setContentView(R.layout.activity_ble_devices);
    listView = (ListView)findViewById(R.id.my_list_view);

    // Create Handler to stop scanning after some time
    mHandler = new Handler();

    // Check that phone has BLE
    if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        Toast.makeText(this,
                R.string.ble_not_supported,
                Toast.LENGTH_SHORT).show();
    }

    final BluetoothManager bluetoothManager =
            (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
    mBluetoothAdapter = bluetoothManager.getAdapter();
    if (mBluetoothAdapter == null   ) {
        //Message that Bluetooth not supported
        Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
    } else {
        bleScanner = mBluetoothAdapter.getBluetoothLeScanner();
    }

    listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> listView, View view, int position, long id) {
            final BluetoothDevice device = mBleListAdapter.getDevice(position);
            if (device != null) {
                String name = device.getName();
                Toast.makeText(getApplicationContext(), name, Toast.LENGTH_SHORT).show();
            }
        }

    });

    Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
    setSupportActionBar(toolbar);

    //FloatingActionButton
    fab_open = (FloatingActionButton) findViewById(R.id.fab_open);
    fab_close = (FloatingActionButton) findViewById(R.id.fab_close);
    fab_set_keys = (FloatingActionButton) findViewById(R.id.fab_set_keys);
    fab_set_keys.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            String s;
            try {
                final SecureRandom prng = new SecureRandom();
                final byte[] aes128KeyData  = new byte[128 / Byte.SIZE];
                prng.nextBytes(aes128KeyData);
                final SecretKey aesKey1 = new SecretKeySpec(aes128KeyData, "AES");
                s = toHex(aesKey1.getEncoded());
                prng.nextBytes(aes128KeyData);
                final SecretKey aesKey2 = new SecretKeySpec(aes128KeyData, "AES");
                s = s+ "\n" + toHex(aesKey2.getEncoded());
                Log.d(TAG, "Keys=\n"+s);

                Toast.makeText(getApplicationContext(), "Set key to "+s,
                        Toast.LENGTH_SHORT).show();
                final View v = view;
                ble.setServerKeys(aesKey1.getEncoded(), aesKey2.getEncoded(), mac_address,
                        new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "OK setting server keys");
                                showAlert(v,"OK setting server keys");
                                TextView mTextView = (TextView) findViewById(R.id.text_secret_key);
                                mTextView.setText("OK setting server keys");

                            }
                        },
                        new Runnable() {
                            @Override
                            public void run() {
                                Log.d(TAG, "Failed writing keys to server");
                                showAlert(v,"Failed writing keys to server");
                                TextView mTextView = (TextView) findViewById(R.id.text_secret_key);
                                mTextView.setText("Failed writing keys to server");
                            }
                        }
                );
            } catch (Exception e){
                s = "Exception creating key";
                Toast.makeText(getApplicationContext(), "Exception in key generation",
                        Toast.LENGTH_SHORT).show();
            }
            TextView mTextView = (TextView) findViewById(R.id.text_secret_key);
            mTextView.setText("Keys written ok");

        }

    });
    fab_open.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Toast.makeText(getApplicationContext(), "Open keysafe", Toast.LENGTH_SHORT).show();
            ble.openKeysafe();
        }
    });
    fab_close.setOnClickListener(new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Toast.makeText(getApplicationContext(), "Close keysafe", Toast.LENGTH_SHORT).show();
            ble.closeKeysafe();
        }
    });
    fab_open.hide();
    fab_close.hide();
    ble=Ble.getInstance(this);
}

public void showAlert(final View view, final String str) {
    AlertDialog.Builder builder = new AlertDialog.Builder(view.getContext());
    builder.setMessage(str);
    builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
            // User clicked OK button
        }
    });
    builder.show();
}

@Override
public boolean onCreateOptionsMenu(Menu menu) {
    // Inflate the menu; this adds items to the action bar if it is present.
    //getMenuInflater().inflate(R.menu.menu_ble_devices, menu);
    return true;
}
@Override
protected void onResume() {
    super.onResume();

    if (mBluetoothAdapter!=null) {
        Log.d(TAG, "onResume() - mBluetoothAdapter exists");
        if (!mBluetoothAdapter.isEnabled()) {
            //Create an intent to get permission to enable BT
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        mBleListAdapter = new BleListAdapter();
        //Create new list adapter to hold list of BLE devices found during scan
        listView.setAdapter(mBleListAdapter);
        scanBleDevices(true);
    }
}

@Override
protected void onPause() {
    super.onPause();
    Log.d(TAG, "onPause()");
    scanBleDevices(false);
    bleScanner = null;
    if (mBleListAdapter!=null) {
        mBleListAdapter.clear();
    }

    if (ble!=null) {
        ble.bleClose();
    }
    ble=null;
}

@Override
public boolean onOptionsItemSelected(MenuItem item) {
    // Handle action bar item clicks here. The action bar will
    // automatically handle clicks on the Home/Up button, so long
    // as you specify a parent activity in AndroidManifest.xml.
    int id = item.getItemId();

    //noinspection SimplifiableIfStatement
    if (id == R.id.action_settings) {
        return true;
    }

    return super.onOptionsItemSelected(item);
}


// The mLeScanCallback method is called each time a device is found during the scan
private void scanBleDevices(final boolean enable) {
    if (bleScanner==null) {
        Log.e(TAG, "scanBleDevices() error, bleScanner==null");
        return;
    }
    if (enable) {
        Log.d(TAG, "scanBleDevices() - enable");
        mScanning = true;
        // Start scanning with callback method to execute when a new BLE device is found
        bleScanner.flushPendingScanResults(mLeScanCallback);
        bleScanner.startScan(mLeScanCallback);
    } else {
        Log.d(TAG, "scanBleDevices() - disable");
        mScanning = false;
        //mBluetoothAdapter.stopLeScan(mLeScanCallback);
        bleScanner.stopScan(mLeScanCallback);
        // scanning - callback method indicates which scan to stop
        //mBluetoothAdapter.stopScan(mLeScanCallback);
    }
    invalidateOptionsMenu();
}

// Device scan callback. Bluetooth adapter calls this method when a
// new device is discovered during a scan.
private final ScanCallback mLeScanCallback = new ScanCallback() {
    @Override
    public void onScanResult(int callbackType, final ScanResult scanResult) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                BluetoothDevice device = scanResult.getDevice();
                if ((ble!=null) &&(ble.bleState != Ble.BleState.CONNECTING)) {
                    final String deviceName = device.getName();
                    Log.d(TAG, "Found BLE Device: " + device.getAddress() + " Name=" + deviceName);
                    if ((deviceName != null) && (deviceName.length()>=7)) {
                        String s = deviceName.substring(0, 7);
                        if (s.equals("Keysafe")) {
                            mBleListAdapter.addDevice(device);
                            mBleListAdapter.notifyDataSetChanged();
                            fab_open.show();
                            fab_close.show();
                            doOpenBle(device.getAddress());
                            mac_address = device.getAddress();
                        }
                    }
                }
            }
        });
    }
};

Ble.BleState oldState;

private void doOpenBle(String address) {
    final Handler bleHandler = new Handler(new Handler.Callback() {
        @Override
        public boolean handleMessage(Message msg) {
            if ((ble!=null)&&(oldState != ble.bleState) && (ble.bleState == Ble.BleState
                    .DISCONNECTED)) {
                mBleListAdapter.clear();
                mBleListAdapter.notifyDataSetChanged();
            }
            oldState = ble.bleState;
            return true;
        }
    });
    if (ble!=null) {
        //ble.bleOpen(getBaseContext(), "CC:78:AB:7E:BE:81", bleHandler);
        ble.bleOpen(getBaseContext(), address, bleHandler);
    }
}


// Adapter for holding devices found through scanning. Acts as a bridge between the
// list data and the view displaying the data
private class BleListAdapter extends BaseAdapter {
    private final ArrayList<BluetoothDevice> mBleDevices;
    private final LayoutInflater mInflator;

    public BleListAdapter() {
        super();
        mBleDevices = new ArrayList<>();
        mInflator = BleDevices.this.getLayoutInflater();
    }

    public void addDevice(BluetoothDevice device) {
        if(!mBleDevices.contains(device)) {
            mBleDevices.add(device);
        }
    }

    public BluetoothDevice getDevice(int position) {
        return mBleDevices.get(position);
    }

    public void clear() {                                                           //Method to clear the list
        mBleDevices.clear();
    }

    @Override
    public int getCount() {                                                         //Method to get number of devices in the list
        return mBleDevices.size();
    }

    @Override
    public Object getItem(int i) {                                                  //Method to get generic object from the list - Not used
        return mBleDevices.get(i);
    }

    @Override
    public long getItemId(int i) {                                                  //Method to get object's ID from the list - Not used
        return i;
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {
        ViewHolder viewHolder;
        // General ListView optimization code.
        if (view == null) {
            view = mInflator.inflate(R.layout.listitem_device, viewGroup, false);
            viewHolder = new ViewHolder();
            viewHolder.deviceAddress = (TextView) view.findViewById(R.id.device_address);
            viewHolder.deviceName = (TextView) view.findViewById(R.id.device_name);
            view.setTag(viewHolder);
        } else {
            viewHolder = (ViewHolder) view.getTag();
        }

        BluetoothDevice device = mBleDevices.get(i);
        final String deviceName = device.getName();
        if (deviceName != null && deviceName.length() > 0) {
            viewHolder.deviceName.setText(deviceName);
        }
        else {
            viewHolder.deviceName.setText(R.string.unknown_device);
        }
        viewHolder.deviceAddress.setText(device.getAddress());
        return view;
    }
}

}
*/
