package net.discdd.pathutils;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.utils.Constants;
import net.discdd.utils.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientPaths {
    // client security
    public final Path sessionStorePath;
    public final Path clientKeyPath;
    // client window
    public static final String CLIENT_WINDOW_SUBDIR = "ClientWindow";
    private static final String WINDOW_FILE = "clientWindow.csv";
    public final Path clientWindowDataPath;
    public final Path dbFile;

    // client bundle generator
    public final Path counterFilePath;
    public final java.io.File metadataFile;

    // client routing
    private static final String METADATAFILE = "routing.metadata";

    // BundleSecurity directory
    private static final String BUNDLE_ID_NEXT_COUNTER = "Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";

    /* Bundle generation directory */
    private static final String BUNDLE_GENERATION_DIRECTORY = "BundleTransmission/bundle-generation";
    private static final String TO_BE_BUNDLED_DIRECTORY = "to-be-bundled";
    private static final String TO_SEND_DIRECTORY = "to-send";
    private static final String UNCOMPRESSED_PAYLOAD = "uncompressed-payload";
    private static final String COMPRESSED_PAYLOAD = "compressed-payload";
    private static final String ENCRYPTED_PAYLOAD = "encrypted-payload";
    private static final String RECEIVED_PROCESSING = "received-processing";
    private static final String LARGEST_BUNDLE_ID_RECEIVED = "Shared/DB/LARGEST_BUNDLE_ID_RECEIVED.txt";
    private static final String RECEIVED_BUNDLES_DIRECTORY = "Shared/received-bundles";
    private static final String SENT_BUNDLE_DETAILS = "Shared/DB/SENT_BUNDLE_DETAILS.json";
    private static final String LAST_SENT_BUNDLE_STRUCTURE = "Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

    public static final Long APP_DATA_SIZE_LIMIT = 1000000000L;
    public static final Long BUNDLE_SIZE_LIMIT = 100_000_000L;

    public final Path ackRecordPath;
    public final Path crashReportPath;
    public final Path tosendDir;

    public final Path bundleGenerationDir;
    public final Path toBeBundledDir;
    public final Path uncompressedPayloadDir;
    public final Path compressedPayloadDir;
    public final Path encryptedPayloadDir;
    public final Path receivedProcDir;
    public final Path uncompressedPayloadStore;
    public final Path largestBundleIdReceived;
    public final Path receiveBundlePath;
    public final Path sendADUsPath;
    public final Path receiveADUsPath;
    public final Path sendBundleDetailsPath;

    public final Path bundleSecurityPath;
    public final Path serverKeyPath;
    public final Path bundleIdNextCounter;
    public final Path grpcSecurityPath;

    // initialize key paths
    public final Path serverIdentity;
    public final Path serverSignedPre;
    public final Path serverRatchet;

    /**
     * makes sure the keypaths are created in non disruptive manner except for the server keys which will
     * overwrite existing keys if they are provided. if the keys are null, they will be ignored.
     */
    public ClientPaths(Path rootDir, byte[] serverIdentity, byte[] serverSignedPre, byte[] serverRatchet) throws
            IOException {
        // Bundle generation directory
        bundleGenerationDir = rootDir.resolve(BUNDLE_GENERATION_DIRECTORY);
        toBeBundledDir = rootDir.resolve(TO_BE_BUNDLED_DIRECTORY);
        ackRecordPath = toBeBundledDir.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
        net.discdd.utils.FileUtils.createFileWithDefaultIfNeeded(ackRecordPath, "HB".getBytes());
        crashReportPath = toBeBundledDir.resolve(Constants.BUNDLE_CRASH_REPORT_FILE_NAME);
        net.discdd.utils.FileUtils.createFileWithDefaultIfNeeded(crashReportPath, "No report".getBytes());
        tosendDir = bundleGenerationDir.resolve(TO_SEND_DIRECTORY);
        tosendDir.toFile().mkdirs();

        uncompressedPayloadDir = bundleGenerationDir.resolve(UNCOMPRESSED_PAYLOAD);
        uncompressedPayloadDir.toFile().mkdirs();

        compressedPayloadDir = bundleGenerationDir.resolve(COMPRESSED_PAYLOAD);
        compressedPayloadDir.toFile().mkdirs();

        encryptedPayloadDir = bundleGenerationDir.resolve(ENCRYPTED_PAYLOAD);
        encryptedPayloadDir.toFile().mkdirs();

        receivedProcDir = bundleGenerationDir.resolve(RECEIVED_PROCESSING);
        receivedProcDir.toFile().mkdirs();

        uncompressedPayloadStore = rootDir.resolve(Paths.get(BUNDLE_GENERATION_DIRECTORY, RECEIVED_PROCESSING));
        largestBundleIdReceived = rootDir.resolve(LARGEST_BUNDLE_ID_RECEIVED);
        receiveBundlePath = rootDir.resolve(RECEIVED_BUNDLES_DIRECTORY);
        receiveBundlePath.toFile().mkdirs();

        // Application Data Manager
        sendADUsPath = rootDir.resolve("send");
        receiveADUsPath = rootDir.resolve("receive");
        sendBundleDetailsPath = rootDir.resolve(SENT_BUNDLE_DETAILS);

        // Bundle security directory
        bundleSecurityPath = rootDir.resolve(SecurityUtils.BUNDLE_SECURITY_DIR);
        serverKeyPath = bundleSecurityPath.resolve(SecurityUtils.SERVER_KEY_PATH);
        serverKeyPath.toFile().mkdirs();

        bundleIdNextCounter = rootDir.resolve(BUNDLE_ID_NEXT_COUNTER);
        FileUtils.createFileWithDefaultIfNeeded(bundleIdNextCounter, "0".getBytes());
        FileUtils.createFileWithDefaultIfNeeded(largestBundleIdReceived, "0".getBytes());

        // initialize key paths
        this.serverIdentity = serverKeyPath.resolve(SecurityUtils.SERVER_IDENTITY_KEY);
        if (serverIdentity != null) Files.write(this.serverIdentity, serverIdentity);
        this.serverSignedPre = serverKeyPath.resolve(SecurityUtils.SERVER_SIGNED_PRE_KEY);
        if (serverSignedPre != null) Files.write(this.serverSignedPre, serverSignedPre);
        this.serverRatchet = serverKeyPath.resolve(SecurityUtils.SERVER_RATCHET_KEY);
        if (serverRatchet != null) Files.write(this.serverRatchet, serverRatchet);

        // client routing
        var metaDataPath = rootDir.resolve("BundleRouting");
        metaDataPath.toFile().mkdirs();
        metadataFile = metaDataPath.resolve(METADATAFILE).toFile();

        // client bundle generator
        counterFilePath = rootDir.resolve(Paths.get("BundleRouting", "sentBundle.id"));

        // client window
        clientWindowDataPath = rootDir.resolve(CLIENT_WINDOW_SUBDIR);
        clientWindowDataPath.toFile().mkdirs();
        dbFile = clientWindowDataPath.resolve(WINDOW_FILE);

        // client security
        clientKeyPath = bundleSecurityPath.resolve(SecurityUtils.CLIENT_KEY_PATH);
        sessionStorePath = bundleSecurityPath.resolve(SecurityUtils.SESSION_STORE_FILE);
        grpcSecurityPath = rootDir.resolve(SecurityUtils.GRPC_SECURITY_PATH);
    }
}
