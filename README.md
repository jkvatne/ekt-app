# EKT App

This App was made for use in orienteering competitions where the Emit EKT system is used.
The app is used to connect an Emit EKT reader (MTR4) to the Motime cloud database.

It is made only for Android, using the
[Android USB Host Mode (OTG)](http://developer.android.com/guide/topics/connectivity/usb/host.html)
available since Android 3.1 and working reliably since Android 4.2.

No root access, ADK, or special kernel drivers are required; all drivers are implemented in
Java using the library at https://github.com/mik3y/usb-serial-for-android

## License
The library is published under the MIT License, as is this app. This means you are free to use and modify it as you like

## Installation
No precompiled APK is available. You have to compile and install the program yourself.
For this, you have to use Android Studio, which is free.

The URL for the web operation is stored in the file apikey.properties, which is not
checked in to the repository.

Create a file named apikey.properties in the project root, and add a single line like this:

SERVER_URL = "https://my.domain.com/myprogram.php?pw=mypassword&"

The EKT data is appended in compressed format. See the code for details.

## Warning

This is Alpha software, and have not been tested much. The plan is to add support for the latest EKT Readers, but currently only MTR4 and erlier types are supported.

 