package net.discdd.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "cli", subcommands = { DecryptBundle.class,
                                       EncryptBundle.class,
                                       LocalAduSendReceive.class,
                                       SelfSignedCertCreator.class,
                                       JavaKeyCreator.class }, mixinStandardHelpOptions = true)
public class Main {

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}