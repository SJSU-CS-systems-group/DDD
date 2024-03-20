package com.ddd.server.commands.keygenerator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
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
    GenerateKeys generateKeys;

    @Autowired
    DecodePublicKey decodePublicKey;

    @Override
    public void run(String... args) throws Exception {
        // run picocli impl
        String command = args.length > 0 ? args[0] : null;

        if (command == null) {
            return;
        }
        
        if (command.equals("generate-keys")) {
            System.exit(new CommandLine(generateKeys, factory).execute(args));
        } else if (command.equals("decode-pub-key")) {
            System.exit(new CommandLine(decodePublicKey, factory).execute(args));
        }
    }
}