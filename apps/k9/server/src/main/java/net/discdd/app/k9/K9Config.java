package net.discdd.app.k9;

import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Component
public class K9Config {
    @Bean
    public StoreADUs sendStoreADUs(@Value("${adapter-server.rootdir}") Path rootDir) {
        return new StoreADUs(rootDir.resolve("send"));
    }

    @Bean(name = "grpcExecutor")
    public Executor grpcExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.initialize();
        return executor;
    }
}
