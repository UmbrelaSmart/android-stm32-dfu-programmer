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

    private static final String TAG = "Umbrela STM32 DFU Programmer: Outputs";

    private static final String bootValuePath = "/sys/class/gpio_sw/PD1/data";
    private static final String resetValuePath = "/sys/class/gpio_sw/PD0/data";

    private static final File outBoot = new File(bootValuePath);
    private static final File outReset = new File(resetValuePath);

    public static boolean enterDfuMode(){
        try {
            setReset();
            setBoot();
            clearReset();
            Log.i(TAG, "Entered DFU mode successfully");
            return true;
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            //e.printStackTrace();
            return false;
        }
    }

    public static boolean leaveDfuMode(){
        try {
            setReset();
            clearBoot();
            clearReset();
            Log.i(TAG, "Exited DFU mode successfully");
            return true;
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            //e.printStackTrace();
            return false;
        }
    }

    public static boolean enterNormalMode(){
        try {
            setReset();
            clearReset();
            clearBoot();
            Log.i(TAG, "Entered Normal mode successfully");
            return true;
        } catch (IOException e) {
            Log.e(TAG, e.getMessage());
            //e.printStackTrace();
            return false;
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
