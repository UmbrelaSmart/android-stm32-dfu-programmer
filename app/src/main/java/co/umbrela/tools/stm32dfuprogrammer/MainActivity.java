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

public class MainActivity extends Activity implements Handler.Callback, Usb.OnUsbChangeListener {

    /* USB DFU ID's (may differ by device) */
    public final static int USB_VENDOR_ID = 1155;   // VID while in DFU mode 0x0483
    public final static int USB_PRODUCT_ID = 57105; // PID while in DFU mode 0xDF11

    private Usb mUsb;
    private Dfu mDfu;

    private TextView mTv;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDfu = new Dfu(USB_VENDOR_ID, USB_PRODUCT_ID);
        mTv = (TextView) findViewById(R.id.my_textview);
        mDfu.setTextView(mTv);

        Button massErase = (Button) findViewById(R.id.btnMassErase);
        massErase.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDfu.massErase();
            }
        });

        Button program = (Button) findViewById(R.id.btnProgram);
        program.setOnClickListener(new Button.OnClickListener() {
            @Override
            public void onClick(View v) {
                mDfu.program();
            }
        });

        Button forceErase = (Button) findViewById(R.id.btnForceErase);
        forceErase.setOnClickListener(new Button.OnClickListener(){
            @Override
            public void onClick(View v){
              mDfu.fastOperations();
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();

        /* USB */
        mUsb = new Usb();
        mUsb.setUsbManager((UsbManager) getSystemService(Context.USB_SERVICE));

        IntentFilter filter = new IntentFilter(Usb.ACTION_USB_PERMISSION);
        registerReceiver(mUsb.getmUsbReceiver(), filter);
        mUsb.requestPermission(this, USB_VENDOR_ID, USB_PRODUCT_ID);
        mUsb.setOnUsbChangeListener(this);
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
    public boolean handleMessage(Message message) {
        return false;
    }

    @Override
    public void onUsbConnected() {

        String deviceInfo = mUsb.getDeviceInfo(mUsb.getUsbDevice());
        mTv.setText(deviceInfo);
        mDfu.setmUsb(mUsb);
        mDfu.setDeviceVersion(mUsb.getDeviceVersion());
    }
}
