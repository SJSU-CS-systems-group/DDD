# gRPC Bundle Transport (Android Java)

## PROJECT DESCRIPTION
- Android mobile application that behaves as the data transporter between the disconnected and connected areas for the DDD system
- Store the bundles received by the client and deliver them to the server and vice versa
- Important note: Bundle Transport device is considered untrustworthy due to having physical carriers to transport the data.

## INSTALLATION
1. Download [Android Studio](https://developer.android.com/studio)
2. Git clone DDD repo using SSH ([try this guide](https://www.warp.dev/terminus/git-clone-ssh))
3. Open DDD as a Project with Android Studio 
4. Android developer options configuration - [guide](https://developer.android.com/studio/debug/dev-options)
5. Connect the phone to laptop through USB-C cable and **Allow** connection from the laptop on the phone
6. Choose the desired physical device and application and hit “Play” to install

Set up VPN to be able to connect with Bundle Server
1. Get a OPVN certificate from [](https://authncert.com/)
2. Request Ben Reed to configure the server for your email
3. Use OpenVPN or TunnelBlick with the .OPVN file
4. Connect VPN 
   
   
**Notes**
- check for possible Gradle sync 
- clean old Gradle package ```./gradlew clean```
- install necessary Maven dependencies ```mvn clean install```
  
## FEATURES and HOW TO USE
The Bundle Transport has two main features:
1. Start/Stop the gRPC server - The gRPC server on the Bundle Transport is used by the Bundle Client to send and retrieve data from the Bundle Transport
2. Connect to the Bundle Server - Creates a request to the Bundle Server in order to upload and download the bundles

Look up bundle transport directory in Device Explorer ```/storage/emulated/0/Android/data/net.discdd.bundletransport```
```
  /client: bundles to send to client
  /server: bundles to send to server
```

## ADDITIONAL NOTES
- [Android Tutorial](https://developer.android.com/training/basics/firstapp/index.html) if you're new to Android development
- For M1 and M2 Mac models please change grpc plugin (found in build.gradle file) from
```artifact = 'io.grpc:protoc-gen-grpc-java:1.42.0'```
to
```artifact = 'io.grpc:protoc-gen-grpc-java:1.42.0:osx-x86_64'```
- For M2 Mac models ALSO install rosetta in terminal using following command in terminal
```softwareupdate --install-rosetta```
