package com.ddd.server.commands;

import org.reflections.Reflections;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * Preprocessor to figure out if we are being called with a command
 */
public class CommandProcessor {
    static Map<String, ? extends Class<?>> commands;

    public static boolean checkForCommand(String[] args) {
        String command = args.length > 0 ? args[0] : null;

        if (command == null) {
            return false;
        }

        final boolean doingHelp = command.equals("--help") || command.equals("-h");
        if (doingHelp) {
            System.out.println("Available commands:");
        }

        // find all classes annotated with @Command
        Reflections reflections = new Reflections(CommandProcessor.class.getPackageName());
        commands = reflections.getTypesAnnotatedWith(Command.class).stream().map(c -> {
            var a = c.getAnnotation(Command.class);
            if (doingHelp) {
                System.out.println(a.name() + " - " + String.join(" ", a.description()));
            }
            return Map.entry(a.name(), c);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        if (doingHelp) System.exit(0);
        return commands.containsKey(command);
    }
}
