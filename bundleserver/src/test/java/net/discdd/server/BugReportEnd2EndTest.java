package net.discdd.server;

import net.discdd.bugreport.BugReport;
import net.discdd.client.bundletransmission.ClientBundleTransmission;
import net.discdd.grpc.BundleSenderType;
import net.discdd.model.Bundle;
import net.discdd.pathutils.ClientPaths;
import net.discdd.server.applicationdatamanager.AduStores;
import net.discdd.server.bundletransmission.ServerBundleTransmission;
import net.discdd.server.service.GrpcExecutor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A bug report queued by a real {@link ClientBundleTransmission} under {@link BugReport#APP_ID} rides the client's
 * bundle through a transport to the server, which stores it under {@code bugReports/<clientId>/<aduId>.txt}.
 */
@SpringBootTest(classes = { BundleServerApplication.class, End2EndTest.End2EndTestInitializer.class })
@TestMethodOrder(MethodOrderer.MethodName.class)
public class BugReportEnd2EndTest extends End2EndTest {
    private static final String TRANSPORT_ID = "bug-report-test-transport";

    @TempDir
    static Path clientRoot;
    @TempDir
    static Path workDir;

    static ClientBundleTransmission client;
    static String bugClientId;
    static int fileCounter;

    @Autowired
    ServerBundleTransmission serverBundleTransmission;
    @Autowired
    AduStores aduStores;
    @Autowired
    GrpcExecutor.GrpcTransactionalRunner transactionalRunner;
    @Value("${bundle-server.bundle-store-shared}")
    String bundleStoreShared;

    @BeforeAll
    static void setUpClient() throws Exception {
        var paths = new ClientPaths(clientRoot,
                                    Files.readAllBytes(serverIdentityKeyPath),
                                    Files.readAllBytes(serverSignedPreKeyPath),
                                    Files.readAllBytes(serverRatchetKeyPath));
        client = new ClientBundleTransmission(paths, adu -> {});
        bugClientId = client.getBundleSecurity().getClientSecurity().getClientID();
    }

    @Test
    void test01ReportFromFreshClientReachesServer() throws Exception {
        clientUploads("first report");

        assertEquals("first report", Files.readString(reportPath(1)));
        assertTrue(aduStores.getReceiveADUsStorage().getAppData(bugClientId, BugReport.APP_ID).isEmpty(),
                   "the handler consumed the ADU");
    }

    @Test
    void test02ServerRegistersAppIdAndClientDropsAckedReport() throws Exception {
        clientDownloads();

        assertTrue(client.applicationDataManager.getRegisteredAppIds().contains(BugReport.APP_ID),
                   () -> "registered: " + client.applicationDataManager.getRegisteredAppIds());
        assertTrue(client.applicationDataManager.sendADUsStorage.getAppData(null, BugReport.APP_ID).isEmpty(),
                   "the server's ack deletes the delivered report on the client");
    }

    @Test
    void test03LaterReportIsSentOnceAppIdListIsSet() throws Exception {
        clientUploads("second report");

        assertEquals("second report", Files.readString(reportPath(2)));
    }

    private Path reportPath(long aduId) {
        return Path.of(bundleStoreShared, "bugReports", bugClientId, aduId + ".txt");
    }

    /** the client queues a report and builds a bundle; a transport uploads a copy of it */
    private void clientUploads(String report) throws Exception {
        // the client reuses its last bundle unless an ADU is newer than it (millisecond mtimes)
        Thread.sleep(20);
        client.applicationDataManager.sendADUsStorage.addADU(null,
                                                              BugReport.APP_ID,
                                                              report.getBytes(StandardCharsets.UTF_8),
                                                              -1);
        Bundle bundle = client.generateBundleForTransmission();
        Path copy = workDir.resolve("upload-" + fileCounter++);
        Files.copy(bundle.getSource().toPath(), copy);
        inRpcTransaction(() -> {
            serverBundleTransmission.processBundleFile(copy.toFile(), BundleSenderType.TRANSPORT, TRANSPORT_ID);
            return null;
        });
    }

    /** a transport fetches the client's downstream bundle and the client processes it */
    private void clientDownloads() throws Exception {
        String bundleId = inRpcTransaction(() -> serverBundleTransmission.generateBundleForClient(bugClientId));
        Path copy = workDir.resolve("download-" + fileCounter++);
        Files.copy(serverBundleTransmission.getPathForBundleToSend(bugClientId, bundleId), copy);
        client.processReceivedBundle(TRANSPORT_ID, new Bundle(copy.toFile()));
    }

    /** runs a server call the way the gRPC executor runs every RPC: inside one transaction */
    private <T> T inRpcTransaction(Callable<T> call) throws Exception {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Exception>();
        transactionalRunner.executeInTransaction(() -> {
            try {
                result.set(call.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }
}
