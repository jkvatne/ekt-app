package no.jkvatne.android.motimeekthandler;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.ListFragment;

import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import java.util.ArrayList;
import java.util.Locale;

public class DevicesFragment extends ListFragment {
    View header;

    static class ListItem {
        final UsbDevice device;
        final int port;
        final UsbSerialDriver driver;

        ListItem(UsbDevice device, int port, UsbSerialDriver driver) {
            this.device = device;
            this.port = port;
            this.driver = driver;
        }
    }

    private final ArrayList<ListItem> listItems = new ArrayList<>();
    private ArrayAdapter<ListItem> listAdapter;
    private int baudRate = 115200;
    private boolean withIoManager = true;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        listAdapter = new ArrayAdapter<ListItem>(requireActivity(), 0, listItems) {
            @SuppressLint("SetTextI18n")
            @NonNull
            @Override
            public View getView(int position, View view, @NonNull ViewGroup parent) {
                ListItem item = listItems.get(position);
                if (view == null)
                    view = requireActivity().getLayoutInflater().inflate(R.layout.device_list_item, parent, false);
                TextView text1 = view.findViewById(R.id.text1);
                TextView text2 = view.findViewById(R.id.text2);
                if(item.driver == null) {
                    text1.setText(R.string.no_driver);

                } else if(item.driver.getPorts().size() == 1) {
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", ""));
                } else {
                    text1.setText(item.driver.getClass().getSimpleName().replace("SerialDriver", "") + ", Port " + item.port);
                }
                if (item.device.getVendorId()==8263) {
                    text1.setText("Emit eScan");
                }
                text2.setText(String.format(Locale.US, "Vendor %04X, Product %04X", item.device.getVendorId(), item.device.getProductId()));
                return view;
            }
        };
    }

    @Override
    public void onViewCreated(View v, Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        setListAdapter(null);
        View header = requireActivity().getLayoutInflater().inflate(R.layout.device_list_header,
                null,
                false);
        getListView().addHeaderView(header, null, false);
        setEmptyText("<Sett inn USB kabel>");
        ((TextView) getListView().getEmptyView()).setTextSize(24);
        setListAdapter(listAdapter);
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_devices, menu);
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if(id == R.id.refresh) {
            refresh();
            return true;
        } else if (id ==R.id.baud_rate) {
            final String[] values = getResources().getStringArray(R.array.baud_rates);
            int pos = java.util.Arrays.asList(values).indexOf(String.valueOf(baudRate));
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Baud rate");
            builder.setSingleChoiceItems(values, pos, (dialog, which) -> {
                baudRate = Integer.parseInt(values[which]);
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id ==R.id.read_mode) {
            final String[] values = getResources().getStringArray(R.array.read_modes);
            int pos = withIoManager ? 0 : 1; // read_modes[0]=event/io-manager, read_modes[1]=direct
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Read mode");
            builder.setSingleChoiceItems(values, pos, (dialog, which) -> {
                withIoManager = (which == 0);
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    void refresh() {

        UsbManager usbManager =
                (UsbManager) requireActivity().getSystemService(Context.USB_SERVICE);
        UsbSerialProber usbDefaultProber = UsbSerialProber.getDefaultProber();
        UsbSerialProber usbCustomProber = CustomProber.getCustomProber();
        listItems.clear();
        for(UsbDevice device : usbManager.getDeviceList().values()) {
            UsbSerialDriver driver = usbDefaultProber.probeDevice(device);
            if(driver == null) {
                driver = usbCustomProber.probeDevice(device);
            }
            if (driver == null) {
                // Probe for our custom FTDI device
                ProbeTable customTable = new ProbeTable();
                customTable.addProduct(0x2047, 0x0300, CdcAcmSerialDriver.class);
                UsbSerialProber prober = new UsbSerialProber(customTable);
                driver = prober.probeDevice(device);
            }
            if(driver != null) {
                for(int port = 0; port < driver.getPorts().size(); port++)
                    listItems.add(new ListItem(device, port, driver));
            } else {
                listItems.add(new ListItem(device, 0, null));
            }
        }
        if (!listItems.isEmpty()) {
            // Automaticaly start application when usb device is detected
            startup();

        }
        listAdapter.notifyDataSetChanged();
    }

    private void startup() {
        String name;
        Bundle args = new Bundle();
        ListItem item = listItems.get(0);
        args.putInt("device", item.device.getDeviceId());
        args.putInt("port", item.port);
        args.putInt("baud", baudRate);
        name = item.device.getProductName();
        args.putString("name", name);
        args.putBoolean("withIoManager", withIoManager);
        Fragment fragment = new TerminalFragment();
        fragment.setArguments(args);
        getParentFragmentManager().beginTransaction().replace(R.id.fragment, fragment,
                "terminal").addToBackStack(null).commit();
    }

    @Override
    public void onListItemClick(@NonNull ListView l, @NonNull View v, int position, long id) {
        ListItem item = listItems.get(position-1);
        if(item.driver == null) {
            Toast.makeText(getActivity(), "no driver", Toast.LENGTH_SHORT).show();
        } else {
            startup();
        }
    }

}
