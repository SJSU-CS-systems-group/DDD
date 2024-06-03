package com.ddd.model;

public class BundleDTO {
    private String senderClientId;
    private String bundleId;
    private Bundle bundle;

    public String getBundleId() {
        return this.bundleId;
    }

    public Bundle getBundle() {
        return this.bundle;
    }

    public BundleDTO(String senderClientId, String bundleId, Bundle bundle) {
        this.senderClientId = senderClientId;
        this.bundleId = bundleId;
        this.bundle = bundle;
    }
}
