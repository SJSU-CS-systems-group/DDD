package net.discdd.viewmodels;

import android.content.Context;

/**
 * Where a submitted bug report goes. An app that can deliver reports (BundleClient queues them as ADUs) passes one to
 * {@code BugReportScreen}; without one the report is only appended to a local file on the device.
 */
public interface BugReportSink {
    /**
     * Hand off one formatted report (user text plus recent logs). Called off the main thread.
     *
     * @return true if the report was accepted for delivery
     */
    boolean submit(Context context, String report) throws Exception;
}
