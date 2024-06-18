package com.ddd.server;

import com.ddd.server.config.BundleServerConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class StartupRunner implements CommandLineRunner {

    @Autowired
    private BundleServerConfig bundleStoreConfig;

    @Override
    public void run(String... args) throws Exception {
        this.setUpFileStore();

    }

    private void setUpFileStore() {
        File bundleReceivedDir = new File(this.bundleStoreConfig.getBundleTransmission().getBundleReceivedLocation());
        bundleReceivedDir.mkdirs();
        File bundleGenerationDir =
                new File(this.bundleStoreConfig.getBundleTransmission().getBundleGenerationDirectory());
        bundleGenerationDir.mkdirs();
        File toBeBundledDir = new File(this.bundleStoreConfig.getBundleTransmission().getToBeBundledDirectory());
        toBeBundledDir.mkdirs();
        File tosendDir = new File(this.bundleStoreConfig.getBundleTransmission().getToSendDirectory());
        tosendDir.mkdirs();
        File receivedProcessingDir =
                new File(this.bundleStoreConfig.getBundleTransmission().getReceivedProcessingDirectory());
        receivedProcessingDir.mkdirs();

        File unCompressedPayloadDir =
                new File(this.bundleStoreConfig.getBundleTransmission().getUncompressedPayloadDirectory());
        unCompressedPayloadDir.mkdirs();
        File compressedPayloadDir =
                new File(this.bundleStoreConfig.getBundleTransmission().getCompressedPayloadDirectory());
        compressedPayloadDir.mkdirs();
        File encryptedPayloadDir =
                new File(this.bundleStoreConfig.getBundleTransmission().getEncryptedPayloadDirectory());
        encryptedPayloadDir.mkdirs();
    }
}
