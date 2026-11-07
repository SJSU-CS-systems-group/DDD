package net.discdd.server.bugreport;

import net.discdd.bugreport.BugReport;
import net.discdd.model.ADU;
import net.discdd.server.applicationdatamanager.InternalAppHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;

/**
 * The server end of the bug report channel ({@link BugReport#APP_ID}). Each upstream ADU is one report from
 * BundleClient; it is copied to {@code <bundle-store-shared>/bugReports/<clientId>/<aduId>.txt}, next to the
 * {@code crashReports} folder. The name is keyed by ADU ID, so a delivery retried after a failure rewrites the same
 * file instead of duplicating it. The user's description (without the attached logs) is also logged at INFO.
 * <p>
 * Every client gets the app ID ({@link #isRegisteredFor}): once a client has a downstream bundle it only uploads ADUs
 * for app IDs in its list, so a report queued on a client of a server without this handler stays queued.
 */
@Component
public class BugReportHandler implements InternalAppHandler {
    private static final Logger logger = Logger.getLogger(BugReportHandler.class.getName());
    static final String BUG_REPORTS_DIR = "bugReports";
    /** header of the logs section in reports formatted by android-core's BugReportViewModel */
    static final String LOGS_SECTION = "=== System Logs ===";
    static final int MAX_LOGGED_CHARS = 2000;

    private final Path bugReportsDir;

    public BugReportHandler(@Value("${bundle-server.bundle-store-shared}") String bundleStoreShared) {
        this.bugReportsDir = Path.of(bundleStoreShared, BUG_REPORTS_DIR);
    }

    @Override
    public String appId() {
        return BugReport.APP_ID;
    }

    @Override
    public boolean isRegisteredFor(String clientId) {
        return clientId != null;
    }

    @Override
    public void handleUpstream(String clientId, List<ADU> adus) throws IOException {
        Path clientDir = bugReportsDir.resolve(clientId);
        Files.createDirectories(clientDir);
        for (var adu : adus) {
            Path dest = clientDir.resolve(adu.getADUId() + ".txt");
            Files.copy(adu.getSource().toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            logger.log(INFO,
                       "[BugReports] received bug report " + adu.getADUId() + " from " + clientId + " -> " + dest +
                               "\n" + description(Files.readString(dest)));
        }
    }

    /**
     * The user's part of a report, for the log: everything before the attached logs section that BundleClient
     * appends, capped so a long report does not flood the terminal. The full report is in the stored file.
     */
    static String description(String report) {
        int logsAt = report.indexOf(LOGS_SECTION);
        String text = (logsAt < 0 ? report : report.substring(0, logsAt)).strip();
        return text.length() <= MAX_LOGGED_CHARS ? text : text.substring(0, MAX_LOGGED_CHARS) + " ... [see file]";
    }
}
