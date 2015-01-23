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

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.util.HashMap;
import java.util.Iterator;

public class Usb {

    final static String TAG = "Umbrela Client: USB";

    private UsbManager mUsbManager;
    private UsbDevice mDevice;
    private UsbDeviceConnection mConnection;
    private UsbInterface mInterface;
    private int mDeviceVersion;

    public static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";

    /* Callback Interface */
    public interface OnUsbChangeListener {
        void onUsbConnected();
    }

    public void setOnUsbChangeListener(OnUsbChangeListener l) {
        mOnUsbChangeListener = l;
    }

    private OnUsbChangeListener mOnUsbChangeListener;

    public UsbDevice getUsbDevice() {
        return mDevice;
    }

    /* Broadcast Reciever*/
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            //call method to set up device communication
                            setDevice(device);


                            if (mOnUsbChangeListener != null) {
                                mOnUsbChangeListener.onUsbConnected();
                            }
                        }
                    } else {
                        Log.d(TAG, "permission denied for device " + device);
                    }
                }
            }
        }
    };

    public BroadcastReceiver getmUsbReceiver() {
        return mUsbReceiver;
    }

    public Usb() {
    }

    public void setUsbManager(UsbManager usbManager) {
        this.mUsbManager = usbManager;
    }

    public void requestPermission(Context context, int vendorId, int productId) {
        // Setup Pending Intent
        PendingIntent permissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(Usb.ACTION_USB_PERMISSION), 0);
        UsbDevice device = getUsbDevice(vendorId, productId);

        if (device != null) {
            mUsbManager.requestPermission(device, permissionIntent);
        }
    }

    private UsbDevice getUsbDevice(int vendorId, int productId) {
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        Iterator<UsbDevice> deviceIterator = deviceList.values().iterator();

        UsbDevice device;
        while (deviceIterator.hasNext()) {
            device = deviceIterator.next();
            if (device.getVendorId() == vendorId && device.getProductId() == productId) {
                return device;
            }
        }
        return null;
    }

    public boolean release() {
        boolean isReleased = false;

        if (mConnection != null) {
            isReleased = mConnection.releaseInterface(mInterface);
            mConnection.close();
            mConnection = null;
        }
        return isReleased;
    }

    public void setDevice(UsbDevice device) {
        mDevice = device;

        // The first interface is the one we want
        mInterface = device.getInterface(0);    // todo check when changing if alternative interface is changing

        if (device != null) {
            UsbDeviceConnection connection = mUsbManager.openDevice(device);
            if (connection != null && connection.claimInterface(mInterface, true)) {
                Log.i(TAG, "open SUCCESS");
                mConnection = connection;

                // get the bcdDevice version
                byte[] rawDescriptor = mConnection.getRawDescriptors();
                mDeviceVersion = rawDescriptor[13] << 8;
                mDeviceVersion |= rawDescriptor[12];

                Log.i("USB", getDeviceInfo(device));
            } else {
                Log.e(TAG, "open FAIL");
                mConnection = null;
            }
        }
    }

    public boolean isConnected() {
        return (mConnection != null);
    }

    public String getDeviceInfo(UsbDevice device) {
        if (device == null)
            return "No device found.";

        StringBuilder sb = new StringBuilder();
        sb.append("Model: " + device.getDeviceName() + "\n");
        sb.append("ID: " + device.getDeviceId() + " (0x" + Integer.toHexString(device.getDeviceId()) + ")" + "\n");
        sb.append("Class: " + device.getDeviceClass() + "\n");
        sb.append("Subclass: " + device.getDeviceSubclass() + "\n");
        sb.append("Protocol: " + device.getDeviceProtocol() + "\n");
        sb.append("Vendor ID " + device.getVendorId() + " (0x" + Integer.toHexString(device.getVendorId()) + ")" + "\n");
        sb.append("Product ID: " + device.getProductId() + " (0x" + Integer.toHexString(device.getProductId()) + ")" + "\n");
        sb.append("Device Ver: 0x" + Integer.toHexString(mDeviceVersion) + "\n");
        sb.append("Interface count: " + device.getInterfaceCount() + "\n");

        for (int i = 0; i < device.getInterfaceCount(); i++) {

            UsbInterface usbInterface = device.getInterface(i);

            sb.append("Interface: " + usbInterface.toString() + "\n");
            sb.append("Endpoint Count: " + usbInterface.getEndpointCount() + "\n");

            for (int j = 0; j < usbInterface.getEndpointCount(); j++) {

                UsbEndpoint ep = usbInterface.getEndpoint(j);

                sb.append("Endpoint: " + ep.toString() + "\n");
            }
        }

        return sb.toString();
    }

    public int getDeviceVersion() {
        return mDeviceVersion;
    }


    /**
     * Performs a control transaction on endpoint zero for this device.
     * The direction of the transfer is determined by the request type.
     * If requestType & {@link android.hardware.usb.UsbConstants#USB_ENDPOINT_DIR_MASK} is
     * {@link android.hardware.usb.UsbConstants#USB_DIR_OUT}, then the transfer is a write,
     * and if it is {@link android.hardware.usb.UsbConstants#USB_DIR_IN}, then the transfer
     * is a read.
     *
     * @param requestType MSB selects direction, rest defines to whom request is addressed
     * @param request     DFU command ID
     * @param value       0 for commands, >0 for firmware blocks
     * @param index       often 0
     * @param buffer      buffer for data portion of transaction,
     *                    or null if no data needs to be sent or received
     * @param offset      the index of the first byte in the buffer to send or receive
     * @param length      the length of the data to send or receive
     * @param timeout     50ms f
     * @return length of data transferred (or zero) for success,
     * or negative value for failure
     */
    public int controlTransfer(int requestType, int request, int value, int index, byte[] buffer, int offset, int length, int timeout) {
        synchronized (this) {
            return mConnection.controlTransfer(requestType, request, value, index, buffer, offset, length, timeout);
        }
    }
}
