# gRPC Bundle Transport (Android Java)

## PROJECT DESCRIPTION

- Transport bundle between client and server
- Transport - Client: required have the recency blob in order to exchange bundles with Client
- Transport - Server
    - Receives blob from Server in first connection
    - Send and request bundles from Server every connection
- File structure: /storage/emulated/0/Android/data/net.discdd.bundletransport
    - /client: bundles to send to client + recencyBlob
    - /server: bundles to send to server

- Important note: Bundle Transport device is considered untrustworthy due to having physical carriers to transport the
  data.

## INSTALLATION

1. Download [Android Studio](https://developer.android.com/studio)
2. Git clone DDD repo using SSH ([try this guide](https://www.warp.dev/terminus/git-clone-ssh))
3. Open DDD as a Project with Android Studio
4. Android developer options configuration - [guide](https://developer.android.com/studio/debug/dev-options)
5. Connect the phone to laptop through USB-C cable and **Allow** connection from the laptop on the phone
6. Choose the desired physical device and application and hit “Play” to install

Set up VPN to be able to connect with Bundle Server

1. Get a OPVN certificate from [authncert.com](https://authncert.com/)
2. Request Ben Reed to configure the server for your email
3. Use OpenVPN or TunnelBlick with the .OPVN file
4. Connect VPN

**Notes**

- check for possible Gradle sync
- clean old Gradle package ```./gradlew clean```
- install necessary Maven dependencies ```mvn clean install```

## FEATURES and HOW TO USE

The Bundle Transport has two main features:

1. Start/Stop the gRPC server - The gRPC server on the Bundle Transport is used by the Bundle Client to send and
   retrieve data from the Bundle Transport
2. Connect to the Bundle Server - Creates a request to the Bundle Server in order to upload and download the bundles

Look up bundle transport directory in Device Explorer ```/storage/emulated/0/Android/data/net.discdd.bundletransport```

```
  /client: bundles to send to client
  /server: bundles to send to server
```
