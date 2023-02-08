package com.ddd.model;

import java.util.List;
import java.util.Set;

public class BundleTransferDTO {

  private Set<String> deletionSet;

  private List<Bundle> bundles;

  public BundleTransferDTO(Set<String> deletionSet, List<Bundle> bundles) {
    this.deletionSet = deletionSet;
    this.bundles = bundles;
  }

  public Set<String> getDeletionSet() {
    return this.deletionSet;
  }

  public List<Bundle> getBundles() {
    return this.bundles;
  }
}
