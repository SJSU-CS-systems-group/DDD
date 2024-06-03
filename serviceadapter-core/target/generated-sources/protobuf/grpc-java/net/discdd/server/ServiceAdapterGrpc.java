package net.discdd.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.58.0)",
    comments = "Source: ServiceAdapter.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ServiceAdapterGrpc {

  private ServiceAdapterGrpc() {}

  public static final java.lang.String SERVICE_NAME = "ServiceAdapter";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<net.discdd.server.AppData,
      net.discdd.server.AppData> getSaveDataMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "saveData",
      requestType = net.discdd.server.AppData.class,
      responseType = net.discdd.server.AppData.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<net.discdd.server.AppData,
      net.discdd.server.AppData> getSaveDataMethod() {
    io.grpc.MethodDescriptor<net.discdd.server.AppData, net.discdd.server.AppData> getSaveDataMethod;
    if ((getSaveDataMethod = ServiceAdapterGrpc.getSaveDataMethod) == null) {
      synchronized (ServiceAdapterGrpc.class) {
        if ((getSaveDataMethod = ServiceAdapterGrpc.getSaveDataMethod) == null) {
          ServiceAdapterGrpc.getSaveDataMethod = getSaveDataMethod =
              io.grpc.MethodDescriptor.<net.discdd.server.AppData, net.discdd.server.AppData>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "saveData"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  net.discdd.server.AppData.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  net.discdd.server.AppData.getDefaultInstance()))
              .setSchemaDescriptor(new ServiceAdapterMethodDescriptorSupplier("saveData"))
              .build();
        }
      }
    }
    return getSaveDataMethod;
  }

  private static volatile io.grpc.MethodDescriptor<net.discdd.server.ClientData,
      net.discdd.server.PrepareResponse> getPrepareDataMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "prepareData",
      requestType = net.discdd.server.ClientData.class,
      responseType = net.discdd.server.PrepareResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<net.discdd.server.ClientData,
      net.discdd.server.PrepareResponse> getPrepareDataMethod() {
    io.grpc.MethodDescriptor<net.discdd.server.ClientData, net.discdd.server.PrepareResponse> getPrepareDataMethod;
    if ((getPrepareDataMethod = ServiceAdapterGrpc.getPrepareDataMethod) == null) {
      synchronized (ServiceAdapterGrpc.class) {
        if ((getPrepareDataMethod = ServiceAdapterGrpc.getPrepareDataMethod) == null) {
          ServiceAdapterGrpc.getPrepareDataMethod = getPrepareDataMethod =
              io.grpc.MethodDescriptor.<net.discdd.server.ClientData, net.discdd.server.PrepareResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "prepareData"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  net.discdd.server.ClientData.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  net.discdd.server.PrepareResponse.getDefaultInstance()))
              .setSchemaDescriptor(new ServiceAdapterMethodDescriptorSupplier("prepareData"))
              .build();
        }
      }
    }
    return getPrepareDataMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServiceAdapterStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterStub>() {
        @java.lang.Override
        public ServiceAdapterStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServiceAdapterStub(channel, callOptions);
        }
      };
    return ServiceAdapterStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServiceAdapterBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterBlockingStub>() {
        @java.lang.Override
        public ServiceAdapterBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServiceAdapterBlockingStub(channel, callOptions);
        }
      };
    return ServiceAdapterBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServiceAdapterFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterFutureStub>() {
        @java.lang.Override
        public ServiceAdapterFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServiceAdapterFutureStub(channel, callOptions);
        }
      };
    return ServiceAdapterFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     * <pre>
     * saveData sends received ADUs to the ServiceAdapter and at
     * the same time receives ADUs to send back to clients as the
     * return value.
     * </pre>
     */
    default void saveData(net.discdd.server.AppData request,
        io.grpc.stub.StreamObserver<net.discdd.server.AppData> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSaveDataMethod(), responseObserver);
    }

    /**
     * <pre>
     * tell the ServiceAdapter to prepare data for a given client
     * </pre>
     */
    default void prepareData(net.discdd.server.ClientData request,
        io.grpc.stub.StreamObserver<net.discdd.server.PrepareResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getPrepareDataMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServiceAdapter.
   */
  public static abstract class ServiceAdapterImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServiceAdapterGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServiceAdapter.
   */
  public static final class ServiceAdapterStub
      extends io.grpc.stub.AbstractAsyncStub<ServiceAdapterStub> {
    private ServiceAdapterStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServiceAdapterStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServiceAdapterStub(channel, callOptions);
    }

    /**
     * <pre>
     * saveData sends received ADUs to the ServiceAdapter and at
     * the same time receives ADUs to send back to clients as the
     * return value.
     * </pre>
     */
    public void saveData(net.discdd.server.AppData request,
        io.grpc.stub.StreamObserver<net.discdd.server.AppData> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSaveDataMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     * <pre>
     * tell the ServiceAdapter to prepare data for a given client
     * </pre>
     */
    public void prepareData(net.discdd.server.ClientData request,
        io.grpc.stub.StreamObserver<net.discdd.server.PrepareResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getPrepareDataMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServiceAdapter.
   */
  public static final class ServiceAdapterBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServiceAdapterBlockingStub> {
    private ServiceAdapterBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServiceAdapterBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServiceAdapterBlockingStub(channel, callOptions);
    }

    /**
     * <pre>
     * saveData sends received ADUs to the ServiceAdapter and at
     * the same time receives ADUs to send back to clients as the
     * return value.
     * </pre>
     */
    public net.discdd.server.AppData saveData(net.discdd.server.AppData request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSaveDataMethod(), getCallOptions(), request);
    }

    /**
     * <pre>
     * tell the ServiceAdapter to prepare data for a given client
     * </pre>
     */
    public net.discdd.server.PrepareResponse prepareData(net.discdd.server.ClientData request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getPrepareDataMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServiceAdapter.
   */
  public static final class ServiceAdapterFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServiceAdapterFutureStub> {
    private ServiceAdapterFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServiceAdapterFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServiceAdapterFutureStub(channel, callOptions);
    }

    /**
     * <pre>
     * saveData sends received ADUs to the ServiceAdapter and at
     * the same time receives ADUs to send back to clients as the
     * return value.
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<net.discdd.server.AppData> saveData(
        net.discdd.server.AppData request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSaveDataMethod(), getCallOptions()), request);
    }

    /**
     * <pre>
     * tell the ServiceAdapter to prepare data for a given client
     * </pre>
     */
    public com.google.common.util.concurrent.ListenableFuture<net.discdd.server.PrepareResponse> prepareData(
        net.discdd.server.ClientData request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getPrepareDataMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SAVE_DATA = 0;
  private static final int METHODID_PREPARE_DATA = 1;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SAVE_DATA:
          serviceImpl.saveData((net.discdd.server.AppData) request,
              (io.grpc.stub.StreamObserver<net.discdd.server.AppData>) responseObserver);
          break;
        case METHODID_PREPARE_DATA:
          serviceImpl.prepareData((net.discdd.server.ClientData) request,
              (io.grpc.stub.StreamObserver<net.discdd.server.PrepareResponse>) responseObserver);
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
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getSaveDataMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              net.discdd.server.AppData,
              net.discdd.server.AppData>(
                service, METHODID_SAVE_DATA)))
        .addMethod(
          getPrepareDataMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              net.discdd.server.ClientData,
              net.discdd.server.PrepareResponse>(
                service, METHODID_PREPARE_DATA)))
        .build();
  }

  private static abstract class ServiceAdapterBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServiceAdapterBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return net.discdd.server.ServiceAdapterOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServiceAdapter");
    }
  }

  private static final class ServiceAdapterFileDescriptorSupplier
      extends ServiceAdapterBaseDescriptorSupplier {
    ServiceAdapterFileDescriptorSupplier() {}
  }

  private static final class ServiceAdapterMethodDescriptorSupplier
      extends ServiceAdapterBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServiceAdapterMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (ServiceAdapterGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServiceAdapterFileDescriptorSupplier())
              .addMethod(getSaveDataMethod())
              .addMethod(getPrepareDataMethod())
              .build();
        }
      }
    }
    return result;
  }
}
