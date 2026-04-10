package net.discdd.app.trick;

import net.discdd.config.GrpcSecurityConfig;
import net.discdd.grpc.GrpcServerRunner;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Import;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Properties;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HibernateJpaAutoConfiguration.class
})
@Import({ GrpcServerRunner.class, GrpcSecurityConfig.class })
public class TrickApplication {

    public static void main(String[] args) throws IOException {
        var app = new SpringApplication(TrickApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);

        String[] springArgs = args;
        if (args.length >= 1) {
            File propsFile = new File(args[0]);
            if (propsFile.isFile()) {
                var properties = new Properties();
                try (InputStream in = new FileInputStream(propsFile)) {
                    properties.load(in);
                }
                app.setDefaultProperties(properties);
                springArgs = Arrays.copyOfRange(args, 1, args.length);
            }
        }
        // If no file given or file not found: use application.properties from classpath (inside the JAR)
        app.run(springArgs);
    }
}
