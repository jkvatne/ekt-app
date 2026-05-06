package no.jkvatne.android.motimeekthandler;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Toast;

import java.util.Objects;

import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private BluetoothAdapter mBluetoothAdapter;
    // private BleDevices.BleListAdapter mBleListAdapter;
    private boolean mScanning;
    private BluetoothLeScanner bleScanner;
    private Ble ble;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Check that phone has BLE
        /*
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Toast.makeText(this,
                    R.string.ble_not_supported,
                    Toast.LENGTH_SHORT).show();
        } else {
            final BluetoothManager bluetoothManager =
                    (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            mBluetoothAdapter = bluetoothManager.getAdapter();
            if (mBluetoothAdapter == null) {
                //Message that Bluetooth not supported
                Toast.makeText(this, R.string.ble_not_supported, Toast.LENGTH_SHORT).show();
            } else {
                bleScanner = mBluetoothAdapter.getBluetoothLeScanner();
            }
            ble=Ble.getInstance(this);
        }
        */
    }

    @Override
    public void onBackStackChanged() {
        Objects.requireNonNull(getSupportActionBar()).setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        this.getOnBackPressedDispatcher().onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null) {
                terminal.status("USB device detected");
                terminal.connect();
            }
        }
        super.onNewIntent(intent);
    }


}
