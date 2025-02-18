    package net.discdd.tls;

    import io.grpc.Context;
    import io.grpc.Contexts;
    import io.grpc.Grpc;
    import io.grpc.Metadata;
    import io.grpc.ServerCall;
    import io.grpc.ServerCallHandler;
    import io.grpc.ServerInterceptor;

    import javax.net.ssl.ExtendedSSLSession;
    import javax.net.ssl.SSLPeerUnverifiedException;
    import java.security.cert.X509Certificate;

    public class NettyServerCertificateInterceptor implements ServerInterceptor {
        public static final Context.Key<X509Certificate> CLIENT_CERTIFICATE_KEY = Context.key("client-certificate");
        @Override
        public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
            var sslSession = (ExtendedSSLSession) call.getAttributes().get(Grpc.TRANSPORT_ATTR_SSL_SESSION);
            if (sslSession != null) {
                try {
                    var peerCertificates = sslSession.getPeerCertificates();
                    if (peerCertificates.length > 0) {
                        X509Certificate clientCert = (X509Certificate) peerCertificates[0];
                        var ctx = Context.current().withValue(CLIENT_CERTIFICATE_KEY, clientCert);
                        return Contexts.interceptCall(ctx, call, headers, next);
                    }
                } catch (SSLPeerUnverifiedException e) {
                    e.printStackTrace();    
                    throw new RuntimeException("Client certificate not found " + e.getMessage());
                }
            }
            return next.startCall(call, headers);
        }
    }
