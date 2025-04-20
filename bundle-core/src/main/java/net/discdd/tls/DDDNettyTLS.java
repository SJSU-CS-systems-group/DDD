package net.discdd.tls;

import io.grpc.BindableService;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.AbstractBlockingStub;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class DDDNettyTLS {

    public static Server createGrpcServer(KeyPair serverKeyPair,
                                          X509Certificate serverCert,
                                          int port,
                                          BindableService... services) throws SSLException {
        var sslServerContext =
                GrpcSslContexts.configure(SslContextBuilder.forServer(serverKeyPair.getPrivate(), serverCert))
                        .clientAuth(ClientAuth.REQUIRE)
                        .trustManager(DDDTLSUtil.trustManager)
                        .build();
        var serviceBuilder = NettyServerBuilder.forPort(port).sslContext(sslServerContext);
        for (var service : services) {
            serviceBuilder.addService(service);
        }

        serviceBuilder.intercept(new NettyServerCertificateInterceptor());
        return serviceBuilder.build();
    }

    record NettyStubWithCertificate<T>(T stub, CompletableFuture<X509Certificate> certificate) {}

    public static <T extends AbstractBlockingStub<T>> NettyStubWithCertificate<T> createGrpcStubWithCertificate(Function<ManagedChannel, T> maker,
                                                                                                                KeyPair clientKeyPair,
                                                                                                                String host,
                                                                                                                int port,
                                                                                                                X509Certificate clientCert) throws
            SSLException {
        var sslClientContext = GrpcSslContexts.forClient()
                .keyManager(clientKeyPair.getPrivate(), clientCert)
                .trustManager(DDDTLSUtil.trustManager)
                .build();
        var channel = NettyChannelBuilder.forAddress(host, port)
                .useTransportSecurity()
                .intercept(new NettyClientCertificateInterceptor())
                .sslContext(sslClientContext)
                .build();

        var certCompletion = new CompletableFuture<X509Certificate>();
        var stub = maker.apply(channel);
        stub = NettyClientCertificateInterceptor.createServerCertificateOption(stub, certCompletion);
        return new NettyStubWithCertificate<>(stub, certCompletion);
    }

    public static ManagedChannel createGrpcChannel(KeyPair clientKeyPair,
                                                   X509Certificate clientCert,
                                                   String host,
                                                   int port) throws SSLException {
        var sslClientContext = GrpcSslContexts.forClient()
                .keyManager(clientKeyPair.getPrivate(), clientCert)
                .trustManager(DDDTLSUtil.trustManager)
                .build();

        return NettyChannelBuilder.forAddress(host, port).useTransportSecurity().sslContext(sslClientContext).build();
    }
}