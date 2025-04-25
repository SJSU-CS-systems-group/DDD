package net.discdd.bundleclient;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import net.discdd.adapter.DDDClientAdapter;
import net.discdd.datastore.providers.MessageProvider;

import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MessageProviderTest {

    private MessageProvider messageProvider;
    private Uri testUri;
    private Path sendADUStorePath;
    private Path receiveADUStorePath;
    private String smallData = "Small Data";
    private String  bigData = "Big Data".repeat(1024 * 1024);
    private ContentResolver contentResolver;
    DDDClientAdapter adapter;

    @Before
    public void setUp() throws IOException {
        messageProvider = new MessageProvider();
        messageProvider.attachInfo(ApplicationProvider.getApplicationContext(), null);
        testUri = Uri.parse(MessageProvider.URL);
        contentResolver = ApplicationProvider.getApplicationContext().getContentResolver();
        adapter = new DDDClientAdapter(ApplicationProvider.getApplicationContext());
        // access the private sendADUsStorage and receiveADUsStorage fields in messageProvider
        String appId = ApplicationProvider.getApplicationContext().getPackageName();
        sendADUStorePath = ApplicationProvider.getApplicationContext().getDataDir().toPath().resolve("send").resolve(appId);
        receiveADUStorePath = ApplicationProvider.getApplicationContext().getDataDir().toPath().resolve("receive").resolve(appId);
        Files.createDirectories(receiveADUStorePath);
    }

    @Test
    public void test1InsertSmallData() throws IOException {
        try (var os = adapter.createAduToSend()) {
            os.write(smallData.getBytes());
        }
        var sendADUPath = sendADUStorePath.resolve("1");
        var aduSendString = new String(Files.readAllBytes(sendADUPath));
        Assert.assertEquals(smallData, aduSendString);
    }

    @Test
    public void test2InsertBigData() throws IOException {
        // Make sure it takes multiple sends
        try (var os = adapter.createAduToSend()) {
            os.write(bigData.getBytes());
        }
        var sendADUPath = sendADUStorePath.resolve("2");
        var aduSendString = new String(Files.readAllBytes(sendADUPath));
        Assert.assertEquals(bigData.length(), aduSendString.length());
        Assert.assertEquals(bigData, aduSendString);
    }

    @Test
    public void test3QuerySmallData() throws IOException {
        Path receiveSmallDataPath = receiveADUStorePath.resolve("1");
        Files.write(receiveSmallDataPath, smallData.getBytes());
        try (var is = adapter.receiveAdu(1)) {
            byte[] allBytes = is.readAllBytes();
            var readString = new String(allBytes);
            Assert.assertEquals(smallData, readString);
        }
    }

    @Test
    public void test4QueryBigData() throws IOException {
        Path receiveBigDataPath = receiveADUStorePath.resolve("2");
        Files.write(receiveBigDataPath, bigData.getBytes());
        try (var is = adapter.receiveAdu(2)) {
            var readString = new String(is.readAllBytes());
            Assert.assertEquals(bigData, readString);
        }
    }

    public static class MessageProviderInputStream extends InputStream {
        private byte[] data;
        private long nextReadOffset = 0;
        private int dataOffset = 0;
        final private MessageProvider messageProvider;
        final private long aduId;
        private boolean finished;

        public MessageProviderInputStream(MessageProvider messageProvider, long aduId) {
            this.messageProvider = messageProvider;
            this.aduId = aduId;
        }

        private int bytesRemaining() {
            return finished || data == null ? 0 : data.length - dataOffset;
        }

        private void checkData() {
            if (!finished && bytesRemaining() == 0) {
                try (var rsp = messageProvider.query(Uri.parse(MessageProvider.URL), new String[] {"data"}, "aduData", new String[] { String.valueOf(aduId), String.valueOf(nextReadOffset) }, null)) {
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
        public int read() throws IOException {
            checkData();
            if (finished) {
                return -1;
            }
            return data[dataOffset++];
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