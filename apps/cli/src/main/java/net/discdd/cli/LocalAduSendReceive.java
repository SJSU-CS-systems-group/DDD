package net.discdd.cli;

import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.client.bundletransmission.TransportDevice;
import net.discdd.model.ADU;
import net.discdd.pathutils.ClientPaths;
import net.discdd.utils.StoreADUs;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExecutionException;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static java.lang.String.format;
import static net.discdd.bundlesecurity.SecurityUtils.SERVER_IDENTITY_KEY;
import static net.discdd.bundlesecurity.SecurityUtils.SERVER_RATCHET_KEY;
import static net.discdd.bundlesecurity.SecurityUtils.SERVER_SIGNED_PRE_KEY;

@Command(name = "bc", description = "a command line bundle client", mixinStandardHelpOptions = true)
public class LocalAduSendReceive extends StdOutMixin {
    @Command(mixinStandardHelpOptions = true)
    public void initializeStorage(
            @Parameters(paramLabel = "<directory>", description = "Directory containing ADUs and keys", arity = "1")
            Path rootPath,

            @Option(names = "--server-keys", description = "Path to the server public key file", required = true)
            Path sourceServerKeyPath,
            @Option(names = "--server", description = "Address:port of the server to connect to " +
                    "(a connection will not be made right now", required = true) String serverAddress) throws
            ExecutionException {
        if (Files.exists(rootPath) && rootPath.toFile().list().length > 0) {
            throw new ParameterException(cmd(),
                                         format("There is already data in %s. Empty it to re-initialize.", rootPath));
        }

        var filesToCopy = List.of(SERVER_IDENTITY_KEY, SERVER_SIGNED_PRE_KEY, SERVER_RATCHET_KEY);
        var missing = filesToCopy.stream().filter(f -> !Files.exists(sourceServerKeyPath.resolve(f))).toList();
        if (!missing.isEmpty()) {
            throw new ParameterException(cmd(),
                                         format("Server key files not found under %s: %s",
                                                sourceServerKeyPath,
                                                String.join(",", missing)));
        }
        var serverKeys = rootPath.resolve(Path.of("BundleSecurity", "Server_Keys"));
        try {
            Files.createDirectories(rootPath);
        } catch (IOException e) {
            throw new ParameterException(cmd(),
                                         format("Problem creating data directory at %s: %s", rootPath, e.getMessage()),
                                         e);
        }

        try {
            Files.createDirectories(serverKeys);
            Files.copy(sourceServerKeyPath.resolve(SERVER_IDENTITY_KEY),
                       serverKeys.resolve(SERVER_IDENTITY_KEY),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceServerKeyPath.resolve(SERVER_SIGNED_PRE_KEY),
                       serverKeys.resolve(SERVER_SIGNED_PRE_KEY),
                       StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourceServerKeyPath.resolve(SERVER_RATCHET_KEY),
                       serverKeys.resolve(SERVER_RATCHET_KEY),
                       StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException e) {
            throw new ParameterException(cmd(), "Failed to copy server key: " + e.getLocalizedMessage(), e);
        }
        try {
            Files.writeString(getServerAddressPath(rootPath), serverAddress);
        } catch (IOException e) {
            throw new ExecutionException(cmd(), format("Could not write to %s", getServerAddressPath(rootPath)), e);
        }
        out().printf("Initialization of %s complete.%n", rootPath);
    }

    private Path getServerAddressPath(Path rootPath) {
        return rootPath.resolve("serverHostPort.txt");
    }

    public InetSocketAddress getAddressPort(Path rootPath) {
        String addressPort;
        try {
            addressPort = Files.readString(getServerAddressPath(rootPath)).strip();
        } catch (IOException e) {
            throw new ParameterException(cmd(),
                                         format("Problem reading server address:port from %s (did you run " +
                                                        "initialize?): %s", rootPath, e.getMessage()),
                                         e);
        }
        var lastColon = addressPort.lastIndexOf(':');
        if (lastColon == -1) {
            throw new ParameterException(cmd(), "Invalid server address format. Expected <address>:<port>");
        }
        String portString = addressPort.substring(lastColon + 1);
        int port;
        try {
            port = Integer.parseInt(portString);
            if (port < 1 || port > 65535) {
                throw new ParameterException(cmd(), "Port must be between 1 and 65535");
            }
        } catch (NumberFormatException e) {
            throw new ParameterException(cmd(), "Invalid port number: " + portString, e);
        }
        var serverAddress = addressPort.substring(0, lastColon);
        return new InetSocketAddress(serverAddress, port);
    }

    @Command(mixinStandardHelpOptions = true)
    public void exchange(
            @Parameters(paramLabel = "<directory>", description = "Directory containing ADUs and keys", arity = "1")
            Path rootPath

    ) throws Exception {
        var serverAddress = getAddressPort(rootPath);
        // we pass nulls for the key files since they are already set up
        var clientPaths = new ClientPaths(rootPath, null, null, null);
        var bundleTransmission = new ClientBundleTransmission(clientPaths, (ADU adu) -> {
            out().println("Received ADU: " + adu);
        });
        var bundleExchangeCounts = bundleTransmission.doExchangeWithTransport(TransportDevice.SERVER_DEVICE,
                                                                              serverAddress.getHostName(),
                                                                              serverAddress.getPort(),
                                                                              false);
        out().printf("Sent %s, received %s%n",
                     bundleExchangeCounts.downloadStatus(),
                     bundleExchangeCounts.uploadStatus());
        if (bundleExchangeCounts.uploadStatus() == ClientBundleTransmission.Statuses.FAILED ||
                bundleExchangeCounts.downloadStatus() == ClientBundleTransmission.Statuses.FAILED) {
            throw new ExecutionException(cmd(), "Exchange failed: Sent " +
                    bundleExchangeCounts.downloadStatus() + ", received " +
                    bundleExchangeCounts.uploadStatus(), bundleExchangeCounts.e());
        }
    }

    @Command(mixinStandardHelpOptions = true)
    public void addAdu(
            @Parameters(paramLabel = "<directory>", description = "Directory containing ADUs and keys", arity = "1")
            Path rootPath,

            @Parameters(paramLabel = "appid") String appId,
            @Parameters(paramLabel = "<adu>", description = "Files containing ADUs to add. - indicates " +
                    "stdin", arity = "1..*") String[] adus,
            @Option(names = "--source", description = "Source file for the ADU. Defaults to stdin if not " +
                    "specified") Path sourcePath,
            @Option(names = "--force", description = "Force overwrite of existing ADU with the same appId",
                    defaultValue = "false", showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
            boolean force) throws ExecutionException {
        try {
            var clientPaths = new ClientPaths(rootPath, null, null, null);
            var sendADUsStorage = new StoreADUs(clientPaths.sendADUsPath);
            for (String aduFile : adus) {
                var data = aduFile.equals("-") ? System.in.readAllBytes() : Files.readAllBytes(Path.of(aduFile));
                sendADUsStorage.addADU(null, appId, data, -1);
            }
        } catch (IOException e) {
            throw new ExecutionException(cmd(), "Failed to write ADU: " + e.getMessage(), e);
        }
    }
}
