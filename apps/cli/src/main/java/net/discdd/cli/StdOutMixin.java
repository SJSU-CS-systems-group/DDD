package net.discdd.cli;

import picocli.CommandLine;

import java.io.PrintWriter;

public class StdOutMixin {
    @CommandLine.Spec
    public CommandLine.Model.CommandSpec spec;

    final public PrintWriter err() {
        return cmd().getErr();
    }

    final public PrintWriter out() {
        return cmd().getOut();
    }

    final public CommandLine cmd() {
        return spec.commandLine();
    }
}
