//package net.discdd.server.config;
//
//import com.google.common.net.InetAddresses;
//import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
//import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
//import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
//import net.devh.boot.grpc.server.config.GrpcServerProperties;
//import net.devh.boot.grpc.server.serverfactory.AbstractGrpcServerFactory;
//import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
//import net.discdd.server.service.BundleServerExchangeServiceImpl;
//import net.discdd.server.service.BundleServerServiceImpl;
//import net.discdd.tls.DDDTLSUtil;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.core.io.Resource;
//
//import java.io.IOException;
//import java.net.InetSocketAddress;
//import java.util.List;
//import java.util.logging.Logger;
//
//public class GrpcServerSecurityConfig extends AbstractGrpcServerFactory<NettyServerBuilder> {
//    private static final Logger logger = Logger.getLogger(GrpcServerSecurityConfig.class.getName());
//    @Autowired
//    private GrpcServerProperties grpcServerProperties;
//    @Autowired
//    private BundleServerServiceImpl bundleServerService;
//    @Autowired
//    private BundleServerExchangeServiceImpl bundleServerExchangeService;
//
//    protected GrpcServerSecurityConfig(GrpcServerProperties properties, List<GrpcServerConfigurer> serverConfigurers) {
//        super(properties, serverConfigurers);
//    }
//
//    @Override
//    protected NettyServerBuilder newServerBuilder() {
//        final String address = getAddress();
//        final int port = getPort();
//        return NettyServerBuilder.forAddress(new InetSocketAddress(InetAddresses.forString(address), port));
//    }
//
//    protected static SslContextBuilder newServerSslContextBuilder(final GrpcServerProperties.Security security) throws IOException {
//        final Resource privateKey = security.getPrivateKey();
//        final Resource certificateChain = security.getCertificateChain();
//
//        return GrpcSslContexts.configure(SslContextBuilder.forServer(certificateChain.getFile(), privateKey.getFile()))
//                .trustManager(DDDTLSUtil.trustManager);
//    }
////    @ConditionalOnBean(ShadedNettyGrpcServerFactory.class)
////    @ConditionalOnProperty(value = "grpc.server.security.enabled", havingValue = "true")
////    @Bean
////    public SslContext sslContext(GrpcServerProperties properties) throws Exception {
////        File certFile = new File("C:\\Users\\ngngo\\OneDrive\\Desktop\\ddd-research\\Data\\BundleSecurity\\Keys\\Server\\Server_Keys\\server.crt");
////        File keyFile = new File("C:\\Users\\ngngo\\OneDrive\\Desktop\\ddd-research\\Data\\BundleSecurity\\Keys\\Server\\Server_Keys\\serverJava.pvt");
////
////        return GrpcSslContexts.configure(SslContextBuilder.forServer(certFile, keyFile))
////                .trustManager(DDDTLSUtil.trustManager)
////                .clientAuth(REQUIRE)
////                .build();
////    }
////    @ConditionalOnBean(ShadedNettyGrpcServerFactory.class)
////    @ConditionalOnProperty(value = "grpc.server.security.enabled", havingValue = "true")
////    @Bean
////    public GrpcServerConfigurer serverConfigurer(SslContext sslContext) {
////        return serverBuilder -> {
////            if (serverBuilder instanceof NettyServerBuilder) {
////                try {
////                    ((NettyServerBuilder) serverBuilder)
////                            .forPort(grpcServerProperties.getPort())
////                            .addService(bundleServerService)
////                            .addService(bundleServerExchangeService)
////                            .sslContext(sslContext(grpcServerProperties))
////                            .intercept(new NettyServerCertificateInterceptor());
////                } catch (Exception e) {
////                    throw new RuntimeException(e);
////                }
////            } else {
////                logger.log(SEVERE, "Unable to configure SSL context for server builder: " + serverBuilder.getClass().getName());
////            }
////        };
////    }
//
////    @Bean
////    public ShadedNettyGrpcServerFactory grpcServerFactory(GrpcServerProperties properties, GrpcServerConfigurer serverConfigurer) {
////        return new ShadedNettyGrpcServerFactory(properties, (List<GrpcServerConfigurer>) serverConfigurer);
////    }
//
////    @Bean
////    public GrpcServerLifecycle shadedNettyGrpcServerLifecycle(ShadedNettyGrpcServerFactory serverFactory) {
////        Server lifecycle = serverFactory.createServer();
////    }
//
//}
