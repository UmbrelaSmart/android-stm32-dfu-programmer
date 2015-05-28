package co.umbrela.tools.stm32dfuprogrammer;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Created by Philip on 26/05/2015.
 *
 * This class assume that the user has already created the gpio pins and set their direction
 * inside of the init.sun7i.rc script file.
 * The class must have 'other' write permission to access the gpio 'value' parameter
 */

public class Outputs {

    private static final String TAG = "Umbrela Outputs";
    private static final String bootValuePath = "sys/class/gpio/gpio177/value";
    private static final String resetValuePath = "sys/class/gpio/gpio215/value";

    File outReset;
    File outBoot;

    public Outputs(){
        outBoot = new File(bootValuePath);
        outReset = new File(resetValuePath);
    }

    public void setReset() {
        try {
            FileOutputStream stream = new FileOutputStream(outReset);
            stream.write('1');
            stream.close();
            Log.i(TAG, "set Reset OK");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            // e.printStackTrace();
        }
    }

    public void clearReset(){
        try {
            FileOutputStream stream = new FileOutputStream(outReset);
            stream.write('0');
            stream.close();
            Log.i(TAG, "clear Reset OK");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
           // e.printStackTrace();
        }
    }

    public void setBoot(){
        try {
            FileOutputStream stream = new FileOutputStream(outBoot);
            stream.write('1');
            stream.close();
            Log.i(TAG, "set Boot OK");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            //e.printStackTrace();
        }
    }

    public void clearBoot(){
        try {
            FileOutputStream stream = new FileOutputStream(outBoot);
            stream.write('0');
            stream.close();
            Log.i(TAG, "clear Boot OK");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            //e.printStackTrace();
        }
    }
}
