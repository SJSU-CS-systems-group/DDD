package net.discdd.server.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * This crazy code is used to execute gRPC calls in a transactional context.
 * Without it, code will mostly work, but some database operations will fail.
 */
@Component
@Qualifier("grpcExecutor")
public class GrpcExecutor implements Executor {
    private static final Logger logger = Logger.getLogger(GrpcExecutor.class.getName());

    // use a Spring-managed ThreadPoolTaskExecutor to handle gRPC calls
    // don't forget to initialize it in the constructor!
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    // this loooks really weird since the implementation is in this class! but ...
    // ... after a ton of chatGPTing ... reading doc ... debugging ... i landed on
    // this solution. key requirements:
    // 1. Runnables run in a spring executor
    // 2. They pass through a @Transactional method
    // 3. That transactional method is on a Spring-managed bean (not created with new)
    // 4. the bean cannot be the executor itself, which seems really weird to me,
    //    but i experimented with it alot and it doesn't work!
    private final GrpcTransactionalRunner grpcTransactionalRunner;

    public GrpcExecutor(GrpcTransactionalRunner grpcTransactionalRunner) {
        this.grpcTransactionalRunner = grpcTransactionalRunner;
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(100);
        executor.initialize();
    }
    @Override
    public void execute(Runnable command) {
        try {
            executor.execute(() -> grpcTransactionalRunner.executeInTransaction(command));
        } catch (Exception e) {
            // Log the exception or handle it as needed
            logger.severe("Error executing command: " + e.getMessage());
        }
    }

    @Component
    public static class GrpcTransactionalRunner {
        @Transactional
        public void executeInTransaction(Runnable command) {
            // This method is used to ensure that the command runs in a transaction
            command.run();
        }
    }
}