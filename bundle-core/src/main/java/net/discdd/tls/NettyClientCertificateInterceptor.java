package net.discdd.tls;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.Grpc;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.stub.AbstractStub;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;

public class NettyClientCertificateInterceptor implements ClientInterceptor {
    public static final CallOptions.Key<CompletableFuture<X509Certificate>> SERVER_CERTIFICATE_OPTION = CallOptions.Key.create("server-certificate");

    public static <T extends AbstractStub<T>> T createServerCertificateOption(T stub, CompletableFuture<X509Certificate> certCompletion) {
        return stub.withOption(NettyClientCertificateInterceptor.SERVER_CERTIFICATE_OPTION, certCompletion);
    }


    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        return new ForwardingClientCall.SimpleForwardingClientCall<>(next.newCall(method, callOptions)) {
            @Override
            public void start(Listener<RespT> responseListener, Metadata headers) {
                super.start(new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(responseListener) {
                    @Override
                    public void onHeaders(Metadata headers) {
                        var sslSession = (SSLSession) getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
                        if (sslSession != null) {
                            try {
                                Certificate[] peerCertificates = sslSession.getPeerCertificates();
                                if (peerCertificates.length > 0) {
                                    X509Certificate serverCert = (X509Certificate) peerCertificates[0];
                                    callOptions.getOption(SERVER_CERTIFICATE_OPTION).complete(serverCert);
                                }
                            } catch (SSLPeerUnverifiedException e) {
                                e.printStackTrace();
                            }
                        }
                        super.onHeaders(headers);
                    }
                }, headers);
            }
        };
    }
}