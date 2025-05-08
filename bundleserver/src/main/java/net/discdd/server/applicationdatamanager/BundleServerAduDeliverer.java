package net.discdd.server.applicationdatamanager;

import com.google.protobuf.ByteString;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.grpc.PendingDataCheckRequest;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.model.ADU;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.tls.DDDTLSUtil;
import net.discdd.tls.GrpcSecurity;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@Component
public class BundleServerAduDeliverer implements ApplicationDataManager.AduDeliveredListener {
    private static final Logger logger = Logger.getLogger(BundleServerAduDeliverer.class.getName());
    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;
    private final StoreADUs sendFolder;
    private final StoreADUs receiveFolder;
    private final ConcurrentHashMap<String, AppState> apps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final Duration dataCheckInterval;
    private long grpcTimeout = 20_000; // milliseconds
    @Autowired
    private GrpcSecurity serverGrpcSecurity;

    public BundleServerAduDeliverer(AduStores aduStores,
                                    RegisteredAppAdapterRepository registeredAppAdapterRepository,
                                    @Value("${serviceadapter.datacheck.interval}") Duration dataCheckInterval) {
        this.sendFolder = aduStores.getSendADUsStorage();
        this.receiveFolder = aduStores.getReceiveADUsStorage();
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
        this.dataCheckInterval = dataCheckInterval;
    }

    synchronized private void revalidateApps() {
        var foundApps = new HashSet<String>();
        registeredAppAdapterRepository.findAll().forEach(appAdapter -> {
            foundApps.add(appAdapter.getAppId());
            if (!apps.containsKey(appAdapter.getAppId())) {
                try {
                    var sslClientContext = GrpcSslContexts.forClient()
                            .keyManager(serverGrpcSecurity.getGrpcKeyPair().getPrivate(),
                                    serverGrpcSecurity.getGrpcCert())
                            .trustManager(DDDTLSUtil.trustManager)
                            .build();
                    var channel = NettyChannelBuilder.forTarget(appAdapter.getAddress())
                            .useTransportSecurity()
                            .maxInboundMessageSize(53 * 1024 * 1024)
                            .sslContext(sslClientContext)
                            .build();

                    var stub = ServiceAdapterServiceGrpc.newStub(channel); // Use async stub
                    var blockingStub = ServiceAdapterServiceGrpc.newBlockingStub(channel); // Use async stub

                    apps.put(appAdapter.getAppId(),
                            new AppState(appAdapter.getAppId(),
                                    Executors.newSingleThreadExecutor(),
                                    new HashSet<>(),
                                    stub,
                                    blockingStub));
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        apps.keySet().removeIf(appId -> !foundApps.contains(appId));
        logger.log(INFO, "Revalidated apps " + apps.keySet());
    }

    @PostConstruct
    public void init() {
        revalidateApps();
        sendFolder.getAllClientApps().forEach(app -> addAppWithPendingData(app.appId(), app.clientId()));
        logger.log(INFO, "Checking for pending data from service adapters every " + dataCheckInterval);
        scheduler.scheduleWithFixedDelay(this::checkServiceAdapters,
                dataCheckInterval.toMillis(),
                dataCheckInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    private void checkServiceAdapters() {
        apps.forEach((appId, appState) -> {
            try {
                logger.info("Checking for pending data for " + appId + " at " + appState.stub.getChannel().authority());
                var checkResponse = appState.blockingStub.withDeadlineAfter(grpcTimeout, TimeUnit.MILLISECONDS)
                        .pendingDataCheck(PendingDataCheckRequest.getDefaultInstance());
                checkResponse.getClientIdList().forEach(clientId -> addAppWithPendingData(appId, clientId));
                logger.log(INFO, "Pending clients for " + appId + " " + checkResponse.getClientIdList());
            } catch (Throwable e) {
                logger.log(SEVERE,
                        "Failed to check for pending data for " + appId + " at " +
                                appState.stub.getChannel().authority(),
                        e);
            }
        });
    }

    private void addAppWithPendingData(String appId, String clientId) {
        if (!apps.containsKey(appId)) revalidateApps();
        final var appState = apps.get(appId);
        if (appState == null) {
            logger.log(SEVERE, "No service adapter for " + appId + " things are going to break");
        } else {
            addAppWithPendingData(appState, clientId);
        }
    }

    private void addAppWithPendingData(AppState appState, String clientId) {
        synchronized (appState.pendingClients) {
            if (appState.pendingClients.contains(clientId)) {
                logger.log(INFO, "Client " + clientId + " is already pending for " + appState.appId);
                return;
            }
            appState.executor.execute(() -> contactServiceAdapterForClient(clientId, appState));
            appState.pendingClients.add(clientId);
        }
    }

    private void contactServiceAdapterForClient(String clientId, AppState appState) {
        String appId = appState.appId;
        try {
            synchronized (appState.pendingClients) {
                appState.pendingClients.remove(clientId);
            }

            StreamObserver<ExchangeADUsResponse> responseObserver = new StreamObserver<ExchangeADUsResponse>() {
                @Override
                public void onNext(ExchangeADUsResponse response) {
                    try {
                        if (response.hasAdus()) {
                            AppDataUnit adu = response.getAdus();
                            sendFolder.addADU(clientId, appId, adu.getData().toByteArray(), adu.getAduId());
                            logger.log(INFO, "Received ADU ID: " + adu.getAduId() + " for clientId: " + clientId +
                                    " from " + appId);
                        }
                        if (response.hasLastADUIdReceived()) {
                            logger.log(INFO, "Server processed up to ADU ID: " + response.getLastADUIdReceived() +
                                    " for clientId: " + clientId);
                        }
                    } catch (Exception e) {
                        logger.log(SEVERE, "Error processing response for clientId: " + clientId, e);
                    }
                }

                @Override
                public void onError(Throwable t) {
                    logger.log(SEVERE, "Stream error for clientId: " + clientId + " appId: " + appId, t);
                    scheduler.schedule(() -> addAppWithPendingData(appState, clientId),
                            dataCheckInterval.toMillis(),
                            TimeUnit.MILLISECONDS);
                }

                @Override
                public void onCompleted() {
                    logger.log(INFO, "Stream completed for clientId: " + clientId + " appId: " + appId);
                }
            };

            StreamObserver<ExchangeADUsRequest> requestObserver =
                    appState.stub.withDeadlineAfter(grpcTimeout, TimeUnit.MILLISECONDS).exchangeADUs(responseObserver);

            requestObserver.onNext(ExchangeADUsRequest.newBuilder().setClientId(clientId).build());

            long lastADUIdReceived = sendFolder.getLastADUIdAdded(clientId, appId);
            requestObserver.onNext(ExchangeADUsRequest.newBuilder().setLastADUIdReceived(lastADUIdReceived).build());

            long lastAduIdSent = 0;
            List<ADU> adus = receiveFolder.getAppData(clientId, appId);
            for (var adu : adus) {
                long aduId = adu.getADUId();
                if (aduId > lastAduIdSent) {
                    lastAduIdSent = aduId;
                }
                byte[] data = receiveFolder.getADU(clientId, appId, aduId);
                AppDataUnit appDataUnit = AppDataUnit.newBuilder()
                        .setAduId(aduId)
                        .setData(ByteString.copyFrom(data))
                        .build();
                requestObserver.onNext(ExchangeADUsRequest.newBuilder().setAdus(appDataUnit).build());
                logger.log(INFO, "Sent ADU ID: " + aduId + " for clientId: " + clientId + " to " + appId);
            }

            requestObserver.onCompleted();

            if (lastAduIdSent > 0) {
                receiveFolder.deleteAllFilesUpTo(clientId, appId, lastAduIdSent);
                logger.log(INFO, "Deleted ADUs up to ID: " + lastAduIdSent + " for clientId: " + clientId);
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to contact " + appId + " for " + clientId, e);
            scheduler.schedule(() -> addAppWithPendingData(appState, clientId),
                    dataCheckInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onAduDelivered(String clientId, Set<String> appId) {
        appId.forEach(app -> addAppWithPendingData(app, clientId));
    }

    record AppState(String appId,
                    Executor executor,
                    HashSet<String> pendingClients,
                    ServiceAdapterServiceGrpc.ServiceAdapterServiceStub stub,
                    ServiceAdapterServiceGrpc.ServiceAdapterServiceBlockingStub blockingStub
    ) {}
}