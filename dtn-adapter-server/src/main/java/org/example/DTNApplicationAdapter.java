package org.example;

/*
* To get data from application and send to DTN bundle server using DTNApplicationClient.java
*
* */
public class DTNApplicationAdapter {
    public static void main(String[] args) {
        String target = "localhost:8980";
        if (args.length > 0) {
            if ("--help".equals(args[0])) {
                System.err.println("Usage: [target]");
                System.err.println("");
                System.err.println("  target  The server to connect to. Defaults to " + target);
                System.exit(1);
            }
            target = args[0];
        }

        //start adapter server

    }
}