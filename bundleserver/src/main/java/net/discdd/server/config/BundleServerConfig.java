package net.discdd.server.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConfigurationProperties("bundle-server")
@Getter
@Setter
public class BundleServerConfig {
    private Path bundleStoreRoot;
    private String registeredAppIds;
    private BundleTransmission bundleTransmission = new BundleTransmission();
    private ApplicationDataManager applicationDataManager = new ApplicationDataManager();
    private BundleSecurity bundleSecurity = new BundleSecurity();

    @Getter
    @Setter
    public static class BundleTransmission {
        private Long bundleSizeLimit;
        private Path bundleReceivedLocation;
        private Path bundleGenerationDirectory;
        private Path toBeBundledDirectory;
        private Path toSendDirectory;
        private Path receivedProcessingDirectory;
        private Path uncompressedPayloadDirectory;
        private Path compressedPayloadDirectory;
        private Path encryptedPayloadDirectory;
    }

    @Getter
    @Setter
    public static class ApplicationDataManager {
        private Long appDataSizeLimit;

        @Getter
        @Setter
        public static class StateManager {
            private String largestAduIdReceived;
            private String sentBundleDetails;
            private String largestAduIdDelivered;
            private String lastSentBundleStructure;
        }
    }

    @Getter
    @Setter
    public static class BundleSecurity {
        private Path serverKeyPath;
    }
}
