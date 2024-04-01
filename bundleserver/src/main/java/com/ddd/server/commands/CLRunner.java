package com.ddd.server.commands;

import com.ddd.server.commands.bundlesecurity.EncryptBundle;
import com.ddd.server.commands.keygenerator.DecodePublicKey;
import com.ddd.server.commands.bundlesecurity.DecryptBundle;
import com.ddd.server.commands.keygenerator.GenerateKeys;
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

    @Autowired
    DecryptBundle decryptBundle;

    @Autowired
    EncryptBundle encryptBundle;

    @Override
    public void run(String... args) throws Exception {
        // run picocli impl
        String command = args.length > 0 ? args[0] : null;

        if (command == null) {
            return;
        }

        switch (command) {
            case "generate-keys":
                System.exit(new CommandLine(generateKeys, factory).execute(args));
                break;
            case "decode-pub-key":
                System.exit(new CommandLine(decodePublicKey, factory).execute(args));
                break;
            case "decrypt-bundle":
                System.exit(new CommandLine(decryptBundle, factory).execute(args));
                break;
            case "encrypt-bundle":
                System.exit(new CommandLine(encryptBundle, factory).execute(args));
                break;
            default:
                break;
        }
        
//        if (command.equals("generate-keys")) {
//            System.exit(new CommandLine(generateKeys, factory).execute(args));
//        } else if (command.equals("decode-pub-key")) {
//            System.exit(new CommandLine(decodePublicKey, factory).execute(args));
//        } else if (command.equals("decrypt-bundle")) {
//            System.exit(new CommandLine(decryptBundle, factory).execute(args));
//        } else if (command.equals("encrypt-bundle")) {
//            System.exit(new CommandLine(encryptBundle, factory).execute(args));
//        }
    }
}