package net.discdd.model;

public record EncryptedBundleId(String encryptedId, String clientId, long bundleCounter) {}
