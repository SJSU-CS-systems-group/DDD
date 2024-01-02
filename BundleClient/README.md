gRPC Bundle Client (Android Java)
========================

PREREQUISITES
-------------
- [Android Tutorial](https://developer.android.com/training/basics/firstapp/index.html) if you're new to Android development

INSTALL
-------
1. Download [Android Studio](https://developer.android.com/studio)
2. Git clone DDD repo(using SSH) and open “bundleClient” directory into Android Studio
3. Connect your Android to your computer using a USB-C cable and “run”

ADDITIONAL NOTES
----------------
- While cloning, if you receive a Permission denied (publickey) error, make sure you have and authentication key for the computer you are using by navigating to “Settings” → “SSH and GPG keys” → “Authentication keys”
- If your code isn’t compiling, make sure you have at least v. 7.x.x for Gradle
- Java gRPC should already be installed in this process but if it is not you have to use the [Quick Start guide](https://grpc.io/docs/platforms/android/java/quickstart/). You can check if this is an issue by looking into your build.gradle file and checking for at least the following dependencies: okhttp, protobuf, and stub. Here is the documentation for [Java gRPC](https://github.com/grpc/grpc-java).
