package net.discdd.adapter;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class DDDClientAdapter {
    public static final String PROVIDER_NAME = "net.discdd.provider.datastoreprovider";
    public static final Uri PROVIDER_URI;
    static {
        PROVIDER_URI = Uri.parse("content://" + PROVIDER_NAME + "/messages");
    }
    public static final int MAX_ADU_SIZE = 128*1024;

    final Context context;
    private final ContentResolver resolver;
    public long aduId = -1;

    public DDDClientAdapter(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
    }

    public MessageProviderOutputStream createAduToSend() {
        return new MessageProviderOutputStream();
    }

    public MessageProviderInputStream receiveAdu(long aduId) {
        return new MessageProviderInputStream(aduId);
    }

    public boolean deleteReceivedAdusUpTo(long aduId) {
        var rc = resolver.delete(PROVIDER_URI, "deleteAllADUsUpto", new String[] { String.valueOf(aduId) });
        return rc == 1;
    }

    public String getClientId() {
        try (var rsp = resolver.query(PROVIDER_URI, new String[] {"clientId"}, null, null)) {
            if (rsp == null || !rsp.moveToFirst()) {
                return null;
            }
            return rsp.getString(0);
        }
    }

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
            int length = bufferOffset;
            sendProviderInsert(bufferOffset == buffer.length ? buffer : Arrays.copyOfRange(buffer, 0, bufferOffset), bufferOffset, nextWriteOffset, false);
            nextWriteOffset += bufferOffset;
            bufferOffset = 0;
        }

        private void sendProviderInsert(byte[] bytes, int len, long writeOffset, boolean finished) throws IOException {
            ContentValues values = new ContentValues();
            values.put("data", bytes);
            values.put("offset", (long) writeOffset);
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
            sendProviderInsert(new byte[0], 0, nextWriteOffset, true);
        }
    }

    public class MessageProviderInputStream extends InputStream {
        private byte[] data;
        private long nextReadOffset = 0;
        private int dataOffset = 0;
        final private long aduId;
        private boolean finished;

        public MessageProviderInputStream(long aduId) {
            this.aduId = aduId;
        }

        private int bytesRemaining() {
            return finished || data == null ? 0 : data.length - dataOffset;
        }

        private void checkData() {
            if (!finished && bytesRemaining() == 0) {
                try (var rsp = resolver.query(PROVIDER_URI, new String[] { "data"}, "aduData", new String[] { String.valueOf(aduId), String.valueOf(nextReadOffset) }, null)) {
                    if (rsp == null || !rsp.moveToFirst()) {
                        // all done
                        data = new byte[0];
                        dataOffset = 0;
                        finished = true;
                        return;
                    }
                    data = rsp.getBlob(0);
                    nextReadOffset += data.length;
                    dataOffset = 0;
                    finished = data.length == 0;
                }
            }
        }

        @Override
        public int read() {
            checkData();
            if (finished) {
                return -1;
            }
            return data[dataOffset++] & 0xff;
        }

        @Override
        public int read(byte[] b, int off, int len) {
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
