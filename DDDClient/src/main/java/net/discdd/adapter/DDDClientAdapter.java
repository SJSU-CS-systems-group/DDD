package net.discdd.adapter;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

public class DDDClientAdapter {
    public static final String PROVIDER_NAME = "net.discdd.provider.datastoreprovider";
    public static final String URL = "content://" + PROVIDER_NAME + "/messages";
    public static final int MAX_ADU_SIZE = 512*1024;

    final Context context;
    private final ContentResolver resolver;

    public DDDClientAdapter(Context context) {
        this.context = context;
        this.resolver = context.getContentResolver();
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
                try (var rsp = resolver.query(Uri.parse(URL), new String[] { "data"}, "aduData", new String[] { String.valueOf(aduId), String.valueOf(nextReadOffset) }, null)) {
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
