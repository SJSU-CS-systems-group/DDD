package net.discdd.server.service;

import io.grpc.stub.StreamObserver;
import net.discdd.grpc.BundleInventoryRequest;
import net.discdd.grpc.BundleInventoryResponse;
import net.discdd.grpc.BundleServerServiceGrpc;
import net.discdd.grpc.EncryptedBundleId;
import net.discdd.grpc.GrpcService;
import net.discdd.server.bundletransmission.BundleTransmission;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.logging.Level.SEVERE;

@GrpcService()
public class BundleServerServiceImpl extends BundleServerServiceGrpc.BundleServerServiceImplBase {
    private static final Logger logger = Logger.getLogger(BundleServerServiceImpl.class.getName());
    @Autowired
    private BundleTransmission bundleTransmission;
    @Override
    public void bundleInventory(BundleInventoryRequest request,
                                StreamObserver<BundleInventoryResponse> responseObserver) {
        var serverBundlesOnTransport =
                request.getBundlesFromServerOnTransportList().stream().map(EncryptedBundleId::getEncryptedId)
                        .collect(Collectors.toSet());
        ;
        var deletionList = new ArrayList<EncryptedBundleId>();
        var downloadList = new ArrayList<EncryptedBundleId>();
        try {
            var bundlesToExchange =
                    bundleTransmission.inventoryBundlesForTransmission(request.getSender(), serverBundlesOnTransport);
            bundlesToExchange.bundlesToDelete().stream()
                    .map(s -> EncryptedBundleId.newBuilder().setEncryptedId(s).build()).forEach(deletionList::add);
            bundlesToExchange.bundlesToDownload().stream()
                    .map(s -> EncryptedBundleId.newBuilder().setEncryptedId(s).build()).forEach(downloadList::add);
        } catch (Exception e) {
            logger.log(SEVERE, "Couldn't generate deletion list", e);
        }
        responseObserver.onNext(BundleInventoryResponse.newBuilder().addAllBundlesToDelete(deletionList)
                                        .addAllBundlesToDownload(downloadList)
                                        .addAllBundlesToUpload(request.getBundlesFromClientsOnTransportList()).build());
        responseObserver.onCompleted();
    }
}