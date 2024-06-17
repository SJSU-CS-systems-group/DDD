package com.ddd.server.commands;

import com.ddd.server.commands.bundlesecurity.EncryptBundle;
import com.ddd.server.commands.keygenerator.DecodePublicKey;
import com.ddd.server.commands.bundlesecurity.DecryptBundle;
import com.ddd.server.commands.keygenerator.GenerateKeys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import picocli.CommandLine;
import picocli.CommandLine.IFactory;


@Component
@Order(0)
public class CLRunner implements CommandLineRunner {
    @Autowired
    IFactory factory;

    @Autowired
    ApplicationContext context;

    @Override
    public void run(String... args) throws Exception {
        // run picocli impl
        String command = args.length > 0 ? args[0] : null;

        if (command == null) return;

        var clazz = CommandProcessor.commands.get(command);
        if (clazz == null) return;

        var bean = context.getBean(clazz);
        System.exit(new CommandLine(bean, factory).execute(args));
    }
}
