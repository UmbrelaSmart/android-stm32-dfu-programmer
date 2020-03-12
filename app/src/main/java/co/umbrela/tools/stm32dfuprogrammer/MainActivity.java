/*
 * Copyright 2015 Umbrela Smart, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package co.umbrela.tools.stm32dfuprogrammer;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements
        Handler.Callback, Usb.OnUsbChangeListener, Dfu.DfuListener {

    private final int REQUEST_CODE_EXTERNAL_STORAGE_PERMISSIONS = 0;

    private Usb usb;
    private Dfu dfu;

    private ScrollView scroll;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dfu = new Dfu(Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID);
        dfu.setListener(this);

        scroll = findViewById(R.id.scroll);
        status = findViewById(R.id.status);

        Button massErase = findViewById(R.id.btnMassErase);
        massErase.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                dfu.massErase();
            }
        });

        Button program = findViewById(R.id.btnProgram);
        program.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                dfu.program();
            }
        });

        Button fastOperations = findViewById(R.id.btnFastOperations);
        fastOperations.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                dfu.fastOperations();
            }
        });

        Button verify = findViewById(R.id.btnVerify);
        verify.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                dfu.verify();
            }
        });

        Button enterDfu = findViewById(R.id.btnEnterDFU);
        enterDfu.setOnClickListener(new Button.OnClickListener(){
            @Override
            public void onClick(View v) {
                dfu.enterDfuMode();
            }
        });

        Button leaveDfu = findViewById(R.id.btnLeaveDFU);
        leaveDfu.setOnClickListener(new Button.OnClickListener(){
            @Override
            public void onClick(View v) {
                dfu.leaveDfuMode();
            }
        });

        Button releaseReset = findViewById(R.id.btnReleaseReset);
        releaseReset.setOnClickListener(new Button.OnClickListener(){
            @Override
            public void onClick(View v) {
                dfu.enterNormalMode();
            }
        });

        // Check whether this app has read/write external storage permission; if not, request user to grant required permissions
        final int permissionRead  = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE);
        final int permissionWrite = ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (permissionRead != PackageManager.PERMISSION_GRANTED || permissionWrite != PackageManager.PERMISSION_GRANTED) {
            String[] permissions = new String[] {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            ActivityCompat.requestPermissions(MainActivity.this, permissions, REQUEST_CODE_EXTERNAL_STORAGE_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_EXTERNAL_STORAGE_PERMISSIONS) {
            boolean permissionRead = true, permissionWrite = true;
            for (int i = 0; i < permissions.length; i++) {
                final String permission = permissions[i];
                final int grantResult = grantResults[i];
                final boolean permissionGranted = grantResult == PackageManager.PERMISSION_GRANTED;
                if (permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE))
                    permissionRead = permissionGranted;
                if (permission.equals(Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    permissionWrite = permissionGranted;
            }
            if (!permissionRead || !permissionWrite) {
                Toast.makeText(getApplicationContext(), "You need to grant external storage permission for this app to work.", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();

        /* Setup USB */
        usb = new Usb(this);
        usb.setUsbManager((UsbManager) getSystemService(Context.USB_SERVICE));
        usb.setOnUsbChangeListener(this);

        // Handle two types of intents. Device attachment and permission
        registerReceiver(usb.getmUsbReceiver(), new IntentFilter(Usb.ACTION_USB_PERMISSION));
        registerReceiver(usb.getmUsbReceiver(), new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));
        registerReceiver(usb.getmUsbReceiver(), new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));

        // Handle case where USB device is connected before app launches;
        // hence ACTION_USB_DEVICE_ATTACHED will not occur so we explicitly call for permission
        usb.requestPermission(this, Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID);
    }

    @Override
    protected void onStop() {
        super.onStop();

        /* DFU */
        dfu.terminate();

        /* USB */
        dfu.setUsb(null);
        usb.release();
        try {
            unregisterReceiver(usb.getmUsbReceiver());
        } catch (IllegalArgumentException e) { /* Already unregistered */ }
    }

    @Override
    public void onStatusMsg(String msg) {
        this.runOnUiThread(() -> {
            status.append(msg + "\n");
            scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public boolean handleMessage(Message message) {
        return false;
    }

    @Override
    public void onUsbConnected() {
        final String deviceInfo = usb.getDeviceInfo(usb.getUsbDevice());
        dfu.setUsb(usb);
        this.runOnUiThread(() -> {
            scroll.post(() -> scroll.scrollTo(0, 0));
            status.setText(deviceInfo);
        });
    }
}
