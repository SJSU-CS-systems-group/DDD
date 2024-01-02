gRPC Bundle Transport (Android Java)
========================

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