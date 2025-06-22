package net.discdd.app.k9;

import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class K9Config {
    @Bean
    public StoreADUs sendStoreADUs(@Value("${adapter-server.rootdir}") Path rootDir) {
        return new StoreADUs(rootDir.resolve("send"), true);
    }
}
