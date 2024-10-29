package net.discdd.pathutils;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.utils.Constants;
import net.discdd.utils.FileUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientPaths {
    // client security
    public final Path sessionStorePath;
    public final Path clientKeyPath;
    // client window
    public static final String CLIENT_WINDOW_SUBDIR = "ClientWindow";
    private final String WINDOW_FILE = "clientWindow.csv";
    public final Path clientWindowDataPath;
    public final Path dbFile;

    // client bundle generator
    public final Path counterFilePath;
    public final java.io.File metadataFile;

    // client routing
    private final String METADATAFILE = "routing.metadata";

    // BundleSecurity directory
    public static final String BUNDLE_SECURITY_DIR = "BundleSecurity";
    private static final String BUNDLE_ID_NEXT_COUNTER = "Shared/DB/BUNDLE_ID_NEXT_COUNTER.txt";
    public static final String SERVER_IDENTITY_PUB = "server_identity.pub";
    public static final String SERVER_SIGNED_PRE_PUB = "server_signed_pre.pub";
    public static final String SERVER_RATCHET_PUB = "server_ratchet.pub";
    public static final String SERVER_KEYS_SUBDIR = "Server_Keys";

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

    private static String SENT_BUNDLE_DETAILS = "Shared/DB/SENT_BUNDLE_DETAILS.json";

    private static String LAST_SENT_BUNDLE_STRUCTURE = "Shared/DB/LAST_SENT_BUNDLE_STRUCTURE.json";

    public Long APP_DATA_SIZE_LIMIT = 1000000000L;
    public final long BUNDLE_SIZE_LIMIT = 100_000_000L;

    public final Path ackRecordPath;
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
    public final Path lastSentBundleStructurePath;

    // Bundle security directory
    public final Path bundleSecurityPath;
    public final Path serverKeyPath;
    public final Path bundleIdNextCounter;

    // initialize key paths
    public final Path outServerIdentity;
    public final Path outServerSignedPre;
    public final Path outServerRatchet;

    public ClientPaths(Path rootDir) throws IOException {
        // Bundle generation directory
        bundleGenerationDir = rootDir.resolve(BUNDLE_GENERATION_DIRECTORY);
        toBeBundledDir = rootDir.resolve(TO_BE_BUNDLED_DIRECTORY);
        ackRecordPath = toBeBundledDir.resolve(Constants.BUNDLE_ACKNOWLEDGEMENT_FILE_NAME);
        net.discdd.utils.FileUtils.createFileWithDefaultIfNeeded(ackRecordPath, "HB".getBytes());
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
        lastSentBundleStructurePath = rootDir.resolve(LAST_SENT_BUNDLE_STRUCTURE);

        // Bundle security directory
        bundleSecurityPath = rootDir.resolve(BUNDLE_SECURITY_DIR);
        serverKeyPath = bundleSecurityPath.resolve(SERVER_KEYS_SUBDIR);
        serverKeyPath.toFile().mkdirs();

        bundleIdNextCounter = rootDir.resolve(BUNDLE_ID_NEXT_COUNTER);
        FileUtils.createFileWithDefaultIfNeeded(bundleIdNextCounter, "0".getBytes());
        FileUtils.createFileWithDefaultIfNeeded(largestBundleIdReceived, "0".getBytes());

        // initialize key paths
        outServerIdentity = serverKeyPath.resolve(SERVER_IDENTITY_PUB);
        outServerSignedPre = serverKeyPath.resolve(SERVER_SIGNED_PRE_PUB);
        outServerRatchet = serverKeyPath.resolve(SERVER_RATCHET_PUB);

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
    }
}
