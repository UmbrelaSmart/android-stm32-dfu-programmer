# android-stm32-dfu-programmer #
Brief Description
=======
This app is able to mass erase a STM32Fx and
flash it with firmware from a .dfu file.

###Notes###
The USB_VENDOR_ID and USB_PRODUCT_ID in the MainActivity.java 
file must match your stm32 device, 
otherwise the app will not recognize the usb device.

###Instructions###
1.	Create .dfu file with DfuSe by STM with the proper
	PID, and Version number.
	Note: Only one image per file is supported.
2.	Copy the file into the Internal Download folder 
	of your Android	device. 
3.	Hold/Jumper Boot0 button/pin ( and sometimes also reset button)
	while connecting the usb device to the Android device 
	USING an OTG cable.
4.	(Re)open the app. If device was found, the permission dialog 
	appears. Allow persmission to access USB device.

Once your device info is displayed int the text field,
you are ready to perform two actions.

*	Press Mass Erase Button to completely wipe the Flash
memory excluding the Option Bytes. This might take 15 seconds 
depending on the Flash size and app will not respond
until its finished.

*	Press Program Button: This will load the .dfu file from
Download Folder and sent to attached Device. Once the file
is downloaded it will automatically start the new program
downloaded. If you want to Mass Erase, reconnect the cable and
restart the app.
	


###Tested on###
Nexus 7 (2012) running Android API 19 (Kitkat) to a STM32-P107 Dev Board by Olimex

###Limitation###
Only one .dfu file must be located in the internal 
Download folder.
The .dfu file must be compiled using dfuSE version1
(tested with DFU File Manager 3.0.3) with at most 
one element file for at most one target.
Does only mass erase the complete flash memory.
The image file size must not exceed the target device
flash memory capacity.
Any errors during programming are ignored by clearing the
status.

Known Issues
=======
On some STM32F devices, It takes several tries to recognize
the USB device.

Contributors
------
* Philip Ped
* Ryan Ramchandar

License
=======
	Copyright 2015 Umbrela Smart, Inc.

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

   	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
