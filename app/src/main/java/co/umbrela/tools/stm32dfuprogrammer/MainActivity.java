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

import android.app.Activity;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity implements
        Handler.Callback, Usb.OnUsbChangeListener, Dfu.DfuListener {

    private Usb mUsb;
    private Dfu mDfu;

    private TextView mTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDfu = new Dfu(Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID);
        mDfu.setListener(this);

        mTv = findViewById(R.id.my_textview);

        Button massErase = findViewById(R.id.btnMassErase);
        massErase.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDfu.massErase();
            }
        });

        Button program = findViewById(R.id.btnProgram);
        program.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDfu.program();
            }
        });

        Button forceErase = findViewById(R.id.btnForceErase);
        forceErase.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDfu.fastOperations();
            }
        });

        Button verify = findViewById(R.id.btnVerify);
        verify.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDfu.verify();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        /* Setup USB */
        mUsb = new Usb(this);
        mUsb.setUsbManager((UsbManager) getSystemService(Context.USB_SERVICE));
        mUsb.setOnUsbChangeListener(this);

        // Handle two types of intents. Device attachment and permission
        registerReceiver(mUsb.getmUsbReceiver(), new IntentFilter(Usb.ACTION_USB_PERMISSION));
        registerReceiver(mUsb.getmUsbReceiver(), new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED));

        // Handle case where USB device is connected before app launches;
        // hence ACTION_USB_DEVICE_ATTACHED will not occur so we explicitly call for permission
        mUsb.requestPermission(this, Usb.USB_VENDOR_ID, Usb.USB_PRODUCT_ID);
    }

    @Override
    protected void onStop() {
        super.onStop();

        /* USB */
        mDfu.setmUsb(null);
        mUsb.release();
        try {
            unregisterReceiver(mUsb.getmUsbReceiver());
        } catch (IllegalArgumentException e) { /* Already unregistered */ }
    }

    @Override
    public void onStatusMsg(String msg) {
        mTv.append(msg);
    }

    @Override
    public boolean handleMessage(Message message) {
        return false;
    }

    @Override
    public void onUsbConnected() {
        final String deviceInfo = mUsb.getDeviceInfo(mUsb.getUsbDevice());
        mTv.setText(deviceInfo);
        mDfu.setmUsb(mUsb);
        mDfu.setDeviceVersion(mUsb.getDeviceVersion());
    }
}
