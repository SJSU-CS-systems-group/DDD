package net.discdd.bundleclient.service;

import android.content.Context;
import net.discdd.bugreport.BugReport;
import net.discdd.utils.StoreADUs;
import net.discdd.viewmodels.BugReportSink;

import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

/**
 * Queues a bug report as one upstream ADU under {@link BugReport#APP_ID}. It rides the next bundle through any
 * carrier to the server this client is bound to, where {@code BugReportHandler} stores it.
 */
public class ClientBugReportSink implements BugReportSink {
    public static final ClientBugReportSink INSTANCE = new ClientBugReportSink();
    private static final Logger logger = Logger.getLogger(ClientBugReportSink.class.getName());
    private static final byte[] TRUNCATED_MARKER = "\n[truncated]\n".getBytes(StandardCharsets.UTF_8);

    private ClientBugReportSink() {}

    @Override
    public synchronized boolean submit(Context context, String report) throws Exception {
        byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > BugReport.MAX_REPORT_BYTES) {
            byte[] cut = Arrays.copyOf(bytes, BugReport.MAX_REPORT_BYTES);
            System.arraycopy(TRUNCATED_MARKER,
                             0,
                             cut,
                             BugReport.MAX_REPORT_BYTES - TRUNCATED_MARKER.length,
                             TRUNCATED_MARKER.length);
            bytes = cut;
        }
        var file = sendStore(context).addADU(null, BugReport.APP_ID, bytes, -1);
        logger.log(INFO, "Queued bug report ADU " + file);
        return file != null;
    }

    /** the running client's send store, or the same folder opened directly (as MessageProvider does) */
    private static StoreADUs sendStore(Context context) {
        var service = BundleClientService.instance;
        var transmission = service == null ? null : service.getBundleTransmission();
        if (transmission != null) return transmission.applicationDataManager.sendADUsStorage;
        return new StoreADUs(Paths.get(context.getApplicationInfo().dataDir).resolve("send"));
    }
}
