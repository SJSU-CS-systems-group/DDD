package net.discdd.server;

import java.io.File;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import net.discdd.server.config.BundleServerConfig;

@Component
public class StartupRunner implements CommandLineRunner {

    @Autowired
    private BundleServerConfig bundleStoreConfig;

    @Override
    public void run(String... args) throws Exception {
        this.setUpFileStore();

    }

    private void setUpFileStore() {
        File bundleReceivedDir = this.bundleStoreConfig.getBundleTransmission().getBundleReceivedLocation().toFile();
        bundleReceivedDir.mkdirs();
        File bundleGenerationDir = this.bundleStoreConfig.getBundleTransmission()
                .getBundleGenerationDirectory()
                .toFile();
        bundleGenerationDir.mkdirs();
        File toBeBundledDir = this.bundleStoreConfig.getBundleTransmission().getToBeBundledDirectory().toFile();
        toBeBundledDir.mkdirs();
        File tosendDir = this.bundleStoreConfig.getBundleTransmission().getToSendDirectory().toFile();
        tosendDir.mkdirs();
        File receivedProcessingDir = this.bundleStoreConfig.getBundleTransmission()
                .getReceivedProcessingDirectory()
                .toFile();
        receivedProcessingDir.mkdirs();

        File unCompressedPayloadDir = this.bundleStoreConfig.getBundleTransmission()
                .getUncompressedPayloadDirectory()
                .toFile();
        unCompressedPayloadDir.mkdirs();
        File compressedPayloadDir = this.bundleStoreConfig.getBundleTransmission()
                .getCompressedPayloadDirectory()
                .toFile();
        compressedPayloadDir.mkdirs();
        File encryptedPayloadDir = this.bundleStoreConfig.getBundleTransmission()
                .getEncryptedPayloadDirectory()
                .toFile();
        encryptedPayloadDir.mkdirs();
    }
}
