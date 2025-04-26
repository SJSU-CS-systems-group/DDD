package net.discdd.adapter;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DDDClientAdapter extends BroadcastReceiver {
    public static final String BROADCAST_ACTION = "android.intent.dtn.DATA_RECEIVED";
    public static final String PROVIDER_NAME = "net.discdd.provider.datastoreprovider";
    public static final Uri PROVIDER_URI;
    static {
        PROVIDER_URI = Uri.parse("content://" + PROVIDER_NAME + "/messages");
    }
    public static final int MAX_ADU_SIZE = 512*1024;

    final Context context;
    private final ContentResolver resolver;
    public long aduId = -1;
    private final Runnable onAdusReceived;

    /**
     * Create a DDDClientAdapter to send and receive ADUs to BundleClient.
     * This adapter will also register a broadcast receiver to listen for
     * ADUs received events and trigger the onAdusReceived callback when that
     * happens. Events will not be listened for if either lifecycle or
     * onAdusReceived is null.
     *
     * @param context the application context that is used to access the BundleClient MessageProvider.
     * @param lifecycle the lifecycle of the application that is used to register the broadcast receiver. Broadcasts will not be received if this is null.
     * @param onAdusReceived the Runnable to be invoked if the BundleClient has received ADUs for the application. This can be nulled.
     */
    public DDDClientAdapter(Context context, Lifecycle lifecycle, Runnable onAdusReceived) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.onAdusReceived = onAdusReceived;
        ContextCompat.registerReceiver(context,
                                       this,
                                       new IntentFilter(BROADCAST_ACTION),
                                       ContextCompat.RECEIVER_EXPORTED);
        if (lifecycle != null) {
            lifecycle.addObserver(new DefaultLifecycleObserver() {
                @Override
                public void onDestroy(@NonNull LifecycleOwner owner) {
                    context.unregisterReceiver(DDDClientAdapter.this);
                    DefaultLifecycleObserver.super.onDestroy(owner);
                }
            });
        }
    }

    /**
     * Create an OutputStream to send an ADU to BundleClient. The OutputStream must be closed before the ADU is processed by the BundleClient.
     * THIS IS NOT THREADSAFE! THE APPLICATION MUST ENSURE THAT IT ONLY CREATES ONE ADU AT A TIME.
     *
     * @return an OutputStream to send an ADU to BundleClient. The OutputStream must be closed before the ADU is processed by the BundleClient. After closing the aduId in the MessageProviderOutputStream will be set.
     */
    public MessageProviderOutputStream createAduToSend() {
        return new MessageProviderOutputStream();
    }

    /**
     * Receive an ADU from BundleClient Through using an InputStream
     *
     * @param aduId the ADU to read..
     * @return an InputStream that can be used to read the ADU data.
     */
    public MessageProviderInputStream receiveAdu(long aduId) throws IOException {
        return new MessageProviderInputStream(aduId);
    }

    /**
     * Delete all ADUs received by BundleClient up to the given aduId.
     * @param aduId the ADU ID to delete and all ADUs before it.
     * @return true if the ADUs were deleted, false otherwise.
     */
    public boolean deleteReceivedAdusUpTo(long aduId) {
        var rc = resolver.delete(PROVIDER_URI, "deleteAllADUsUpto", new String[] { String.valueOf(aduId) });
        return rc == 1;
    }

    /**
     * Get the client ID of the application.
     * @return the client ID of the application.
     */
    public String getClientId() {
        try (var rsp = resolver.query(PROVIDER_URI, new String[] {"clientId"}, null, null)) {
            if (rsp == null || !rsp.moveToFirst()) {
                return null;
            }
            return rsp.getString(0);
        }
    }

    /**
     * Get the list of ADU IDs that have been received by BundleClient for the Application.
     * This is the list of ADUs that have been received but not yet deleted by the application.
     * @return list of ADU ids.
     */
    public List<Long> getIncomingAduIds() {
        try (var rsp = resolver.query(PROVIDER_URI, new String[] {"data"}, "aduIds", null, null)) {
            if (rsp == null) {
                return null;
            }
            var aduIds = new ArrayList<Long>();
            for(rsp.moveToFirst();!rsp.isAfterLast();rsp.moveToNext()) {
                aduIds.add(rsp.getLong(0));
            }
            return aduIds;
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (onAdusReceived != null) {
            onAdusReceived.run();
        }
    }

    public class MessageProviderOutputStream extends OutputStream {
        byte[] buffer = new byte[MAX_ADU_SIZE];
        int bufferOffset = 0;
        long nextWriteOffset = 0;
        boolean finished = false;

        @Override
        public void write(int b) throws IOException {
            if (finished) throw new IOException("Stream to ADU " + aduId + " closed");
            buffer[bufferOffset++] = (byte) b;
            if (bufferOffset >= MAX_ADU_SIZE) {
                flush();
            }
        }

        @Override
        public void flush() throws IOException {
            if (bufferOffset == 0) return;
            sendProviderInsert(bufferOffset == buffer.length ? buffer : Arrays.copyOfRange(buffer, 0, bufferOffset), nextWriteOffset, false);
            nextWriteOffset += bufferOffset;
            bufferOffset = 0;
        }

        private void sendProviderInsert(byte[] bytes, long writeOffset, boolean finished) throws IOException {
            ContentValues values = new ContentValues();
            values.put("data", bytes);
            values.put("offset", writeOffset);
            values.put("finished", finished);
            values.put("aduId", aduId);
            var rspUri = resolver.insert(PROVIDER_URI, values);
            if (rspUri == null) {
                throw new IOException("Failed to insert data into provider");
            }
            var rspAduIdStr = rspUri.getQueryParameter("aduId");
            var rspErrorStr = rspUri.getQueryParameter("error");
            if (rspErrorStr != null) {
                throw new IOException(rspErrorStr);
            }
            if (rspAduIdStr != null) {
                aduId = Long.parseLong(rspAduIdStr);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (finished) throw new IOException("Stream to ADU " + aduId + " closed");
            while (len > 0) {
                int lenToCopy = Math.min(len, MAX_ADU_SIZE - bufferOffset);
                System.arraycopy(b, off, buffer, bufferOffset, lenToCopy);
                bufferOffset += lenToCopy;
                off += lenToCopy;
                len -= lenToCopy;
                if (bufferOffset >= MAX_ADU_SIZE) {
                    flush();
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (finished) throw new IOException("Stream to ADU " + aduId + " closed");
            finished = true;
            flush();
            // we just want to close everything out
            sendProviderInsert(new byte[0], nextWriteOffset, true);
        }
    }

    public class MessageProviderInputStream extends InputStream {
        private byte[] data;
        private long nextReadOffset = 0;
        private int dataOffset = 0;
        final private long aduId;
        private boolean finished;

        public MessageProviderInputStream(long aduId) throws IOException {
            this.aduId = aduId;
            checkData();
        }

        private int bytesRemaining() {
            return finished || data == null ? 0 : data.length - dataOffset;
        }

        private void checkData() throws IOException {
            if (!finished && bytesRemaining() == 0) {
                try (var rsp = resolver.query(PROVIDER_URI, new String[] { "data", "exception" }, "aduData", new String[] { String.valueOf(aduId), String.valueOf(nextReadOffset) }, null)) {
                    if (rsp == null || !rsp.moveToFirst()) {
                        // all done
                        data = new byte[0];
                        dataOffset = 0;
                        finished = true;
                        return;
                    }
                    var exceptionIndex = 99;
                    var dataIndex = 99;
                    for (int i = 0; i < rsp.getColumnCount(); i++) {
                        if ("exception".equals(rsp.getColumnName(i))) exceptionIndex = i;
                        else if ("data".equals(rsp.getColumnName(i))) dataIndex = i;
                    }
                    if (rsp.getString(exceptionIndex) != null) {
                        throw new IOException(rsp.getString(1));
                    }
                    data = rsp.getBlob(dataIndex);
                    nextReadOffset += data.length;
                    dataOffset = 0;
                    finished = data.length == 0;
                }
            }
        }

        @Override
        public int read() throws IOException {
            checkData();
            if (finished) {
                return -1;
            }
            return data[dataOffset++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            checkData();
            if (finished) return -1;
            int rc = 0;
            while (!finished && len > 0) {
                int lenToCopy = Math.min(len, bytesRemaining());
                System.arraycopy(data, dataOffset, b, off, lenToCopy);
                off += lenToCopy;
                len -= lenToCopy;
                dataOffset += lenToCopy;
                rc += lenToCopy;
                if (len > 0) checkData();
            }
            return rc;
        }
    }
}
