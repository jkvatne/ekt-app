package no.jkvatne.android.motimeekthandler;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.os.Message;

import androidx.core.app.ActivityCompat;

import java.util.List;
import java.util.UUID;

/**
 * Bluetooth Low Energy interface (BLE)
 * This is a singleton class, instantiated on first call to getInstance()
 */

public class Ble {
    private final boolean logBle = false;

    public enum BleState {
        /**
         * Definition of BLE states as enum
         */
        NOT_SUPPORTED(1, R.string.ble_not_supported),
        TURNING_BLE_ON(2, R.string.turning_ble_on),
        CONNECTING(3, R.string.ble_connecting),
        NOT_FOUND(4, R.string.ble_device_not_found),
        LOST_CONNECTION(5, R.string.ble_lost_connection),
        COMMUNICATING(6, R.string.ble_data_received),
        NOT_USED(7, R.string.ble_not_used),
        DISCONNECTED(8, R.string.disconnected),
        DOWNLOADING(9, R.string.downloading);

        public final int index;
            public final int id;

        BleState(int index, int id) {
            this.index = index;
            this.id = id;
        }

        public String getName(Context ctx) {
            return  ctx.getString(this.id);
        }
    }


    private static final String TAG = "MyBle";

    //Private service for eScan2
    private static final String ESCAN_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e";
    //Characteristic for Data - notify, read, write
    private static final String ESCAN_CHAR_WRITE =  "6e400002-b5a3-f393-e0a9-e50e24dcca9e";
    private static final String ESCAN_CHAR_NOTIFY =  "6e400003-b5a3-f393-e0a9-e50e24dcca9e";

    //Special UUID for descriptor needed to enable notifications (?)
    private static final String NOTIFICATION_CONFIG = "f0007571-0451-4000-b000-000000000000";
    private BluetoothGattCharacteristic escanWriteChar;
    private BluetoothGattCharacteristic escanNotifyChar;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGatt mBluetoothGatt = null;
    public BleState bleState;
    // Value returned to onActivityResult()
    private static final int REQUEST_ENABLE_BT = 1;
    // Instance
    private static Ble ble = null;
    private Handler bleHandler;

    private Ble() {
        // Exists only to defeat instantiation.
    }

    public void escanReset(Context mContext, String deviceAddress, Handler handler) {
        if ((mBluetoothAdapter!=null)&&(mBluetoothAdapter.isEnabled())) {
            mBluetoothGatt = null;
            final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(deviceAddress);
            if (device == null) {
                bleState = BleState.NOT_FOUND;
                Log.w(TAG, "bleOpen(): Device not found : "+ deviceAddress);
            } else if (mBluetoothGatt==null){
                if (ActivityCompat.checkSelfPermission(mContext, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    // TODO: Consider calling  ActivityCompat#requestPermissions
                    // here to request the missing permissions, and then overriding
                    // public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults)
                    // to handle the case where the user grants the permission. See the documentation
                    // for ActivityCompat#requestPermissions for more details.
                    return;
                }
                // Do connect. Parameter 2 is auto-connect
                mBluetoothGatt = device.connectGatt(mContext, false, mGattCallback);
                Log.d(TAG, "bleOpen() : Connecting to " + deviceAddress);
                bleState = BleState.CONNECTING;
                boolean ok = mBluetoothGatt.requestConnectionPriority(BluetoothGatt
                        .CONNECTION_PRIORITY_HIGH);
                if (!ok) {
                    Log.d(TAG,"requestConnectionPriority returned false.");
                }
                bleHandler = handler;
            } else {
                Log.w(TAG,"bleOpen() found connection already ok.");
            }
        }
    }

    public void bleClose() {
        if (mBluetoothGatt!=null) {
            mBluetoothGatt.disconnect();
        }
        if (mBluetoothGatt!=null) {
            mBluetoothGatt.close();
        }
        ble=null;
        Log.w(TAG,"bleClose() - setting mBluetoothGatt=null");
    }

    private static String toHex(final byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (final byte b : data) {
            sb.append(String.format("%02X", b));
        }
        return sb.toString();
    }

    /**
     * Handle received strings
     * @param rxString is the string received
     */
    private void handleRxData(String rxString) {
        TerminalFragment.SetLogText(rxString);
    }


    private void bleGetAdapter(Activity mActivity) {
        final BluetoothManager bluetoothManager =
                (BluetoothManager)mActivity.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
        if (ble.mBluetoothAdapter == null) {
            bleState = BleState.NOT_SUPPORTED;
            Log.d(TAG, "Bluetooth not supported,  bluetoothManager.getAdapter() failed");
        } else {
            if (!mBluetoothAdapter.isEnabled()) {
                bleState = BleState.TURNING_BLE_ON;
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                mActivity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    public static Ble getInstance(Activity mActivity) {
        if (ble == null) {
            Log.e(TAG,"getInstance - creating new ble instance");
            ble = new Ble();
            ble.bleGetAdapter(mActivity);
        } else {
            Log.e(TAG, "getInstance - keep old instance");
        }
        return ble;
    }

    private void findGattService(List<BluetoothGattService> gattServices) {

        if (gattServices == null) {
            Log.e(TAG, "findGattService found no Services");
            bleState = BleState.NOT_SUPPORTED;
            return;
        }
        String uuid;

        Log.d(TAG, "findGattService() looping through gattServices");
        for (BluetoothGattService gattService : gattServices) {
            uuid = gattService.getUuid().toString();
            Log.d(TAG, "findGattService() UUID=" + uuid);
        }
        Log.d(TAG, "-- No more services");

        for (BluetoothGattService gattService : gattServices) {
            uuid = gattService.getUuid().toString();
            Log.d(TAG, "Checking UUID="+uuid);

            if (uuid.equalsIgnoreCase(ESCAN_SERVICE)) {
                Log.d(TAG, "Found KeySafe Service");
                List<BluetoothGattCharacteristic> gattCharacteristics =
                        gattService.getCharacteristics();
                for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                    uuid = gattCharacteristic.getUuid().toString();
                    Log.d(TAG, "Found KeySafe characteristic "+uuid);
                    if (uuid.equalsIgnoreCase(ESCAN_CHAR_WRITE)) {
                        // Found the KeySafe command1 characteristic.
                        bleState = BleState.CONNECTING;
                        escanWriteChar = gattCharacteristic;
                    }
                    if (uuid.equalsIgnoreCase(ESCAN_CHAR_NOTIFY)) {
                        bleState = BleState.CONNECTING;
                        escanNotifyChar = gattCharacteristic;
                        if (ActivityCompat.checkSelfPermission(null, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                            return;
                        }
                        final int characteristicProperties = gattCharacteristic.getProperties();
                        Log.d(TAG, "Found Escan notify characteristics with properties="+characteristicProperties);
                        if ((characteristicProperties
                                & (BluetoothGattCharacteristic.PROPERTY_NOTIFY)) > 0) {
                            mBluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);
                            BluetoothGattDescriptor descriptor = gattCharacteristic.getDescriptor(
                                    UUID.fromString(NOTIFICATION_CONFIG));
                            if (descriptor!=null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                mBluetoothGatt.writeDescriptor(descriptor);
                                Log.d(TAG, "Setting escan notification");
                            } else {
                                Log.e(TAG, "Setting escan notification failed");
                            }
                        }
                        if ((characteristicProperties
                                & (BluetoothGattCharacteristic.PROPERTY_INDICATE)) > 0) {
                            mBluetoothGatt.setCharacteristicNotification(gattCharacteristic, true);
                            BluetoothGattDescriptor descriptor = gattCharacteristic.getDescriptor(
                                    UUID.fromString(NOTIFICATION_CONFIG));
                            if (descriptor!=null) {
                                descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                                mBluetoothGatt.writeDescriptor(descriptor);
                                Log.d(TAG, "Setting escan indication");
                            } else {
                                Log.e(TAG, "Setting escan indication failed");
                            }
                        }
                        if ((characteristicProperties
                                & (BluetoothGattCharacteristic.PROPERTY_WRITE)) > 0) {
                            Log.d(TAG, "Setting escan write type default");
                            gattCharacteristic.setWriteType(
                                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                        }
                        if ((characteristicProperties
                                & (BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) > 0) {
                            Log.d(TAG, "Setting escan write type no response");
                            gattCharacteristic.setWriteType(
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                        }

                    }

                }
            }
        }
        if (escanNotifyChar == null) {
            Log.e(TAG, "findGattService found no service");
            bleState = BleState.NOT_SUPPORTED;
        }
        Log.d(TAG, "findGattService() exited");
    }


    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "Connected to GATT server.");
                bleState = BleState.CONNECTING;
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                bleState = BleState.DISCONNECTED;
                bleClose();
                Log.i(TAG, "Disconnected from GATT server.");
                if (bleHandler!=null) {
                    Message msg = bleHandler.obtainMessage();
                    Bundle b = new Bundle();
                    b.putString("message", "New data");
                    msg.setData(b);
                    bleHandler.sendMessage(msg);
                }

            } else {
                bleState = BleState.NOT_USED;
                Log.i(TAG, "Unknown connection state ");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onServicesDiscovered() with status=" + status);
            if (status == BluetoothGatt.GATT_SUCCESS && mBluetoothGatt != null) {
                findGattService(mBluetoothGatt.getServices());
            }
        }

        // For information only. We do not need to know what the previous write completed
        @Override
        public void onCharacteristicWrite(
                BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "onCharacteristicWrite() callback with status="+status+" on " +
                        "uuid="+characteristic.getUuid().toString());
            }
        }

        @Override
        // This is the callback when new data arrives
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.d(TAG, "onCharacteristicChanged callback");
            if (characteristic.getUuid().toString() == ESCAN_CHAR_NOTIFY) {
                String data = characteristic.getStringValue(0);
                ble.handleRxData(data);
                Log.d(TAG, "========= Got escan data " + data);
            } else {
                if (ble != null) {
                    ble.handleRxData(characteristic.getStringValue(0));
                } else {
                    Log.e(TAG, "The BLE connection is closed (ble=null)");
                }
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt,
                                     BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorRead callback");
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt,
                                      BluetoothGattDescriptor descriptor, int status) {
            Log.d(TAG, "onDescriptorWrite callback");
        }

        @Override
        public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
            Log.d(TAG, "onReliableWriteCompleted callback ");
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        }

    };


}