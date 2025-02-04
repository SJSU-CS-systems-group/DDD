//package net.discdd.server.config;
//
//import lombok.extern.slf4j.Slf4j;
//import net.devh.boot.grpc.server.autoconfigure.GrpcServerAutoConfiguration;
//import net.devh.boot.grpc.server.autoconfigure.GrpcServerFactoryAutoConfiguration;
//import net.devh.boot.grpc.server.condition.ConditionalOnInterprocessServer;
//import net.devh.boot.grpc.server.config.GrpcServerProperties;
//import net.devh.boot.grpc.server.serverfactory.GrpcServerConfigurer;
//import net.devh.boot.grpc.server.serverfactory.GrpcServerFactory;
//import net.devh.boot.grpc.server.serverfactory.GrpcServerLifecycle;
//import net.devh.boot.grpc.server.service.GrpcServiceDefinition;
//import net.devh.boot.grpc.server.service.GrpcServiceDiscoverer;
//import org.springframework.boot.autoconfigure.AutoConfigureAfter;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
//import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
//import org.springframework.context.ApplicationEventPublisher;
//import org.springframework.context.annotation.Bean;
//import org.springframework.context.annotation.Conditional;
//import org.springframework.context.annotation.Configuration;
//
//import java.util.List;
//import java.util.logging.Logger;
//
//import static java.util.logging.Level.INFO;
//
//@Slf4j
//@Configuration(proxyBeanMethods = false)
//@ConditionalOnMissingBean({GrpcServerFactory.class, GrpcServerLifecycle.class})
//@AutoConfigureAfter(GrpcServerAutoConfiguration.class)
//public class GrpcServerAutoConfig extends GrpcServerFactoryAutoConfiguration {
//    private static final Logger logger = Logger.getLogger(GrpcServerAutoConfig.class.getName());
//
//    @ConditionalOnClass(name = {"io.grpc.netty.shaded.io.netty.channel.Channel",
//            "io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder"})
//    @Conditional(ConditionalOnInterprocessServer.class)
//    @Bean
//    public GrpcServerSecurityConfig grpcServerSecurityConfig(
//            final GrpcServerProperties properties,
//            final GrpcServiceDiscoverer serviceDiscoverer,
//            final List<GrpcServerConfigurer> serverConfigurers) {
//
//        logger.log(INFO, "Detected custom-grpc-netty-shaded: Creating GrpcServerSecurityConfig");
//        final GrpcServerSecurityConfig factory = new GrpcServerSecurityConfig(properties, serverConfigurers);
//        for (final GrpcServiceDefinition service : serviceDiscoverer.findGrpcServices()) {
//            factory.addService(service);
//        }
//        return factory;
//    }
//
//    @ConditionalOnBean(GrpcServerSecurityConfig.class)
//    @Bean
//    public GrpcServerLifecycle shadedNettyGrpcServerLifecycle(
//            final GrpcServerSecurityConfig factory,
//            final GrpcServerProperties properties,
//            ApplicationEventPublisher eventPublisher) {
//
//        return new GrpcServerLifecycle(factory, properties.getShutdownGracePeriod(), eventPublisher);
//    }
//}
