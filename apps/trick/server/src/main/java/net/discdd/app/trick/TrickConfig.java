package net.discdd.app.trick;

import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.file.Path;
import java.util.concurrent.Executor;

@Configuration
public class TrickConfig {

    @Bean
    public StoreADUs sendStoreADUs(@Value("${adapter-server.root-dir}") Path rootDir) {
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
