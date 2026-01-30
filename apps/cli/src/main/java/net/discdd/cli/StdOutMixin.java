package net.discdd.cli;

import java.io.PrintWriter;

import picocli.CommandLine;

public class StdOutMixin {
    @CommandLine.Spec
    public CommandLine.Model.CommandSpec spec;

    final public PrintWriter err() {
        return cmd().getErr();
    }

    @SuppressWarnings("")
    final public PrintWriter out() {
        return cmd().getOut();
    }

    final public CommandLine cmd() {
        return spec.commandLine();
    }
}
