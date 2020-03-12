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

import android.nfc.FormatException;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@SuppressWarnings("unused")
public class Dfu {

    private static final String TAG = "Umbrela STM32 DFU Programmer: DFU";

    private final static int USB_DIR_OUT = 0;
    private final static int USB_DIR_IN = 128;       //0x80
    private final static int DFU_RequestType = 0x21;  // '2' => Class request ; '1' => to interface

    private final static int STATE_IDLE = 0x00;
    private final static int STATE_DETACH = 0x01;
    private final static int STATE_DFU_IDLE = 0x02;
    private final static int STATE_DFU_DOWNLOAD_SYNC = 0x03;
    private final static int STATE_DFU_DOWNLOAD_BUSY = 0x04;
    private final static int STATE_DFU_DOWNLOAD_IDLE = 0x05;
    private final static int STATE_DFU_MANIFEST_SYNC = 0x06;
    private final static int STATE_DFU_MANIFEST = 0x07;
    private final static int STATE_DFU_MANIFEST_WAIT_RESET = 0x08;
    private final static int STATE_DFU_UPLOAD_IDLE = 0x09;
    private final static int STATE_DFU_ERROR = 0x0A;
    private final static int STATE_DFU_UPLOAD_SYNC = 0x91;
    private final static int STATE_DFU_UPLOAD_BUSY = 0x92;

    // DFU Commands, request ID code when using controlTransfers
    private final static int DFU_DETACH = 0x00;
    private final static int DFU_DNLOAD = 0x01;
    private final static int DFU_UPLOAD = 0x02;
    private final static int DFU_GETSTATUS = 0x03;
    private final static int DFU_CLRSTATUS = 0x04;
    private final static int DFU_GETSTATE = 0x05;
    private final static int DFU_ABORT = 0x06;

    public final static int ELEMENT1_OFFSET = 293;  // constant offset in file array where image data starts
    public final static int TARGET_NAME_START = 22;
    public final static int TARGET_NAME_MAX_END = 276;
    public final static int TARGET_SIZE = 277;
    public final static int TARGET_NUM_ELEMENTS = 281;

    // Device specific parameters
    public static final String mInternalFlashString = "@Internal Flash  /0x08000000/04*016Kg,01*064Kg,07*128Kg"; // STM32F405RG, 1MB Flash, 192KB SRAM
    public static final int mInternalFlashSize = 1048575;
    public static final int mInternalFlashStartAddress = 0x08000000;
    public static final int mOptionByteStartAddress = 0x1FFFC000;
    private static final int OPT_BOR_1 = 0x08;
    private static final int OPT_BOR_2 = 0x04;
    private static final int OPT_BOR_3 = 0x00;
    private static final int OPT_BOR_OFF = 0x0C;
    private static final int OPT_WDG_SW = 0x20;
    private static final int OPT_nRST_STOP = 0x40;
    private static final int OPT_nRST_STDBY = 0x80;
    private static final int OPT_RDP_OFF = 0xAA00;
    private static final int OPT_RDP_1 = 0x3300;

    private final int deviceVid;
    private final int devicePid;
    private final DfuFile dfuFile;

    private Usb usb;
    private int deviceVersion;  // STM bootloader version

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final List<DfuListener> listeners = new ArrayList<>();

    public interface DfuListener {
        void onStatusMsg(String msg);
    }

    public Dfu(int usbVendorId, int usbProductId) {
        this.deviceVid = usbVendorId;
        this.devicePid = usbProductId;

        dfuFile = new DfuFile();
    }

    private void onStatusMsg(final String msg) {
        for (DfuListener listener : listeners) {
            listener.onStatusMsg(msg);
        }
    }

    public void setListener(final DfuListener listener) {
        if (listener == null) throw new IllegalArgumentException("Listener is null");
        listeners.add(listener);
    }

    public void setUsb(Usb usb) {
        this.usb = usb;
        if (this.usb != null) {
            this.deviceVersion = this.usb.getDeviceVersion();
        }
    }

    public void terminate() {
        try {
            pool.awaitTermination(10, TimeUnit.SECONDS);
            pool.shutdownNow();
            listeners.clear();
        } catch (InterruptedException e) {
            e.printStackTrace();
            onStatusMsg(e.toString());
        }
    }

    /* One-Click Programming Method to fully flash the connected device
         This will try everything that it can do to program, if it throws exceptions
         it failed on something it cannot fix.
    */
    public boolean programFirmware(String filePath) throws Exception {

        final int MAX_ALLOWED_RETRIES = 5;

        openFile(filePath);
        verifyFile();
        checkCompatibility();

        if (isDeviceProtected()) {
            Log.i(TAG, "Device is protected");
            Log.i(TAG, "Removing Read Protection");
            removeReadProtection();
            Log.i(TAG, "Device is resetting");
            return false;       // device will reset
        }
        for (int i = MAX_ALLOWED_RETRIES + 1; i > 0; i--) {
            if (isDeviceBlank()) {
                break;
            }
            if (i == 1) {
                throw new Exception("Cannot Mass Erase, REPLACE UNIT!");
            }
            Log.i(TAG, "Device not blank, erasing");
            massErase();
        }
        writeImage();
        for (int i = MAX_ALLOWED_RETRIES + 1; i > 0; i--) {
            if (isWrittenImageOk()) {
                Log.i(TAG, "Writing Option Bytes, will self-reset");
                int selectOptions = OPT_RDP_OFF | OPT_WDG_SW | OPT_nRST_STOP | OPT_nRST_STDBY | OPT_BOR_1;  // todo in production, OPT_RDP_1 must be set instead of OPT_RDP_OFF
                writeOptionBytes(selectOptions);   // will reset device
                break;
            }
            if (i == 1) {
                throw new Exception("Cannot Write successfully, REPLACE UNIT!");
            }
            Log.i(TAG, "Verification failed, retry");
            massErase();
            writeImage();
        }

        return true;
    }

    private boolean isDeviceBlank() throws Exception {

        byte[] readContent = new byte[dfuFile.elementLength];
        readImage(readContent);
        ByteBuffer read = ByteBuffer.wrap(readContent);    // wrap whole array
        int hash = read.hashCode();
        return (dfuFile.elementLength == Math.abs(hash));
    }

    // similar to verify()
    private boolean isWrittenImageOk() throws Exception {

        byte[] deviceFirmware = new byte[dfuFile.elementLength];
        final long startTime = System.currentTimeMillis();
        readImage(deviceFirmware);
        // create byte buffer and compare content
        ByteBuffer fileFw = ByteBuffer.wrap(dfuFile.file, ELEMENT1_OFFSET, dfuFile.elementLength);    // set offset and limit of firmware
        ByteBuffer deviceFw = ByteBuffer.wrap(deviceFirmware);    // wrap whole array
        boolean result = fileFw.equals(deviceFw);
        Log.i(TAG, "Verified completed in " + (System.currentTimeMillis() - startTime) + " ms");
        return result;
    }

    public void massErase() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {

                if (isDeviceProtected()) {
                    removeReadProtection();
                    onStatusMsg("Read Protection removed. Device resets...Wait until it re-enumerates"); // XXX This will reset the device
                    return;
                }

                onStatusMsg("Erasing...");

                final long startTime = System.currentTimeMillis();  // note current time

                massEraseCommand();  // send erase command request

                DfuStatus dfuStatus = new DfuStatus();
                getStatus(dfuStatus);  // initiate erase command, returns 'download busy' even if invalid address or ROP

                final int pollingTime = dfuStatus.bwPollTimeout;  // note requested waiting time
                do {
                    /* wait specified time before next getStatus call */
                    Thread.sleep(pollingTime);
                    clearStatus();
                    getStatus(dfuStatus);
                } while (dfuStatus.bState != STATE_DFU_IDLE);

                onStatusMsg("Mass erase completed in " + (System.currentTimeMillis() - startTime) + " ms");

            } catch (InterruptedException e) {
                e.printStackTrace();
                onStatusMsg("Erasing process has been interrupted, please launch the operation again");
            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    public void fastOperations() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {

                if (isDeviceProtected()) {
                    onStatusMsg("Device is Read-Protected...First Mass Erase");
                    return;
                }

                final byte[] configBytes = new byte[4];
                readDeviceFeature(configBytes);

                if (configBytes[0] != 0x03) {
                    configBytes[0] = 0x03;

                    onStatusMsg("Setting...");

                    download(configBytes, 2);

                    DfuStatus dfuStatus = new DfuStatus();
                    getStatus(dfuStatus);

                    getStatus(dfuStatus);
                    while (dfuStatus.bState != STATE_DFU_IDLE) {
                        clearStatus();
                        getStatus(dfuStatus);
                    }

                    onStatusMsg("Fast Operations set (Parallelism x32)");
                } else {
                    onStatusMsg("Fast Operations was already set (Parallelism x32)");
                }

            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    public void program() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {

                if (isDeviceProtected()) {
                    onStatusMsg("Device is Read-Protected...First Mass Erase");
                    return;
                }

                openFile();
                verifyFile();
                checkCompatibility();
                onStatusMsg("File Path: " + dfuFile.filePath);
                onStatusMsg("File Size: " + dfuFile.file.length + " Bytes");
                onStatusMsg("Element Size: " + dfuFile.elementLength + " Bytes");
                onStatusMsg("Element Address: 0x" + Integer.toHexString(dfuFile.elementStartAddress));
                onStatusMsg("Start writing file in blocks of " + dfuFile.maxBlockSize + " Bytes");

                onStatusMsg("Programming...");

                final long startTime = System.currentTimeMillis();

                writeImage();

                onStatusMsg("Programming completed in " + (System.currentTimeMillis() - startTime) + " ms");

            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    public void verify() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {

                if (isDeviceProtected()) {
                    onStatusMsg("Device is Read-Protected...First Mass Erase");
                    return;
                }

                onStatusMsg("Verifying...");

                if (dfuFile.filePath == null) {
                    openFile();
                    verifyFile();
                    checkCompatibility();
                }

                byte[] deviceFirmware = new byte[dfuFile.elementLength];
                readImage(deviceFirmware);

                // create byte buffer and compare content
                ByteBuffer fileFw = ByteBuffer.wrap(dfuFile.file, ELEMENT1_OFFSET, dfuFile.elementLength);    // set offset and limit of firmware
                ByteBuffer deviceFw = ByteBuffer.wrap(deviceFirmware);    // wrap whole array

                if (fileFw.equals(deviceFw)) {        // compares type, length, content
                    onStatusMsg("Device firmware equals file firmware");
                } else {
                    onStatusMsg("Device firmware does not equal file firmware");
                }

            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    public void enterNormalMode() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                final boolean success = Outputs.enterNormalMode();
                final String message = success
                    ? "Successfully entered normal mode"
                    : "Could not enter normal mode";
                onStatusMsg(message);
            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    public void enterDfuMode() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                final boolean success = Outputs.enterDfuMode();
                final String message = success
                    ? "Successfully entered DFU mode"
                    : "Could not enter DFU mode";
                onStatusMsg(message);
            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    public void leaveDfuMode() {

        if (!isUsbConnected()) return;

        pool.submit(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            try {
                //final boolean success = Outputs.leaveDfuMode();
                final boolean success = detach(mInternalFlashStartAddress);
                final String message = success
                    ? "Successfully leaved DFU mode"
                    : "Could not leave DFU mode";
                onStatusMsg(message);
            } catch (Exception e) {
                e.printStackTrace();
                onStatusMsg(e.toString());
            }
        });
    }

    // check if usb device is active
    private boolean isUsbConnected() {
        if (usb != null && usb.isConnected()) {
            return true;
        }
        onStatusMsg("No device connected");
        return false;
    }

    private void removeReadProtection() throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        unProtectCommand();

        getStatus(dfuStatus);
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("Failed to execute unprotect command");
        }

        usb.release();     // XXX device will self-reset
        Log.i(TAG, "USB was released");
    }

    private void readDeviceFeature(byte[] configBytes) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(0xFFFF0000);
        getStatus(dfuStatus);

        getStatus(dfuStatus);
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Fast Operations not supported");
        }

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }

        upload(configBytes, configBytes.length, 2);
        getStatus(dfuStatus);

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
    }

    private void writeImage() throws Exception {

        int address = dfuFile.elementStartAddress;  // flash start address
        int fileOffset = ELEMENT1_OFFSET;   // index offset of file
        int blockSize = dfuFile.maxBlockSize;   // max block size
        byte[] Block = new byte[blockSize];
        int NumOfBlocks = dfuFile.elementLength / blockSize;
        int blockNum;

        for (blockNum = 0; blockNum < NumOfBlocks; blockNum++) {
            System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, blockSize);
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
        // check if last block is partial
        int remainder = dfuFile.elementLength - (blockNum * blockSize);
        if (remainder > 0) {
            System.arraycopy(dfuFile.file, (blockNum * blockSize) + fileOffset, Block, 0, remainder);
            // Pad with 0xFF so our CRC matches the ST Bootloader and the ULink's CRC
            while (remainder < Block.length) {
                Block[remainder++] = (byte) 0xFF;
            }
            // send out the block to device
            writeBlock(address, Block, blockNum);
        }
    }


    private void readImage(byte[] deviceFw) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        int maxBlockSize = dfuFile.maxBlockSize;
        int startAddress = dfuFile.elementStartAddress;
        byte[] block = new byte[maxBlockSize];
        int nBlock;
        int remLength = deviceFw.length;
        int numOfBlocks = remLength / maxBlockSize;

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(startAddress);
        getStatus(dfuStatus);   // to execute
        getStatus(dfuStatus);   // to verify
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Start address not supported");
        }


        // read full and last partial blocks (NOTE: last partial block will be read with maxBlockSize)
        for (nBlock = 0; nBlock <= numOfBlocks; nBlock++) {

            while (dfuStatus.bState != STATE_DFU_IDLE) {        // todo if fails, maybe stop reading
                clearStatus();
                getStatus(dfuStatus);
            }
            upload(block, maxBlockSize, nBlock + 2);
            getStatus(dfuStatus);

            if (remLength >= maxBlockSize) {
                remLength -= maxBlockSize;
                System.arraycopy(block, 0, deviceFw, (nBlock * maxBlockSize), maxBlockSize);
            } else {
                System.arraycopy(block, 0, deviceFw, (nBlock * maxBlockSize), remLength);
            }
        }
    }

    // this can be used if the filePath is known to .dfu file
    private void openFile(String filePath) throws Exception {

        if (filePath == null) {
            throw new FileNotFoundException("No file selected");
        }
        File myFile = new File(filePath);
        if (!myFile.exists()) {
            throw new FileNotFoundException("Cannot find: " + myFile.toString());
        }
        if (!myFile.canRead()) {
            throw new FormatException("Cannot open: " + myFile.toString());
        }
        dfuFile.filePath = myFile.toString();
        dfuFile.file = new byte[(int) myFile.length()];
        //convert file into byte array
        FileInputStream fileInputStream = new FileInputStream(myFile);
        int readLength = fileInputStream.read(dfuFile.file);
        fileInputStream.close();
        if (readLength != myFile.length()) {
            throw new IOException("Could Not Read File");
        }
    }

    private void openFile() throws Exception {

        File extDownload;
        String myFilePath = null;
        String myFileName = null;
        FileInputStream fileInputStream;
        File myFile;

        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED))
        {
            extDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (extDownload.exists()) {
                String[] files = extDownload.list();
                // todo support multiple dfu files in dir
                if (files != null && files.length > 0) { // will select first dfu file found in dir
                    for (String file : files) {
                        if (file.endsWith(".dfu")) {
                            myFilePath = extDownload.toString();
                            myFileName = file;
                            break;
                        }
                    }
                }
            }
        }
        if (myFileName == null) throw new Exception("No .dfu file found in Download folder");

        myFile = new File(myFilePath + "/" + myFileName);
        dfuFile.filePath = myFile.toString();
        dfuFile.file = new byte[(int) myFile.length()];

        //convert file into byte array
        fileInputStream = new FileInputStream(myFile);
        fileInputStream.read(dfuFile.file);
        fileInputStream.close();
    }

    private void verifyFile() throws Exception {

        // todo for now one expect the file not to be corrupted

        int length = dfuFile.file.length;

        int crcIndex = length - 4;
        int crc = 0;
        crc |= dfuFile.file[crcIndex++] & 0xFF;
        crc |= (dfuFile.file[crcIndex++] & 0xFF) << 8;
        crc |= (dfuFile.file[crcIndex++] & 0xFF) << 16;
        crc |= (dfuFile.file[crcIndex] & 0xFF) << 24;
        // do crc check
        if (crc != calculateCRC(dfuFile.file)) {
            throw new FormatException("CRC Failed");
        }

        // Check the prefix
        String prefix = new String(dfuFile.file, 0, 5);
        if (prefix.compareTo("DfuSe") != 0) {
            throw new FormatException("File signature error");
        }

        // check dfuSe Version
        if (dfuFile.file[5] != 1) {
            throw new FormatException("DFU file version must be 1");
        }

        // Check the suffix
        String suffix = new String(dfuFile.file, length - 8, 3);
        if (suffix.compareTo("UFD") != 0) {
            throw new FormatException("File suffix error");
        }
        if ((dfuFile.file[length - 5] != 16) || (dfuFile.file[length - 10] != 0x1A) || (dfuFile.file[length - 9] != 0x01)) {
            throw new FormatException("File number error");
        }

        // Now check the target prefix, we assume there is only one target in the file
        String target = new String(dfuFile.file, 11, 6);
        if (target.compareTo("Target") != 0) {
            throw new FormatException("Target signature error");
        }

        if (0 != dfuFile.file[TARGET_NAME_START]) {
            String tempName = new String(dfuFile.file, TARGET_NAME_START, TARGET_NAME_MAX_END);
            int foundNullAt = tempName.indexOf(0);
            dfuFile.TargetName = tempName.substring(0, foundNullAt);
        } else {
            throw new FormatException("No Target Name Exist in File");
        }
        Log.i(TAG, "Firmware Target Name: " + dfuFile.TargetName);

        dfuFile.TargetSize = dfuFile.file[TARGET_SIZE] & 0xFF;
        dfuFile.TargetSize |= (dfuFile.file[TARGET_SIZE + 1] & 0xFF) << 8;
        dfuFile.TargetSize |= (dfuFile.file[TARGET_SIZE + 2] & 0xFF) << 16;
        dfuFile.TargetSize |= (dfuFile.file[TARGET_SIZE + 3] & 0xFF) << 24;
        Log.i(TAG, "Firmware Target Size: " + dfuFile.TargetSize);

        dfuFile.NumElements = dfuFile.file[TARGET_NUM_ELEMENTS] & 0xFF;
        dfuFile.NumElements |= (dfuFile.file[TARGET_NUM_ELEMENTS + 1] & 0xFF) << 8;
        dfuFile.NumElements |= (dfuFile.file[TARGET_NUM_ELEMENTS + 2] & 0xFF) << 16;
        dfuFile.NumElements |= (dfuFile.file[TARGET_NUM_ELEMENTS + 3] & 0xFF) << 24;
        Log.i(TAG, "Firmware Num of Elements: " + dfuFile.NumElements);

        if (dfuFile.NumElements > 1) {
            throw new FormatException("Do not support multiple Elements inside Image");
            /*  If you get this error, that means that the C-compiler IDE is treating the Reset Vector ISR
                and the data ( your code) as two separate elements.
                This problem has been observed with The Atollic TrueStudio V5.5.2
                The version of Atollic that works with this is v5.3.0
                The version of DfuSe FileManager is v3.0.3
                Refer to ST document UM0391 for more details on DfuSe format
             */
        }

        // Get Element Flash start address and size
        dfuFile.elementStartAddress = dfuFile.file[285] & 0xFF;
        dfuFile.elementStartAddress |= (dfuFile.file[286] & 0xFF) << 8;
        dfuFile.elementStartAddress |= (dfuFile.file[287] & 0xFF) << 16;
        dfuFile.elementStartAddress |= (dfuFile.file[288] & 0xFF) << 24;

        dfuFile.elementLength = dfuFile.file[289] & 0xFF;
        dfuFile.elementLength |= (dfuFile.file[290] & 0xFF) << 8;
        dfuFile.elementLength |= (dfuFile.file[291] & 0xFF) << 16;
        dfuFile.elementLength |= (dfuFile.file[292] & 0xFF) << 24;

        if (dfuFile.elementLength < 512) {
            throw new FormatException("Element Size is too small");
        }

        // Get VID, PID and version number
        dfuFile.VID = (dfuFile.file[length - 11] & 0xFF) << 8;
        dfuFile.VID |= (dfuFile.file[length - 12] & 0xFF);
        dfuFile.PID = (dfuFile.file[length - 13] & 0xFF) << 8;
        dfuFile.PID |= (dfuFile.file[length - 14] & 0xFF);
        dfuFile.BootVersion = (dfuFile.file[length - 15] & 0xFF) << 8;
        dfuFile.BootVersion |= (dfuFile.file[length - 16] & 0xFF);
    }

    private void checkCompatibility() throws Exception {

        if ((devicePid != dfuFile.PID) || (deviceVid != dfuFile.VID)) {
            throw new FormatException("PID/VID mismatch");
        }

        deviceVersion = usb.getDeviceVersion();

        // give warning and continue on
        if (deviceVersion != dfuFile.BootVersion) {
            onStatusMsg("Warning: Device Boot Version = 0x" + Integer.toHexString(deviceVersion) + ", " +
                        "File Boot Version = 0x" + Integer.toHexString(dfuFile.BootVersion));
        }

        if (dfuFile.elementStartAddress != mInternalFlashStartAddress) { // todo: this will fail with images for other memory sections, other than Internal Flash
            throw new FormatException("Firmware does not start at beginning of internal flash");
        }

        /*if (deviceSizeLimit() < 0) {
            throw new Exception("Error: Could Not Retrieve Internal Flash String");
        }*/

        if ((dfuFile.elementStartAddress + dfuFile.elementLength) >=
                (mInternalFlashStartAddress + mInternalFlashSize)) {
            throw new FormatException("Firmware image too large for target");
        }

        switch (deviceVersion) {
            case 0x011A:
            case 0x0200:
                dfuFile.maxBlockSize = 1024;
                break;
            case 0x2100:
            case 0x2200:
                dfuFile.maxBlockSize = 2048;
                break;
            default:
                throw new Exception("Error: Unsupported bootloader version");
        }
        Log.i(TAG, "Firmware OK and compatible");

    }

    // todo this is limited to stm32f405RG and will fail for other future chips.
    private int deviceSizeLimit() {   // retrieves and compares the Internal Flash Memory Size  and compares to constant string

        int bmRequest = 0x80;       // IN, standard request to usb device
        byte bRequest = (byte) 0x06; // USB_REQ_GET_DESCRIPTOR
        byte wLength = (byte) 127;   // max string size
        byte[] descriptor = new byte[wLength];

        /* This method can be used to retrieve any memory location size by incrementing the wValue in the defined range.
            ie. Size of: Internal Flash,  Option Bytes, OTP Size, and Feature location
         */
        int wValue = 0x0304;        // possible strings range from 0x304-0x307

        int len = usb.controlTransfer(bmRequest, bRequest, wValue, 0, descriptor, wLength, 500);
        if (len < 0) {
            return -1;
        }
        String decoded = new String(descriptor, Charset.forName("UTF-16LE"));
        if (decoded.contains(mInternalFlashString)) {
            return mInternalFlashSize; // size of stm32f405RG
        } else {
            return -1;
        }
    }


    private void writeBlock(int address, byte[] block, int blockNumber) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        if (0 == blockNumber) {
            setAddressPointer(address);
            getStatus(dfuStatus);
            getStatus(dfuStatus);
            if (dfuStatus.bState == STATE_DFU_ERROR) {
                throw new Exception("Start address not supported");
            }
        }

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        download(block, (blockNumber + 2));
        getStatus(dfuStatus);   // to execute
        if (dfuStatus.bState != STATE_DFU_DOWNLOAD_BUSY) {
            throw new Exception("Error when downloading, was not busy");
        }
        getStatus(dfuStatus);   // to verify action
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Error when downloading, did not perform action");
        }

        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
    }

    private boolean detach(int address) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        getStatus(dfuStatus);
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
        // Set the command pointer to the new application base address
        setAddressPointer(address);
        getStatus(dfuStatus);
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
        // Issue the DFU detach command
        detachDfu();
        try {
            getStatus(dfuStatus);
            if (dfuStatus.bState != STATE_DFU_MANIFEST) {
                return false;
            }
            try {
                getStatus(dfuStatus);
                return dfuStatus.bState != STATE_DFU_ERROR;
            } catch (Exception e) {
                // if caught, ignore since device might have disconnected already
                return true;
            }
        } catch (Exception e) {
            // if caught, ignore since device might have disconnected already
            return true;
        }
    }

    private boolean isDeviceProtected() throws Exception {

        DfuStatus dfuStatus = new DfuStatus();
        boolean isProtected = false;

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(mInternalFlashStartAddress);
        getStatus(dfuStatus); // to execute
        getStatus(dfuStatus); // to verify

        if (dfuStatus.bState == STATE_DFU_ERROR) {
            isProtected = true;
        }
        while (dfuStatus.bState != STATE_DFU_IDLE) {
            clearStatus();
            getStatus(dfuStatus);
        }
        return isProtected;
    }

    public void writeOptionBytes(int options) throws Exception {

        DfuStatus dfuStatus = new DfuStatus();

        do {
            clearStatus();
            getStatus(dfuStatus);
        } while (dfuStatus.bState != STATE_DFU_IDLE);

        setAddressPointer(mOptionByteStartAddress);
        getStatus(dfuStatus);
        getStatus(dfuStatus);
        if (dfuStatus.bState == STATE_DFU_ERROR) {
            throw new Exception("Option Byte Start address not supported");
        }

        Log.i(TAG, "Writing options: 0x" + Integer.toHexString(options));

        byte[] buffer = new byte[2];
        buffer[0] = (byte) (options & 0xFF);
        buffer[1] = (byte) ((options >> 8) & 0xFF);
        download(buffer);
        getStatus(dfuStatus);       // device will reset
    }

    private void massEraseCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = 0x41;
        download(buffer);
    }

    private void unProtectCommand() throws Exception {
        byte[] buffer = new byte[1];
        buffer[0] = (byte) 0x92;
        download(buffer);
    }

    private void setAddressPointer(int Address) throws Exception {
        byte[] buffer = new byte[5];
        buffer[0] = 0x21;
        buffer[1] = (byte) (Address & 0xFF);
        buffer[2] = (byte) ((Address >> 8) & 0xFF);
        buffer[3] = (byte) ((Address >> 16) & 0xFF);
        buffer[4] = (byte) ((Address >> 24) & 0xFF);
        download(buffer);
    }

    private void getStatus(DfuStatus status) throws Exception {
        byte[] buffer = new byte[6];
        int length = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_GETSTATUS, 0, 0, buffer, 6, 500);

        if (length < 0) {
            throw new Exception("USB failed during get status");
        }
        status.bStatus = buffer[0]; // state during request
        status.bState = buffer[4]; // state after request
        status.bwPollTimeout = (buffer[3] & 0xFF) << 16;
        status.bwPollTimeout |= (buffer[2] & 0xFF) << 8;
        status.bwPollTimeout |= (buffer[1] & 0xFF);
    }

    private void clearStatus() throws Exception {
        int length = usb.controlTransfer(DFU_RequestType, DFU_CLRSTATUS, 0, 0, null, 0, 0);
        if (length < 0) {
            throw new Exception("USB failed during clear status");
        }
    }

    private void detachDfu() throws Exception {
        //int len = usb.controlTransfer(DFU_RequestType, DFU_DETACH, 0, 0, null, 0, 0); // cf. AN3156 Rev 11 $2 & $5.5
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, null, 0, 0);
        if (len < 0) {
            throw new Exception("USB failed during detach");
        }
    }

    // use for commands
    private void download(byte[] data) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, 0, 0, data, data.length, 50);
        if (len < 0) {
            throw new Exception("USB failed during command download");
        }
    }

    // use for firmware download
    private void download(byte[] data, int nBlock) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType, DFU_DNLOAD, nBlock, 0, data, data.length, 0);
        if (len < 0) {
            throw new Exception("USB failed during firmware download");
        }
    }

    private void upload(byte[] data, int length, int blockNum) throws Exception {
        int len = usb.controlTransfer(DFU_RequestType | USB_DIR_IN, DFU_UPLOAD, blockNum, 0, data, length, 100);
        if (len < 0) {
            throw new Exception("USB failed during upload");
        }
    }

    private static int calculateCRC(byte[] FileData) {
        int crc = -1;
        for (int i = 0; i < FileData.length - 4; i++) {
            crc = CRC_TABLE[(crc ^ FileData[i]) & 0xff] ^ (crc >>> 8);
        }
        return crc;
    }

    // stores the result of a GetStatus DFU request
    private class DfuStatus {
        byte bStatus;       // state during request
        int bwPollTimeout;  // minimum time in ms before next getStatus call should be made
        byte bState;        // state after request
    }

    // holds all essential information for the Dfu File
    private class DfuFile {
        String filePath;
        byte[] file;
        int PID;
        int VID;
        int BootVersion;
        int maxBlockSize = 1024;

        int elementStartAddress;
        int elementLength;

        String TargetName;
        int TargetSize;
        int NumElements;
    }

    private final static int[] CRC_TABLE = {
            0x00000000, 0x77073096, 0xee0e612c, 0x990951ba, 0x076dc419, 0x706af48f,
            0xe963a535, 0x9e6495a3, 0x0edb8832, 0x79dcb8a4, 0xe0d5e91e, 0x97d2d988,
            0x09b64c2b, 0x7eb17cbd, 0xe7b82d07, 0x90bf1d91, 0x1db71064, 0x6ab020f2,
            0xf3b97148, 0x84be41de, 0x1adad47d, 0x6ddde4eb, 0xf4d4b551, 0x83d385c7,
            0x136c9856, 0x646ba8c0, 0xfd62f97a, 0x8a65c9ec, 0x14015c4f, 0x63066cd9,
            0xfa0f3d63, 0x8d080df5, 0x3b6e20c8, 0x4c69105e, 0xd56041e4, 0xa2677172,
            0x3c03e4d1, 0x4b04d447, 0xd20d85fd, 0xa50ab56b, 0x35b5a8fa, 0x42b2986c,
            0xdbbbc9d6, 0xacbcf940, 0x32d86ce3, 0x45df5c75, 0xdcd60dcf, 0xabd13d59,
            0x26d930ac, 0x51de003a, 0xc8d75180, 0xbfd06116, 0x21b4f4b5, 0x56b3c423,
            0xcfba9599, 0xb8bda50f, 0x2802b89e, 0x5f058808, 0xc60cd9b2, 0xb10be924,
            0x2f6f7c87, 0x58684c11, 0xc1611dab, 0xb6662d3d, 0x76dc4190, 0x01db7106,
            0x98d220bc, 0xefd5102a, 0x71b18589, 0x06b6b51f, 0x9fbfe4a5, 0xe8b8d433,
            0x7807c9a2, 0x0f00f934, 0x9609a88e, 0xe10e9818, 0x7f6a0dbb, 0x086d3d2d,
            0x91646c97, 0xe6635c01, 0x6b6b51f4, 0x1c6c6162, 0x856530d8, 0xf262004e,
            0x6c0695ed, 0x1b01a57b, 0x8208f4c1, 0xf50fc457, 0x65b0d9c6, 0x12b7e950,
            0x8bbeb8ea, 0xfcb9887c, 0x62dd1ddf, 0x15da2d49, 0x8cd37cf3, 0xfbd44c65,
            0x4db26158, 0x3ab551ce, 0xa3bc0074, 0xd4bb30e2, 0x4adfa541, 0x3dd895d7,
            0xa4d1c46d, 0xd3d6f4fb, 0x4369e96a, 0x346ed9fc, 0xad678846, 0xda60b8d0,
            0x44042d73, 0x33031de5, 0xaa0a4c5f, 0xdd0d7cc9, 0x5005713c, 0x270241aa,
            0xbe0b1010, 0xc90c2086, 0x5768b525, 0x206f85b3, 0xb966d409, 0xce61e49f,
            0x5edef90e, 0x29d9c998, 0xb0d09822, 0xc7d7a8b4, 0x59b33d17, 0x2eb40d81,
            0xb7bd5c3b, 0xc0ba6cad, 0xedb88320, 0x9abfb3b6, 0x03b6e20c, 0x74b1d29a,
            0xead54739, 0x9dd277af, 0x04db2615, 0x73dc1683, 0xe3630b12, 0x94643b84,
            0x0d6d6a3e, 0x7a6a5aa8, 0xe40ecf0b, 0x9309ff9d, 0x0a00ae27, 0x7d079eb1,
            0xf00f9344, 0x8708a3d2, 0x1e01f268, 0x6906c2fe, 0xf762575d, 0x806567cb,
            0x196c3671, 0x6e6b06e7, 0xfed41b76, 0x89d32be0, 0x10da7a5a, 0x67dd4acc,
            0xf9b9df6f, 0x8ebeeff9, 0x17b7be43, 0x60b08ed5, 0xd6d6a3e8, 0xa1d1937e,
            0x38d8c2c4, 0x4fdff252, 0xd1bb67f1, 0xa6bc5767, 0x3fb506dd, 0x48b2364b,
            0xd80d2bda, 0xaf0a1b4c, 0x36034af6, 0x41047a60, 0xdf60efc3, 0xa867df55,
            0x316e8eef, 0x4669be79, 0xcb61b38c, 0xbc66831a, 0x256fd2a0, 0x5268e236,
            0xcc0c7795, 0xbb0b4703, 0x220216b9, 0x5505262f, 0xc5ba3bbe, 0xb2bd0b28,
            0x2bb45a92, 0x5cb36a04, 0xc2d7ffa7, 0xb5d0cf31, 0x2cd99e8b, 0x5bdeae1d,
            0x9b64c2b0, 0xec63f226, 0x756aa39c, 0x026d930a, 0x9c0906a9, 0xeb0e363f,
            0x72076785, 0x05005713, 0x95bf4a82, 0xe2b87a14, 0x7bb12bae, 0x0cb61b38,
            0x92d28e9b, 0xe5d5be0d, 0x7cdcefb7, 0x0bdbdf21, 0x86d3d2d4, 0xf1d4e242,
            0x68ddb3f8, 0x1fda836e, 0x81be16cd, 0xf6b9265b, 0x6fb077e1, 0x18b74777,
            0x88085ae6, 0xff0f6a70, 0x66063bca, 0x11010b5c, 0x8f659eff, 0xf862ae69,
            0x616bffd3, 0x166ccf45, 0xa00ae278, 0xd70dd2ee, 0x4e048354, 0x3903b3c2,
            0xa7672661, 0xd06016f7, 0x4969474d, 0x3e6e77db, 0xaed16a4a, 0xd9d65adc,
            0x40df0b66, 0x37d83bf0, 0xa9bcae53, 0xdebb9ec5, 0x47b2cf7f, 0x30b5ffe9,
            0xbdbdf21c, 0xcabac28a, 0x53b39330, 0x24b4a3a6, 0xbad03605, 0xcdd70693,
            0x54de5729, 0x23d967bf, 0xb3667a2e, 0xc4614ab8, 0x5d681b02, 0x2a6f2b94,
            0xb40bbe37, 0xc30c8ea1, 0x5a05df1b, 0x2d02ef8d
    };
}
