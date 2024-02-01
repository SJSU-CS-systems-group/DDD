package com.ddd.server.keygenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import com.ddd.server.keygenerator.commands.GenerateKeys;

import picocli.CommandLine;
import picocli.CommandLine.IFactory;

@Component
public class CLRunner implements CommandLineRunner {
    @Autowired
    IFactory factory;

    @Autowired
    GenerateKeys generateKeys;

    @Override
    public void run(String... args) throws Exception {
        // run picocli impl
        String command = args.length > 0 ? args[0] : null;

        if (command == null) {
            return;
        }
        
        if (command.equals("generate-keys")) {
            new CommandLine(generateKeys, factory).execute(args);
        }
    }
}