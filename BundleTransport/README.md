gRPC Bundle Transport (Android Java)
========================

PROJECT DESCRIPTION
-------------------
Bundle Transport is a android mobile application that behaves as the data transporter between the disconnected and connected areas for the DDD system. The main purpose of the Bundle Transport is to store the bundles received by the client and deliver them to the server and vice versa. It is important to note that the device Bundle Transport runs on is considered to be untrusted due to having physical carriers to transport the data.

PREREQUISITES
-------------
- [Android Tutorial](https://developer.android.com/training/basics/firstapp/index.html) if you're new to Android development

INSTALL
-------
1. Download [Android Studio](https://developer.android.com/studio)
2. Git clone DDD repo(using SSH) and open “bundleTransport” directory into Android Studio
3. Connect your Android to your computer using a USB-C cable and “run”

ADDITIONAL NOTES
----------------
- For M1 and M2 Mac models please change grpc plugin (found in build.gradle file) from
```
artifact = 'io.grpc:protoc-gen-grpc-java:1.42.0'
```
to
```
artifact = 'io.grpc:protoc-gen-grpc-java:1.42.0:osx-x86_64'
```
- For M2 Mac models ALSO install rosetta in terminal using following command in terminal
```
softwareupdate --install-rosetta
```

FEATURES and HOW TO USE
-----------------------
The Bundle Transport has two main features:
1. Start/Stop the gRPC server - The gRPC server on the Bundle Transport is used by the Bundle Client to send and retrieve data from the Bundle Transport
2. Connect to the Bundle Server - Creates a request to the Bundle Server in order to upload and download the bundles

As of right now the starting and stopping of the gRPC server and the connection to the Bundle Server is done manually by the Bundle Transport carrier. However, future development will focus on making these features automatic.

