package com.ddd.model;

public class BundleDTO {

    private String bundleId;
    private Bundle bundle;





















    
    public String getBundleId() {
        return this.bundleId;
    }

    public Bundle getBundle() {
        return this.bundle;
    }

    public BundleDTO(String bundleId, Bundle bundle) {
        this.bundleId = bundleId;
        this.bundle = bundle;
    }
}
