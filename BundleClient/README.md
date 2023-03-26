gRPC Hello World Example (Android Java)
========================

PREREQUISITES
-------------
- [Java gRPC](https://github.com/grpc/grpc-java)

- [Android Tutorial](https://developer.android.com/training/basics/firstapp/index.html) if you're new to Android development

- [gRPC Java Android Quick Start Guide](https://grpc.io/docs/quickstart/android.html)

- We only have Android gRPC client in this example. Please follow examples in other languages to build and run a gRPC server.

INSTALL
-------

1. Install and build gRPC Java, the packages will be required to run the application. Follow the steps listed in the [gRPC Java Android Quick Start Guide](https://grpc.io/docs/quickstart/android.html) 

2. Once you have the packages you need to build the Bundle Client Android Application in Android Studio
If the cradle build option does not exist - 
app -> edit configurations -> '+' -> Gradle -> Run options: build --debug --stacktrace -> Apply -> OK

Clean and build the project this will generate necessary client and stub implementation using Port file.
Note the gRPC version are different for M1 Macs, in case you are trying to build on MAC OSX, your gRPC version in the build.gradle(Module) should like io.grpc:protoc-gen-grpc-java:1.42.0:osx-x86_64
In stead of io.grpc:protoc-gen-grpc-java:1.42.0
 

Please refer to the
[tutorial](https://grpc.io/docs/tutorials/basic/android.html) on
how to use gRPC in Android programs.
