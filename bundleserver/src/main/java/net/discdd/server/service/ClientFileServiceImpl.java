package net.discdd.server.service;

import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.bundlerouting.service.FileServiceImpl;
import net.discdd.bundletransport.service.BundleDownloadRequest;
import net.discdd.bundletransport.service.BundleDownloadResponse;
import net.discdd.bundletransport.service.BundleSender;
import net.discdd.bundletransport.service.BundleSenderType;
import net.discdd.server.bundlesecurity.ServerSecurity;
import net.discdd.server.bundletransmission.BundleTransmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;

@GrpcService
public class ClientFileServiceImpl extends FileServiceImpl {
    @Value("${bundle-server.bundle-store-shared}")
    private String serverBasePath;
    @Autowired
    private ServerSecurity serverSecurity;
    @Autowired
    BundleTransmission bundleTransmission;
    @Autowired
    BundleServerServiceImpl bundleServerService;
    private static final Logger logger = Logger.getLogger(ClientFileServiceImpl.class.getName());

    @PostConstruct
    private void init() {
        logger.log(Level.INFO, "inside ClientFileServiceImpl init method");
        this.SERVER_BASE_PATH = Path.of(serverBasePath);
        this.sender = BundleSender.newBuilder().setType(BundleSenderType.SERVER).setId(serverSecurity.getServerId()).build();
        this.downloadingFrom = "send";
        this.uploadingTo = "receive";
        this.setProcessBundle(this::settingProcessBundle);
        this.setGenerateBundle(this::settingGenerateBundle);
    }

    public ClientFileServiceImpl() {
        super(null, null, null);
    }

    public void settingProcessBundle() {
        bundleTransmission.processReceivedBundles(BundleSender.newBuilder().setType(BundleSenderType.CLIENT)
                                                          .setId("noname").build());
    }

    public void settingGenerateBundle() {
        BundleDownloadRequest request =
                BundleDownloadRequest.newBuilder().setSender(sender).addAllBundleList(Collections.singleton(this.bundleToDownload)).build();
        StreamObserver<BundleDownloadResponse> downloadObserver = new StreamObserver<BundleDownloadResponse>() {
            @Override
            public void onNext(BundleDownloadResponse bundleDownloadResponse) {

                logger.log(Level.FINE, "onNext: called with " + bundleDownloadResponse.toString());
                if (bundleDownloadResponse.hasBundleList()) {
                    logger.log(Level.FINE, "Got list for deletion");
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.log(Level.SEVERE, "Error downloading file: " + throwable.getMessage(), throwable);
            }

            @Override
            public void onCompleted() {
                logger.log(Level.INFO, "File download complete");
            }
        };
        bundleServerService.downloadBundle(request, downloadObserver);
    }
}
