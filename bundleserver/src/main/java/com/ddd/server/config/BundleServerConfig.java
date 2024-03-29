package com.ddd.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("bundle-server")
public class BundleServerConfig {
  private String bundleStoreRoot;
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

  public BundleTransmission getBundleTransmission() {
    return this.bundleTransmission;
  }

  public void setBundleTransmission(BundleTransmission bundleTransmission) {
    this.bundleTransmission = bundleTransmission;
  }

  public ApplicationDataManager getApplicationDataManager() {
    return this.applicationDataManager;
  }

  public void setApplicationDataManager(ApplicationDataManager applicationDataManager) {
    this.applicationDataManager = applicationDataManager;
  }

  public String getBundleStoreRoot() {
    return this.bundleStoreRoot;
  }

  public void setBundleStoreRoot(String bundleStoreRoot) {
    this.bundleStoreRoot = bundleStoreRoot;
  }

  public BundleSecurity getBundleSecurity() {
    return bundleSecurity;
  }

  public void setBundleSecurity(BundleSecurity bundleSecurity) {
    this.bundleSecurity = bundleSecurity;
  }

  public static class BundleTransmission {
    private Long bundleSizeLimit;
    private String bundleReceivedLocation;
    private String bundleGenerationDirectory;
    private String toBeBundledDirectory;
    private String toSendDirectory;
    private String receivedProcessingDirectory;

    private String uncompressedPayloadDirectory;
    private String compressedPayloadDirectory;
    private String encryptedPayloadDirectory;

    public String getUncompressedPayloadDirectory() {
      return this.uncompressedPayloadDirectory;
    }

    public void setUncompressedPayloadDirectory(String uncompressedPayloadDirectory) {
      this.uncompressedPayloadDirectory = uncompressedPayloadDirectory;
    }

    public String getCompressedPayloadDirectory() {
      return this.compressedPayloadDirectory;
    }

    public void setCompressedPayloadDirectory(String compressedPayloadDirectory) {
      this.compressedPayloadDirectory = compressedPayloadDirectory;
    }

    public String getEncryptedPayloadDirectory() {
      return this.encryptedPayloadDirectory;
    }

    public void setEncryptedPayloadDirectory(String encryptedPayloadDirectory) {
      this.encryptedPayloadDirectory = encryptedPayloadDirectory;
    }

    public void setReceivedProcessingDirectory(String receivedProcessingDirectory) {
      this.receivedProcessingDirectory = receivedProcessingDirectory;
    }

    public Long getBundleSizeLimit() {
      return this.bundleSizeLimit;
    }

    public void setBundleSizeLimit(Long bundleSizeLimit) {
      this.bundleSizeLimit = bundleSizeLimit;
    }

    public String getBundleReceivedLocation() {
      return this.bundleReceivedLocation;
    }

    public void setBundleReceivedLocation(String bundleReceivedLocation) {
      this.bundleReceivedLocation = bundleReceivedLocation;
    }

    public String getBundleGenerationDirectory() {
      return this.bundleGenerationDirectory;
    }

    public void setBundleGenerationDirectory(String bundleGenerationDirectory) {
      this.bundleGenerationDirectory = bundleGenerationDirectory;
    }

    public String getToBeBundledDirectory() {
      return this.toBeBundledDirectory;
    }

    public void setToBeBundledDirectory(String toBeBundledDirectory) {
      this.toBeBundledDirectory = toBeBundledDirectory;
    }

    public String getToSendDirectory() {
      return this.toSendDirectory;
    }

    public void setToSendDirectory(String toSendDirectory) {
      this.toSendDirectory = toSendDirectory;
    }

    public String getReceivedProcessingDirectory() {
      return this.receivedProcessingDirectory;
    }
  }

  public static class ApplicationDataManager {
    private Long appDataSizeLimit;
    private String registeredAppIdsPath;

    public Long getAppDataSizeLimit() {
      return this.appDataSizeLimit;
    }

    public void setAppDataSizeLimit(Long appDataSizeLimit) {
      this.appDataSizeLimit = appDataSizeLimit;
    }

    public String getRegisteredAppIdsPath() {
      return this.registeredAppIdsPath;
    }

    public void setRegisteredAppIdsPath(String registeredAppIdsPath) {
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
    private String serverKeyPath;

    public String getServerKeyPath() {
      return serverKeyPath;
    }

    public void setServerKeyPath(String serverKeyPath) {
      this.serverKeyPath = serverKeyPath;
    }
  }
}
