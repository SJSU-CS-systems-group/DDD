package net.discdd.model;

import java.util.List;
import java.util.Set;

public class BundleTransferDTO {

    private Set<String> deletionSet;

    private List<BundleDTO> bundles;

    public BundleTransferDTO(Set<String> deletionSet, List<BundleDTO> bundles) {
        this.deletionSet = deletionSet;
        this.bundles = bundles;
    }

    public Set<String> getDeletionSet() {
        return this.deletionSet;
    }

    public List<BundleDTO> getBundles() {
        return this.bundles;
    }
}
