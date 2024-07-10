package net.discdd.cli;

import java.util.concurrent.Callable;

import net.discdd.cli.DecryptBundle;
import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "cli", subcommands = { DecryptBundle.class,
        EncryptBundle.class })
public class Main implements Callable<Integer> {

    @Override public Integer call () {
        System.out.println("Subcommand needed: 'encrypt' or 'decrypt'");
        return 0;
    }

    public static void main (String[]args){
        System.setProperty("java.util.logging.SimpleFormatter.format", "%5$s%6$s%n");
        int rc = new CommandLine(new Main()).execute(args);
        System.exit(rc);
    }
}