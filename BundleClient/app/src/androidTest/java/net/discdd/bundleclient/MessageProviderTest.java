package net.discdd.bundleclient;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@RunWith(AndroidJUnit4.class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class MessageProviderTest {

    private Path sendADUStorePath;
    private Path receiveADUStorePath;
    private final String smallData = "Small Data";
    private final String  bigData = "Big Data".repeat(1024 * 1024);
    DDDClientAdapter adapter;

    @Before
    public void setUp() throws IOException {
        MessageProvider messageProvider = new MessageProvider();
        messageProvider.attachInfo(ApplicationProvider.getApplicationContext(), null);
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

    @Test
    public void test5DeleteIncomingADUIds() {
        Assert.assertEquals(adapter.getIncomingAduIds(), List.of(1L, 2L));
        adapter.deleteReceivedAdusUpTo(1);
        Assert.assertEquals(adapter.getIncomingAduIds(), List.of(2L));
    }
}