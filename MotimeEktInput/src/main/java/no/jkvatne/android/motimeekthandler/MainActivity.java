package no.jkvatne.android.motimeekthandler;

import static androidx.core.content.ContextCompat.getSystemService;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.WindowManager;
import java.util.Objects;
import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    private PowerManager.WakeLock wakeLock;

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
        Log.i("ECB","MainActivity onCreate. Aquire wakeLock");
        // Create the WakeLock
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EktInput::MyWakelockTag" );
        wakeLock.acquire(10 * 60 * 1000L);  // 10 minutes
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i("ECB","MainActivity onResume");
        /*if (wakeLock != null) {
            wakeLock.acquire(120 * 60 * 1000L);  // 120 minutes
        }*/
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.i("ECB","onPause: main activity");
        // Release the WakeLock safely
        //if (wakeLock != null && wakeLock.isHeld()) {
        //    wakeLock.release();
        //}
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
                Log.i("ECB", "onNewIntent: Connecting to USB");
                terminal.connect();
            }
        }
        super.onNewIntent(intent);
    }


}
