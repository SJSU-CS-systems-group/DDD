package net.discdd.pathutils;

import net.discdd.utils.Constants;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class ClientPaths {
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

    public ClientPaths(Path rootDir) throws IOException {
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
    }
}
