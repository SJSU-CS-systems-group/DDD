package net.discdd.server.applicationdatamanager;

import com.google.protobuf.ByteString;
import io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.NettyChannelBuilder;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.PendingDataCheckRequest;
import net.discdd.grpc.ServiceAdapterServiceGrpc;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.tls.DDDX509ExtendedTrustManager;
import net.discdd.tls.GrpcSecurityKey;
import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.net.ssl.SSLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

/**
 * Remember that this class has a different concept of "send" and "receive" folders than the original code.
 * Data in the "send" folder are going back to BundleClients.
 * Data in the "receive" folder are coming from BundleClients and going to the adapters.
 */
@Component
public class BundleServerAduDeliverer implements ApplicationDataManager.AduDeliveredListener {
    private static final Logger logger = Logger.getLogger(BundleServerAduDeliverer.class.getName());
    private final RegisteredAppAdapterRepository registeredAppAdapterRepository;
    private final StoreADUs sendFolder;
    private final StoreADUs receiveFolder;
    // appId to executor and stub
    // TODO: This should adapt to changes in the registered app adapters repository. currently it's only checked at
    //  startup
    private final ConcurrentHashMap<String, AppState> apps = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final Duration dataCheckInterval;
    private final Duration revalidateAppSpinDelay;
    private final long grpcTimeout = 20_000 /* milliseconds */;
    private final GrpcSecurityKey serverGrpcSecurityKey;

    BundleServerAduDeliverer(AduStores aduStores,
                             RegisteredAppAdapterRepository registeredAppAdapterRepository,
                             @Value("${serviceadapter.datacheck.interval}") Duration dataCheckInterval,
                             @Value("${serviceadapter.revalidate-delay:5s}") Duration revalidateAppSpinDelay,
                             GrpcSecurityKey serverGrpcSecurityKey) {
        this.sendFolder = aduStores.getSendADUsStorage();
        this.receiveFolder = aduStores.getReceiveADUsStorage();
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
        this.dataCheckInterval = dataCheckInterval;
        this.revalidateAppSpinDelay = revalidateAppSpinDelay;
        this.serverGrpcSecurityKey = serverGrpcSecurityKey;
    }

    private long lastRevalidateAppsMs;

    // Synchronized because we want this to be an atomic operation
    synchronized private void revalidateApps(boolean forceRevalidation) {
        long now = System.currentTimeMillis();
        if (!forceRevalidation && lastRevalidateAppsMs + revalidateAppSpinDelay.toMillis() > now) return;
        lastRevalidateAppsMs = now;
        var foundApps = new HashSet<String>();
        registeredAppAdapterRepository.findAll().forEach(appAdapter -> {
            foundApps.add(appAdapter.getAppId());
            var state = apps.get(appAdapter.getAppId());
            if (state == null || !state.addr.equals(appAdapter.getAddress())) {
                try {
                    var sslClientContext = GrpcSslContexts.forClient()
                            .keyManager(serverGrpcSecurityKey.grpcKeyPair.getPrivate(), serverGrpcSecurityKey.grpcCert)
                            .trustManager(new DDDX509ExtendedTrustManager(true))
                            .build();
                    var channel = NettyChannelBuilder.forTarget(appAdapter.getAddress())
                            .useTransportSecurity()
                            .maxInboundMessageSize(53 * 1024 * 1024)
                            .sslContext(sslClientContext)
                            .build();

                    var stub = ServiceAdapterServiceGrpc.newBlockingStub(channel);
                    apps.put(appAdapter.getAppId(),
                             new AppState(appAdapter.getAppId(),
                                          Executors.newSingleThreadExecutor(),
                                          // we might be reconnecting, so update the pending clients
                                          state == null ? new HashSet<>() : state.pendingClients,
                                          appAdapter.getAddress(),
                                          stub));
                } catch (SSLException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        // remove any apps that went away
        apps.keySet().removeIf(appId -> !foundApps.contains(appId));
        logger.log(INFO, "Revalidated apps " + apps.keySet());
    }

    // If the data transfer is currently queued for a client, remove it from pending because we are going
    // to send now

    @PostConstruct
    public void init() {
        revalidateApps(false);
        sendFolder.getAllClientApps().forEach(app -> addAppWithPendingData(app.appId(), app.clientId()));
        logger.log(INFO, "Checking for pending data from service adapters every " + dataCheckInterval);
        scheduler.scheduleWithFixedDelay(this::checkServiceAdapters,
                                         dataCheckInterval.toMillis(),
                                         dataCheckInterval.toMillis(),
                                         java.util.concurrent.TimeUnit.MILLISECONDS);

    }

    // Periodic checkin to see if the service adapters have data to send
    private void checkServiceAdapters() {
        apps.forEach((appId, appState) -> {
            try {
                logger.info("Checking for pending data for " + appId + " at " + appState.stub.getChannel().authority());
                var checkResponse = appState.stub.pendingDataCheck(PendingDataCheckRequest.getDefaultInstance());
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
        // if the app isn't registered, check to see if something changed
        if (!apps.containsKey(appId)) revalidateApps(true);
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
            var appData = ExchangeADUsRequest.newBuilder()
                    .setClientId(clientId)
                    .setLastADUIdReceived(sendFolder.getLastADUIdAdded(clientId, appId));
            long lastAduIdSent = 0;
            for (var adu : receiveFolder.getAppData(clientId, appId)) {
                long aduId = adu.getADUId();
                if (aduId > lastAduIdSent) {
                    lastAduIdSent = aduId;
                }
                var data = receiveFolder.getADU(clientId, appId, aduId);
                appData.addAdus(AppDataUnit.newBuilder().setData(ByteString.copyFrom(data)).setAduId(aduId).build());
            }

            logger.log(INFO,
                       "Sending " + appData.getAdusCount() + " ADUs" +
                               (appData.getAdusCount() > 0 ? " from ADU id " + appData.getAdus(0).getAduId() : "") +
                               " to " + appId + " for " + clientId + " on " + appState.stub.getChannel().authority());

            var recvData =
                    appState.stub.withDeadlineAfter(grpcTimeout, TimeUnit.MILLISECONDS).exchangeADUs(appData.build());
            receiveFolder.deleteAllFilesUpTo(clientId, appId, lastAduIdSent);
            logger.log(INFO,
                       "Receiving " + recvData.getAdusList().size() + " ADUs" + (recvData.getAdusList().size() > 0 ?
                                                                                 " from ADU id " + recvData.getAdus(0)
                                                                                         .getAduId() :
                                                                                 "") + " to " + appId + " for " +
                               clientId + " on " + appState.stub.getChannel().authority());
            for (var dataUnit : recvData.getAdusList()) {
                sendFolder.addADU(clientId, appId, dataUnit.getData().toByteArray(), dataUnit.getAduId());
            }
        } catch (Exception e) {
            logger.log(SEVERE, "Failed to contact " + appId + " for " + clientId, e);
            scheduler.schedule(() -> addAppWithPendingData(appState, clientId),
                               dataCheckInterval.toMillis(),
                               java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void onAduDelivered(String clientId, Set<String> appId) {
        appId.forEach(app -> addAppWithPendingData(app, clientId));
    }

    record AppState(String appId,
                    Executor executor,
                    HashSet<String> pendingClients,
                    String addr,
                    ServiceAdapterServiceGrpc.ServiceAdapterServiceBlockingStub stub) {}
}
