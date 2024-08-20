gRPC Bundle Client (Android Java)
========================
DESCRIPTIONS
--------
- **Client - MySignal**: receive ADUs from mysignal through the message provider at ```content://net.discdd.provider.datastoreprovider/messages```
    
- **Client - Transport**
	- Connect with Transport through Wifi Direct mechanism 
	- Send bundles to Transport/Server with GrpcSendTask -> BundleTransmission to fetch ADUs for sending (Application Data Manager) and generate bundle for transmission.
  - Receive bundles from Transport/Client with GrpcRecieveTask -> BundleTransmission to process received bundles and decrypt bundles to get ADUs and send to MySignal app
   
- **Client - USB**
	- USB works as a Transport using on-device UsbManager to check USB drive connection
	- (In progress) As USB Drive connected, Client generate bundles and send to the Drive
    
- **Client - Server**
	- Share many common functions and mechanism: BundleTransmission (to process and generate bundles), Bundle Security (bundle encrypt and decrytion using Signal Algorithms), BundleUtils, Window and Routing (maintain synchronization of bundle transmission and receive)
	- File structure
***
	BundleTransmission (generate bundles and process received bundles)
	    |_bundle-generation
		|compressed-payload
		    |_payload.jar
		|_encrypted-payload
		    |_ **TODO**
		|_to-be-bundled
		    |_bundleID
		|_to-send
		    |_client
			|_transportID
		            |_bundleID
		|_uncompressed-payload
		    |_bundleID
	    |_received-processing
		|_transportID
		    |_serverID
	recieve (manage received bundles)
	    |_clientID
	        |_appID
		    |_metadata.json
	send (manage bundles to send)
	    |_clientID
		|_appID
		    |_to-send-adu
	            |_metadata.json

INSTALLATION
-------
1. Download [Android Studio](https://developer.android.com/studio)
2. Git clone DDD repo using SSH ([try this guide](https://www.warp.dev/terminus/git-clone-ssh))
3. Open DDD as a Project with Android Studio 
4. Android developer options configuration - [guide](https://developer.android.com/studio/debug/dev-options)
5. Connect the phone to laptop through USB-C cable and **Allow** connection from the laptop on the phone
6. Choose the desired physical device and application and hit “Play” to install
   
**Notes**
- check for possible Gradle sync 
- clean old Gradle package ```./gradlew clean```
- install necessary Maven dependencies ```mvn clean install```
- install Client before MySignal

ADDITIONAL NOTES
----------------
- [Android Tutorial](https://developer.android.com/training/basics/firstapp/index.html) if you're new to Android development
- While cloning, if you receive a Permission denied (publickey) error, make sure you have and authentication key for the computer you are using by navigating to “Settings” → “SSH and GPG keys” → “Authentication keys”
- If your code isn’t compiling, make sure you have at least v. 7.x.x for Gradle
- Java gRPC should already be installed in this process but if it is not you have to use the [Quick Start guide](https://grpc.io/docs/platforms/android/java/quickstart/). You can check if this is an issue by looking into your build.gradle file and checking for at least the following dependencies: okhttp, protobuf, and stub. Here is the documentation for [Java gRPC](https://github.com/grpc/grpc-java).
