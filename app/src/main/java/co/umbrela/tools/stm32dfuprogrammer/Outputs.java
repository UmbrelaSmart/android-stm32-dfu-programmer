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
    private static final String bootValuePath = "/sys/class/gpio_sw/PD1/data";
    private static final String resetValuePath = "/sys/class/gpio_sw/PD0/data";

    private static final File outBoot = new File(bootValuePath);
    private static final File outReset = new File(resetValuePath);

    public static void enterDfuMode(){
        try{
            setReset();
            setBoot();
            clearReset();
            Log.i(TAG, "entered DFU mode successful");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            // e.printStackTrace();
        }
    }

    public static void enterNormalMode(){
        try{
            setReset();
            clearReset();
            clearBoot();
            Log.i(TAG, "entered Normal mode successful");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            // e.printStackTrace();
        }
    }

    public static void leaveDfuMode(){
        try{
            setReset();
            clearBoot();
            clearReset();
            Log.i(TAG, "exited DFU mode successful");
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            // e.printStackTrace();
        }
    }

    private static void setReset() throws IOException {
            FileOutputStream stream = new FileOutputStream(outReset);
            stream.write('0');  // this is active-low
            stream.close();
     }

    private static void clearReset()throws IOException {
            FileOutputStream stream = new FileOutputStream(outReset);
            stream.write('1');
            stream.close();
     }

    private static void setBoot() throws IOException {
            FileOutputStream stream = new FileOutputStream(outBoot);
            stream.write('1');  // this is active-high
            stream.close();
    }

    private static void clearBoot() throws IOException {
            FileOutputStream stream = new FileOutputStream(outBoot);
            stream.write('0');
            stream.close();
    }
}
