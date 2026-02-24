package net.discdd.app.cli;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.cli.Main;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class LocalAduSendReceiveTest {
    //NOTE!!! we assume a server is running on localhost:7778
    @TempDir
    private static Path serverKeysPath;

    @BeforeAll
    public static void setupServerKeys() {
        for (var keyfile : List.of(SecurityUtils.SERVER_IDENTITY_KEY,
                                   SecurityUtils.SERVER_SIGNED_PRE_KEY,
                                   SecurityUtils.SERVER_RATCHET_KEY)) {
            try (var is = LocalAduSendReceiveTest.class.getResourceAsStream("/server_keys/" + keyfile)) {
                if (is == null) {
                    throw new IOException("Resource not found: /server_keys/" + keyfile);
                }
                var targetPath = serverKeysPath.resolve(keyfile);
                Files.copy(is, targetPath);
            } catch (IOException e) {
                throw new RuntimeException("Failed to set up server keys: " + e.getMessage(), e);
            }
        }
    }

    @Test
    public void testAduSendReceive(@TempDir Path tempDir) {
        var mydir = tempDir.resolve("mydir");
        var result = clirun(new Main(), "bc", "exchange", mydir.toString());
        Assertions.assertEquals(2, result.exitCode);
        Assertions.assertTrue(result.err.contains("Problem reading"),
                              "Expected 'Problem reading' in error, got: " + result.err);

        try {
            new Socket("localhost", 7778).close();
        } catch (Exception e) {
            System.err.println("☣️ No server running on localhost:7778, skipping test: " + e.getMessage());
            return;
        }

        result = clirun(new Main(),
                        "bc",
                        "initializeStorage",
                        "--server-keys",
                        serverKeysPath.toString(),
                        mydir.toString(),
                        "--server",
                        "localhost:7778");
        System.out.println(result);
        Assertions.assertEquals(0, result.exitCode);
        Assertions.assertTrue(result.out.contains("complete"), "Expected 'complete' in output, got: " + result.out);
    }

    private static TestResult clirun(Object command, String... args) {
        var commandLine = new CommandLine(command);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();

        commandLine.setOut(new PrintWriter(out, true));
        commandLine.setErr(new PrintWriter(err, true));

        int exitCode = commandLine.execute(args);

        return new TestResult(exitCode, out.toString(), err.toString());
    }

    record TestResult(int exitCode, String out, String err) {}

}