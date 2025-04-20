package net.discdd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "cli", subcommands = { DecryptBundle.class,
                                       EncryptBundle.class,
                                       SelfSignedCertCreator.class,
                                       JavaKeyCreator.class })
public class Main implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("Subcommand needed: 'encrypt' or 'decrypt'");
        return 0;
    }

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}