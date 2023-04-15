package com.ddd.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties("bundle-server")
public class BundleServerConfig {
  private String bundleStoreRoot;
  private String registeredAppIds;
  private String dbRoot;
  
  public String getDbRoot() {
  return dbRoot;}

  public void setDbRoot(String dbRoot) {
  this.dbRoot = dbRoot;}

  public String getRegisteredAppIds() {
    return this.registeredAppIds;
  }

  public void setRegisteredAppIds(String registeredAppIds) {
    this.registeredAppIds = registeredAppIds;
  }

  private BundleTransmission bundleTransmission = new BundleTransmission();
  private ApplicationDataManager applicationDataManager = new ApplicationDataManager();

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

  public static class BundleTransmission {
    private Long bundleSizeLimit;
    private String bundleReceivedLocation;
    private String bundleGenerationDirectory;
    private String toBeBundledDirectory;
    private String toSendDirectory;

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
  }

  public static class ApplicationDataManager {
    private Long appDataSizeLimit;
    private String registeredAppIdsPath;

    private StateManager stateManager = new StateManager();
    
    public StateManager getStateManager() {
      return stateManager;
    }

    public void setStateManager(StateManager stateManager) {
      this.stateManager = stateManager;
    }

    private DataStoreAdaptor dataStoreAdaptor = new DataStoreAdaptor();
    
    public DataStoreAdaptor getDataStoreAdaptor() {
      return dataStoreAdaptor;
    }

    public void setDataStoreAdaptor(DataStoreAdaptor dataStoreAdaptor) {
      this.dataStoreAdaptor = dataStoreAdaptor;
    }

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

    public static class DataStoreAdaptor {
      private String appDataRoot;

      public String getAppDataRoot() {
        return this.appDataRoot;
      }

      public void setAppDataRoot(String appDataRoot) {
        this.appDataRoot = appDataRoot;
      }
    }
  }
}
