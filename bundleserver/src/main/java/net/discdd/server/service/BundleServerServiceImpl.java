package net.discdd.server.service;

import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.BundleInventoryRequest;
import net.discdd.grpc.BundleInventoryResponse;
import net.discdd.grpc.BundleServerServiceGrpc;
import net.discdd.grpc.CheckMessagesRequest;
import net.discdd.grpc.CheckMessagesResponse;
import net.discdd.grpc.CrashReportRequest;
import net.discdd.grpc.CrashReportResponse;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GrpcService;
import net.discdd.grpc.ServerMessage;
import net.discdd.grpc.Status;
import net.discdd.server.repository.TransportMessageRepository;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.NettyServerCertificateInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

@GrpcService
public class BundleServerServiceImpl extends BundleServerServiceGrpc.BundleServerServiceImplBase {
    private String ReceiveDir;
    private String SendDir;
    private String crashDir;
    private static final Logger logger = Logger.getLogger(BundleServerServiceImpl.class.getName());

    @Autowired
    private ServerBundleTransmission bundleTransmission;

    @Autowired
    private TransportMessageRepository transportMessageRepository;

    @PostConstruct
    private void init() {
        java.io.File directoryReceive = new java.io.File(ReceiveDir);
        if (!directoryReceive.exists()) {
            directoryReceive.mkdirs();
        }

        java.io.File directorySend = new java.io.File(SendDir);
        if (!directorySend.exists()) {
            directorySend.mkdirs();
        }
    }

    @Value("${bundle-server.bundle-store-shared}")
    public void setDir(String bundleDir) {
        ReceiveDir = bundleDir + java.io.File.separator + "receive";
        SendDir = bundleDir + java.io.File.separator + "send";
        crashDir = bundleDir + java.io.File.separator + "crashReports";
    }

    @Override
    public void bundleInventory(BundleInventoryRequest request,
                                StreamObserver<BundleInventoryResponse> responseObserver) {
        var serverBundlesOnTransport = request.getBundlesFromServerOnTransportList()
                .stream()
                .map(EncryptedBundleId::getEncryptedId)
                .collect(Collectors.toSet());

        var deletionList = new ArrayList<EncryptedBundleId>();
        var downloadList = new ArrayList<EncryptedBundleId>();

        X509Certificate clientCert = NettyServerCertificateInterceptor.CLIENT_CERTIFICATE_KEY.get(Context.current());
        String senderId = DDDTLSUtil.publicKeyToName(clientCert.getPublicKey());
        try {
            var bundlesToExchange = bundleTransmission.inventoryBundlesForTransmission(request.getSenderType(),
                                                                                       senderId,
                                                                                       serverBundlesOnTransport);
            bundlesToExchange.bundlesToDelete()
                    .stream()
                    .map(s -> EncryptedBundleId.newBuilder().setEncryptedId(s).build())
                    .forEach(deletionList::add);
            bundlesToExchange.bundlesToDownload()
                    .stream()
                    .map(s -> EncryptedBundleId.newBuilder().setEncryptedId(s).build())
                    .forEach(downloadList::add);
        } catch (Exception e) {
            logger.log(SEVERE, "Couldn't generate deletion list", e);
        }
        BundleInventoryResponse inventoryResponse = BundleInventoryResponse.newBuilder()
                .addAllBundlesToDelete(deletionList)
                .addAllBundlesToDownload(downloadList)
                .addAllBundlesToUpload(request.getBundlesFromClientsOnTransportList())
                .build();
        logger.info(String.format("%s to delete %s download %s upload %s",
                                  senderId,
                                  bundleListToString(inventoryResponse.getBundlesToDeleteList()),
                                  bundleListToString(inventoryResponse.getBundlesToDownloadList()),
                                  bundleListToString(inventoryResponse.getBundlesToUploadList())));
        responseObserver.onNext(inventoryResponse);
        responseObserver.onCompleted();
    }

    private static String bundleListToString(List<EncryptedBundleId> bundleList) {
        return bundleList.stream().map(EncryptedBundleId::getEncryptedId).collect(Collectors.joining(","));
    }

    @Override
    public void checkMessages(CheckMessagesRequest request, StreamObserver<CheckMessagesResponse> responseObserver) {
        X509Certificate clientCert = NettyServerCertificateInterceptor.CLIENT_CERTIFICATE_KEY.get(Context.current());
        String transportId = DDDTLSUtil.publicKeyToName(clientCert.getPublicKey());

        var messages = transportMessageRepository.findMessagesAfter(transportId, request.getLastMessageId());

        var responseBuilder = CheckMessagesResponse.newBuilder();
        messages.stream()
                .map(m -> ServerMessage.newBuilder()
                        .setMessageId(m.messageKey.getMessageNumber())
                        .setDate(m.messageDate.toString())
                        .setMessage(m.message)
                        .build())
                .forEach(responseBuilder::addServerMessage);

        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }

    @Override
    public void crashReports(CrashReportRequest request, StreamObserver<CrashReportResponse> response) {
        var data = request.getCrashReportData();
        X509Certificate clientCert = NettyServerCertificateInterceptor.CLIENT_CERTIFICATE_KEY.get(Context.current());
        var name = DDDTLSUtil.publicKeyToName(clientCert.getPublicKey());
        new File(crashDir).mkdirs();
        File crashFile = new File(crashDir, name);
        try {
            Files.write(crashFile.toPath(),
                        data.toByteArray(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            response.onNext(CrashReportResponse.newBuilder().setResult(Status.SUCCESS).build());
        } catch (IOException e) {
            logger.log(WARNING, "Couldn't write crash file to " + crashFile, e);
            response.onNext(CrashReportResponse.newBuilder().setResult(Status.FAILED).build());
        }
        response.onCompleted();
    }
}