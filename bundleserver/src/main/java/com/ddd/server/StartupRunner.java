package com.ddd.server;

import java.io.File;

import com.ddd.server.storage.MySQLConnection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import com.ddd.server.config.BundleServerConfig;
import com.ddd.server.service.BundleServerServiceImpl;

import io.grpc.Server;
import io.grpc.ServerBuilder;

@Component
public class StartupRunner implements CommandLineRunner {

  @Autowired private BundleServerConfig bundleStoreConfig;
  public int port=8080;
  @Autowired
  BundleServerServiceImpl bundleServerServiceImpl;

  @Autowired
  DTNCommunicationService dtnCommService;

  @Override
  public void run(String... args) throws Exception {
    this.setUpFileStore();
    ServerBuilder<?> serverBuilder = ServerBuilder.forPort(8080);
    Server server = serverBuilder.addService(dtnCommService)
                .addService(bundleServerServiceImpl)
                .build();
    server.start();
    
    if (server != null) {
        server.awaitTermination();
    }
  }

  private void setUpFileStore() {
    File bundleReceivedDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getBundleReceivedLocation());
    bundleReceivedDir.mkdirs();
    File bundleGenerationDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getBundleGenerationDirectory());
    bundleGenerationDir.mkdirs();
    File toBeBundledDir =
        new File(this.bundleStoreConfig.getBundleTransmission().getToBeBundledDirectory());
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
