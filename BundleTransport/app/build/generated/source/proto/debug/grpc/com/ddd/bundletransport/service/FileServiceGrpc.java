package com.ddd.bundletransport.service;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.42.0)",
    comments = "Source: FileService.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class FileServiceGrpc {

  private FileServiceGrpc() {}

  public static final String SERVICE_NAME = "protobuf.FileService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.ddd.bundletransport.service.FileUploadRequest,
      com.ddd.bundletransport.service.FileUploadResponse> getUploadFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UploadFile",
      requestType = com.ddd.bundletransport.service.FileUploadRequest.class,
      responseType = com.ddd.bundletransport.service.FileUploadResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<com.ddd.bundletransport.service.FileUploadRequest,
      com.ddd.bundletransport.service.FileUploadResponse> getUploadFileMethod() {
    io.grpc.MethodDescriptor<com.ddd.bundletransport.service.FileUploadRequest, com.ddd.bundletransport.service.FileUploadResponse> getUploadFileMethod;
    if ((getUploadFileMethod = FileServiceGrpc.getUploadFileMethod) == null) {
      synchronized (FileServiceGrpc.class) {
        if ((getUploadFileMethod = FileServiceGrpc.getUploadFileMethod) == null) {
          FileServiceGrpc.getUploadFileMethod = getUploadFileMethod =
              io.grpc.MethodDescriptor.<com.ddd.bundletransport.service.FileUploadRequest, com.ddd.bundletransport.service.FileUploadResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UploadFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.FileUploadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.FileUploadResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getUploadFileMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.ddd.bundletransport.service.ReqFilePath,
      com.ddd.bundletransport.service.Bytes> getDownloadFileMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DownloadFile",
      requestType = com.ddd.bundletransport.service.ReqFilePath.class,
      responseType = com.ddd.bundletransport.service.Bytes.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.ddd.bundletransport.service.ReqFilePath,
      com.ddd.bundletransport.service.Bytes> getDownloadFileMethod() {
    io.grpc.MethodDescriptor<com.ddd.bundletransport.service.ReqFilePath, com.ddd.bundletransport.service.Bytes> getDownloadFileMethod;
    if ((getDownloadFileMethod = FileServiceGrpc.getDownloadFileMethod) == null) {
      synchronized (FileServiceGrpc.class) {
        if ((getDownloadFileMethod = FileServiceGrpc.getDownloadFileMethod) == null) {
          FileServiceGrpc.getDownloadFileMethod = getDownloadFileMethod =
              io.grpc.MethodDescriptor.<com.ddd.bundletransport.service.ReqFilePath, com.ddd.bundletransport.service.Bytes>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DownloadFile"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.ReqFilePath.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.Bytes.getDefaultInstance()))
              .build();
        }
      }
    }
    return getDownloadFileMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static FileServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<FileServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<FileServiceStub>() {
        @java.lang.Override
        public FileServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new FileServiceStub(channel, callOptions);
        }
      };
    return FileServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static FileServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<FileServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<FileServiceBlockingStub>() {
        @java.lang.Override
        public FileServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new FileServiceBlockingStub(channel, callOptions);
        }
      };
    return FileServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static FileServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<FileServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<FileServiceFutureStub>() {
        @java.lang.Override
        public FileServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new FileServiceFutureStub(channel, callOptions);
        }
      };
    return FileServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class FileServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.FileUploadRequest> uploadFile(
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.FileUploadResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getUploadFileMethod(), responseObserver);
    }

    /**
     */
    public void downloadFile(com.ddd.bundletransport.service.ReqFilePath request,
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.Bytes> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDownloadFileMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getUploadFileMethod(),
            io.grpc.stub.ServerCalls.asyncClientStreamingCall(
              new MethodHandlers<
                com.ddd.bundletransport.service.FileUploadRequest,
                com.ddd.bundletransport.service.FileUploadResponse>(
                  this, METHODID_UPLOAD_FILE)))
          .addMethod(
            getDownloadFileMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                com.ddd.bundletransport.service.ReqFilePath,
                com.ddd.bundletransport.service.Bytes>(
                  this, METHODID_DOWNLOAD_FILE)))
          .build();
    }
  }

  /**
   */
  public static final class FileServiceStub extends io.grpc.stub.AbstractAsyncStub<FileServiceStub> {
    private FileServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected FileServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new FileServiceStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.FileUploadRequest> uploadFile(
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.FileUploadResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getUploadFileMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public void downloadFile(com.ddd.bundletransport.service.ReqFilePath request,
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.Bytes> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getDownloadFileMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class FileServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<FileServiceBlockingStub> {
    private FileServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected FileServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new FileServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.ddd.bundletransport.service.Bytes> downloadFile(
        com.ddd.bundletransport.service.ReqFilePath request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getDownloadFileMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class FileServiceFutureStub extends io.grpc.stub.AbstractFutureStub<FileServiceFutureStub> {
    private FileServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected FileServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new FileServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_DOWNLOAD_FILE = 0;
  private static final int METHODID_UPLOAD_FILE = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final FileServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(FileServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_DOWNLOAD_FILE:
          serviceImpl.downloadFile((com.ddd.bundletransport.service.ReqFilePath) request,
              (io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.Bytes>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_UPLOAD_FILE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.uploadFile(
              (io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.FileUploadResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (FileServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getUploadFileMethod())
              .addMethod(getDownloadFileMethod())
              .build();
        }
      }
    }
    return result;
  }
}
