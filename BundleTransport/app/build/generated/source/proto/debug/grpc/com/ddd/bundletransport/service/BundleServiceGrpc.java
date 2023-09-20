package com.ddd.bundletransport.service;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.42.0)",
    comments = "Source: BundleService.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class BundleServiceGrpc {

  private BundleServiceGrpc() {}

  public static final String SERVICE_NAME = "protobuf.BundleService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<com.ddd.bundletransport.service.BundleUploadRequest,
      com.ddd.bundletransport.service.BundleUploadResponse> getUploadBundleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "UploadBundle",
      requestType = com.ddd.bundletransport.service.BundleUploadRequest.class,
      responseType = com.ddd.bundletransport.service.BundleUploadResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
  public static io.grpc.MethodDescriptor<com.ddd.bundletransport.service.BundleUploadRequest,
      com.ddd.bundletransport.service.BundleUploadResponse> getUploadBundleMethod() {
    io.grpc.MethodDescriptor<com.ddd.bundletransport.service.BundleUploadRequest, com.ddd.bundletransport.service.BundleUploadResponse> getUploadBundleMethod;
    if ((getUploadBundleMethod = BundleServiceGrpc.getUploadBundleMethod) == null) {
      synchronized (BundleServiceGrpc.class) {
        if ((getUploadBundleMethod = BundleServiceGrpc.getUploadBundleMethod) == null) {
          BundleServiceGrpc.getUploadBundleMethod = getUploadBundleMethod =
              io.grpc.MethodDescriptor.<com.ddd.bundletransport.service.BundleUploadRequest, com.ddd.bundletransport.service.BundleUploadResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.CLIENT_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "UploadBundle"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.BundleUploadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.BundleUploadResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getUploadBundleMethod;
  }

  private static volatile io.grpc.MethodDescriptor<com.ddd.bundletransport.service.BundleDownloadRequest,
      com.ddd.bundletransport.service.BundleDownloadResponse> getDownloadBundleMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "DownloadBundle",
      requestType = com.ddd.bundletransport.service.BundleDownloadRequest.class,
      responseType = com.ddd.bundletransport.service.BundleDownloadResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
  public static io.grpc.MethodDescriptor<com.ddd.bundletransport.service.BundleDownloadRequest,
      com.ddd.bundletransport.service.BundleDownloadResponse> getDownloadBundleMethod() {
    io.grpc.MethodDescriptor<com.ddd.bundletransport.service.BundleDownloadRequest, com.ddd.bundletransport.service.BundleDownloadResponse> getDownloadBundleMethod;
    if ((getDownloadBundleMethod = BundleServiceGrpc.getDownloadBundleMethod) == null) {
      synchronized (BundleServiceGrpc.class) {
        if ((getDownloadBundleMethod = BundleServiceGrpc.getDownloadBundleMethod) == null) {
          BundleServiceGrpc.getDownloadBundleMethod = getDownloadBundleMethod =
              io.grpc.MethodDescriptor.<com.ddd.bundletransport.service.BundleDownloadRequest, com.ddd.bundletransport.service.BundleDownloadResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.SERVER_STREAMING)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "DownloadBundle"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.BundleDownloadRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.lite.ProtoLiteUtils.marshaller(
                  com.ddd.bundletransport.service.BundleDownloadResponse.getDefaultInstance()))
              .build();
        }
      }
    }
    return getDownloadBundleMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static BundleServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BundleServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BundleServiceStub>() {
        @java.lang.Override
        public BundleServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BundleServiceStub(channel, callOptions);
        }
      };
    return BundleServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static BundleServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BundleServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BundleServiceBlockingStub>() {
        @java.lang.Override
        public BundleServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BundleServiceBlockingStub(channel, callOptions);
        }
      };
    return BundleServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static BundleServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<BundleServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<BundleServiceFutureStub>() {
        @java.lang.Override
        public BundleServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new BundleServiceFutureStub(channel, callOptions);
        }
      };
    return BundleServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class BundleServiceImplBase implements io.grpc.BindableService {

    /**
     */
    public io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleUploadRequest> uploadBundle(
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleUploadResponse> responseObserver) {
      return io.grpc.stub.ServerCalls.asyncUnimplementedStreamingCall(getUploadBundleMethod(), responseObserver);
    }

    /**
     */
    public void downloadBundle(com.ddd.bundletransport.service.BundleDownloadRequest request,
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleDownloadResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getDownloadBundleMethod(), responseObserver);
    }

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getUploadBundleMethod(),
            io.grpc.stub.ServerCalls.asyncClientStreamingCall(
              new MethodHandlers<
                com.ddd.bundletransport.service.BundleUploadRequest,
                com.ddd.bundletransport.service.BundleUploadResponse>(
                  this, METHODID_UPLOAD_BUNDLE)))
          .addMethod(
            getDownloadBundleMethod(),
            io.grpc.stub.ServerCalls.asyncServerStreamingCall(
              new MethodHandlers<
                com.ddd.bundletransport.service.BundleDownloadRequest,
                com.ddd.bundletransport.service.BundleDownloadResponse>(
                  this, METHODID_DOWNLOAD_BUNDLE)))
          .build();
    }
  }

  /**
   */
  public static final class BundleServiceStub extends io.grpc.stub.AbstractAsyncStub<BundleServiceStub> {
    private BundleServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BundleServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BundleServiceStub(channel, callOptions);
    }

    /**
     */
    public io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleUploadRequest> uploadBundle(
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleUploadResponse> responseObserver) {
      return io.grpc.stub.ClientCalls.asyncClientStreamingCall(
          getChannel().newCall(getUploadBundleMethod(), getCallOptions()), responseObserver);
    }

    /**
     */
    public void downloadBundle(com.ddd.bundletransport.service.BundleDownloadRequest request,
        io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleDownloadResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncServerStreamingCall(
          getChannel().newCall(getDownloadBundleMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class BundleServiceBlockingStub extends io.grpc.stub.AbstractBlockingStub<BundleServiceBlockingStub> {
    private BundleServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BundleServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BundleServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public java.util.Iterator<com.ddd.bundletransport.service.BundleDownloadResponse> downloadBundle(
        com.ddd.bundletransport.service.BundleDownloadRequest request) {
      return io.grpc.stub.ClientCalls.blockingServerStreamingCall(
          getChannel(), getDownloadBundleMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class BundleServiceFutureStub extends io.grpc.stub.AbstractFutureStub<BundleServiceFutureStub> {
    private BundleServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected BundleServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new BundleServiceFutureStub(channel, callOptions);
    }
  }

  private static final int METHODID_DOWNLOAD_BUNDLE = 0;
  private static final int METHODID_UPLOAD_BUNDLE = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final BundleServiceImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(BundleServiceImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_DOWNLOAD_BUNDLE:
          serviceImpl.downloadBundle((com.ddd.bundletransport.service.BundleDownloadRequest) request,
              (io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleDownloadResponse>) responseObserver);
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
        case METHODID_UPLOAD_BUNDLE:
          return (io.grpc.stub.StreamObserver<Req>) serviceImpl.uploadBundle(
              (io.grpc.stub.StreamObserver<com.ddd.bundletransport.service.BundleUploadResponse>) responseObserver);
        default:
          throw new AssertionError();
      }
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (BundleServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .addMethod(getUploadBundleMethod())
              .addMethod(getDownloadBundleMethod())
              .build();
        }
      }
    }
    return result;
  }
}
