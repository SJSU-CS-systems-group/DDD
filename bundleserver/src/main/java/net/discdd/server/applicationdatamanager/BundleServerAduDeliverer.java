package net.discdd.server.applicationdatamanager;

import com.google.protobuf.ByteString;
import io.grpc.ManagedChannelBuilder;
import net.discdd.server.AppData;
import net.discdd.server.AppDataUnit;
import net.discdd.server.ServiceAdapterGrpc;
import net.discdd.server.repository.RegisteredAppAdapterRepository;
import net.discdd.utils.StoreADUs;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;
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
    private final HashMap<String, AppState> apps = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    BundleServerAduDeliverer(StoreADUs sendFolder, StoreADUs receiveFolder,
                             RegisteredAppAdapterRepository registeredAppAdapterRepository) {
        this.sendFolder = sendFolder;
        this.receiveFolder = receiveFolder;
        this.registeredAppAdapterRepository = registeredAppAdapterRepository;
    }

    @PostConstruct
    public void init() {
        sendFolder.getAllClientApps().forEach(e -> addAppWithPendingData(e.appId(), e.appId()));
        registeredAppAdapterRepository.findAll().forEach(appAdapter -> {
            var stub = ServiceAdapterGrpc.newBlockingStub(
                    ManagedChannelBuilder.forTarget(appAdapter.getAddress()).usePlaintext().build());
            apps.put(appAdapter.getAppId(),
                     new AppState(appAdapter.getAppId(), Executors.newSingleThreadExecutor(), new HashSet<>(), stub));
        });
    }

    private synchronized void removeAppWithPendingData(String appId, String clientId) {
        final var appState = apps.get(appId);
        if (appState == null) {
            logger.log(SEVERE, appId + " is not a registered App! This isn't going to work");
            return;
        }
        appState.pendingClients.remove(clientId);
    }

    private synchronized void addAppWithPendingData(String appId, String clientId) {
        final var appState = apps.get(appId);
        if (appState == null) {
            logger.log(SEVERE, appId + " is not a registered App! This isn't going to work");
            return;
        }
        if (appState.pendingClients.contains(clientId)) {
            logger.log(FINE, "Client " + clientId + " is already pending for " + appId);
            return;
        }
        appState.executor.execute(() -> {
            contactServiceAdapterForClient(clientId, appState);
        });
    }

    private void contactServiceAdapterForClient(String clientId, AppState appState) {
        String appId = appState.appId;
        try {
            removeAppWithPendingData(appId, clientId);
            AppData.Builder appData = AppData.newBuilder().setClientId(clientId)
                    .setLastADUIdReceived(receiveFolder.getLastADUIdReceived(clientId, appId));
            long lastAduIdSent = 0;
            for (var adu : receiveFolder.getAppData(clientId, appId)) {
                long aduId = adu.getADUId();
                if (aduId > lastAduIdSent) {
                    lastAduIdSent = aduId;
                }
                var data = receiveFolder.getADU(clientId, appId, aduId);

                appData.addDataList(
                        AppDataUnit.newBuilder().setData(ByteString.copyFrom(data)).setAduId(aduId).build());
            }
            var recvData = appState.stub.saveData(appData.build());
            receiveFolder.deleteAllFilesUpTo(clientId, appId, lastAduIdSent);
            for (var dataUnit : recvData.getDataListList()) {
                sendFolder.addADU(clientId, appId, dataUnit.getData().toByteArray(), dataUnit.getAduId());
            }

        } catch (Exception e) {
            logger.log(SEVERE, "Failed to notify " + appId + " of delivered ADU for " + clientId, e);
            scheduler.schedule(() -> addAppWithPendingData(appId, clientId), 15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Override
    public void onAduDelivered(String clientId, Set<String> appId) {
        appId.forEach(app -> addAppWithPendingData(app, clientId));
    }

    record AppState(String appId, Executor executor, HashSet<String> pendingClients,
                    ServiceAdapterGrpc.ServiceAdapterBlockingStub stub) {}
}
