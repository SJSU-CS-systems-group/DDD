package com.ddd.server.config;

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

    public String getRegisteredAppIds() {
        return this.registeredAppIds;
    }

    public void setRegisteredAppIds(String registeredAppIds) {
        this.registeredAppIds = registeredAppIds;
    }

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

    public static class ApplicationDataManager {
        private Long appDataSizeLimit;
        private Path registeredAppIdsPath;

        public Long getAppDataSizeLimit() {
            return this.appDataSizeLimit;
        }

        public void setAppDataSizeLimit(Long appDataSizeLimit) {
            this.appDataSizeLimit = appDataSizeLimit;
        }

        public Path getRegisteredAppIdsPath() {
            return this.registeredAppIdsPath;
        }

        public void setRegisteredAppIdsPath(Path registeredAppIdsPath) {
            this.registeredAppIdsPath = registeredAppIdsPath;
        }

        public static class StateManager {
            private String largestAduIdReceived;
            private String sentBundleDetails;
            private String largestAduIdDelivered;
            private String lastSentBundleStructure;

            public String getLargestAduIdReceived() {
                return this.largestAduIdReceived;
            }

            public void setLargestAduIdReceived(String largestAduIdReceived) {
                this.largestAduIdReceived = largestAduIdReceived;
            }

            public String getSentBundleDetails() {
                return this.sentBundleDetails;
            }

            public void setSentBundleDetails(String sentBundleDetails) {
                this.sentBundleDetails = sentBundleDetails;
            }

            public String getLargestAduIdDelivered() {
                return this.largestAduIdDelivered;
            }

            public void setLargestAduIdDelivered(String largestAduIdDelivered) {
                this.largestAduIdDelivered = largestAduIdDelivered;
            }

            public String getLastSentBundleStructure() {
                return this.lastSentBundleStructure;
            }

            public void setLastSentBundleStructure(String lastSentBundleStructure) {
                this.lastSentBundleStructure = lastSentBundleStructure;
            }
        }
    }

    public static class BundleSecurity {
        private Path serverKeyPath;

        public Path getServerKeyPath() {
            return serverKeyPath;
        }

        public void setServerKeyPath(Path serverKeyPath) {
            this.serverKeyPath = serverKeyPath;
        }
    }
}
