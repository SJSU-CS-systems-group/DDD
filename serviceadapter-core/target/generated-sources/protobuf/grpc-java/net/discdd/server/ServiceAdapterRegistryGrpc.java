package net.discdd.server;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.58.0)",
    comments = "Source: ServiceAdapterRegistry.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class ServiceAdapterRegistryGrpc {

  private ServiceAdapterRegistryGrpc() {}

  public static final java.lang.String SERVICE_NAME = "ServiceAdapterRegistry";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<net.discdd.server.ConnectionData,
      net.discdd.server.ResponseStatus> getRegisterAdapterMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "registerAdapter",
      requestType = net.discdd.server.ConnectionData.class,
      responseType = net.discdd.server.ResponseStatus.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<net.discdd.server.ConnectionData,
      net.discdd.server.ResponseStatus> getRegisterAdapterMethod() {
    io.grpc.MethodDescriptor<net.discdd.server.ConnectionData, net.discdd.server.ResponseStatus> getRegisterAdapterMethod;
    if ((getRegisterAdapterMethod = ServiceAdapterRegistryGrpc.getRegisterAdapterMethod) == null) {
      synchronized (ServiceAdapterRegistryGrpc.class) {
        if ((getRegisterAdapterMethod = ServiceAdapterRegistryGrpc.getRegisterAdapterMethod) == null) {
          ServiceAdapterRegistryGrpc.getRegisterAdapterMethod = getRegisterAdapterMethod =
              io.grpc.MethodDescriptor.<net.discdd.server.ConnectionData, net.discdd.server.ResponseStatus>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "registerAdapter"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  net.discdd.server.ConnectionData.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  net.discdd.server.ResponseStatus.getDefaultInstance()))
              .setSchemaDescriptor(new ServiceAdapterRegistryMethodDescriptorSupplier("registerAdapter"))
              .build();
        }
      }
    }
    return getRegisterAdapterMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static ServiceAdapterRegistryStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterRegistryStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterRegistryStub>() {
        @java.lang.Override
        public ServiceAdapterRegistryStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServiceAdapterRegistryStub(channel, callOptions);
        }
      };
    return ServiceAdapterRegistryStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static ServiceAdapterRegistryBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterRegistryBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterRegistryBlockingStub>() {
        @java.lang.Override
        public ServiceAdapterRegistryBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServiceAdapterRegistryBlockingStub(channel, callOptions);
        }
      };
    return ServiceAdapterRegistryBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static ServiceAdapterRegistryFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterRegistryFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<ServiceAdapterRegistryFutureStub>() {
        @java.lang.Override
        public ServiceAdapterRegistryFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new ServiceAdapterRegistryFutureStub(channel, callOptions);
        }
      };
    return ServiceAdapterRegistryFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void registerAdapter(net.discdd.server.ConnectionData request,
        io.grpc.stub.StreamObserver<net.discdd.server.ResponseStatus> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getRegisterAdapterMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service ServiceAdapterRegistry.
   */
  public static abstract class ServiceAdapterRegistryImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return ServiceAdapterRegistryGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service ServiceAdapterRegistry.
   */
  public static final class ServiceAdapterRegistryStub
      extends io.grpc.stub.AbstractAsyncStub<ServiceAdapterRegistryStub> {
    private ServiceAdapterRegistryStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServiceAdapterRegistryStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServiceAdapterRegistryStub(channel, callOptions);
    }

    /**
     */
    public void registerAdapter(net.discdd.server.ConnectionData request,
        io.grpc.stub.StreamObserver<net.discdd.server.ResponseStatus> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getRegisterAdapterMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service ServiceAdapterRegistry.
   */
  public static final class ServiceAdapterRegistryBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<ServiceAdapterRegistryBlockingStub> {
    private ServiceAdapterRegistryBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServiceAdapterRegistryBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServiceAdapterRegistryBlockingStub(channel, callOptions);
    }

    /**
     */
    public net.discdd.server.ResponseStatus registerAdapter(net.discdd.server.ConnectionData request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getRegisterAdapterMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service ServiceAdapterRegistry.
   */
  public static final class ServiceAdapterRegistryFutureStub
      extends io.grpc.stub.AbstractFutureStub<ServiceAdapterRegistryFutureStub> {
    private ServiceAdapterRegistryFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected ServiceAdapterRegistryFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new ServiceAdapterRegistryFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<net.discdd.server.ResponseStatus> registerAdapter(
        net.discdd.server.ConnectionData request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getRegisterAdapterMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_REGISTER_ADAPTER = 0;

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
        case METHODID_REGISTER_ADAPTER:
          serviceImpl.registerAdapter((net.discdd.server.ConnectionData) request,
              (io.grpc.stub.StreamObserver<net.discdd.server.ResponseStatus>) responseObserver);
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
          getRegisterAdapterMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              net.discdd.server.ConnectionData,
              net.discdd.server.ResponseStatus>(
                service, METHODID_REGISTER_ADAPTER)))
        .build();
  }

  private static abstract class ServiceAdapterRegistryBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    ServiceAdapterRegistryBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return net.discdd.server.ServiceAdapterRegistryOuterClass.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("ServiceAdapterRegistry");
    }
  }

  private static final class ServiceAdapterRegistryFileDescriptorSupplier
      extends ServiceAdapterRegistryBaseDescriptorSupplier {
    ServiceAdapterRegistryFileDescriptorSupplier() {}
  }

  private static final class ServiceAdapterRegistryMethodDescriptorSupplier
      extends ServiceAdapterRegistryBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    ServiceAdapterRegistryMethodDescriptorSupplier(java.lang.String methodName) {
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
      synchronized (ServiceAdapterRegistryGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new ServiceAdapterRegistryFileDescriptorSupplier())
              .addMethod(getRegisterAdapterMethod())
              .build();
        }
      }
    }
    return result;
  }
}
