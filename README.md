# EKT App

This App was made for use in orienteering competitions where the Emit EKT system is used.
The app is used to connect an Emit EKT reader (MTR4) to the Motime cloud database.

It is made only for Android, using the
[Android USB Host Mode (OTG)](http://developer.android.com/guide/topics/connectivity/usb/host.html)
available since Android 3.1 and working reliably since Android 4.2.

No root access, ADK, or special kernel drivers are required; all drivers are implemented in
Java using the library at https://github.com/mik3y/usb-serial-for-android

## License
The library made by mik3y is published under the MIT License, as is this app. This means you are free to use and modify it as you like

## Installation
No precompiled APK is available. You have to compile and install the program yourself.
For this, you have to use Android Studio, which is free.

The URL for the web operation is stored in the file apikey.properties, which is not
checked in to the repository.

Create a file named apikey.properties in the project root, and add a single line like this:

SERVER_URL = "https://my.domain.com/myprogram.php?pw=mypassword&"

The EKT data is appended in compressed format. See the code for details.

## Warning

This is Alpha software, and have not been tested much. 

I have used it on the old eScan (pistol type) and MTR4.
The webserver tid.nook.no is the only server supported, 
but it can easily be modified to support other servers.

## Debugging
 
 To connect over WiFi, go to developer settings, Wifi debugging
 and read the ip/port of the phone.
 Then connect over usb and type (replace ip/port as required)
 
 >adb pair 192.168.2.22:39963
 
 Remove usb and type
 >adb connect 192.168.2.22:39963
 
 Now it should be connected.