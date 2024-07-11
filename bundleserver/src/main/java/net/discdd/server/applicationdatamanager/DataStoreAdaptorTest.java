package net.discdd.server.applicationdatamanager;

import net.discdd.model.ADU;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DataStoreAdaptorTest {
    private static final Path ROOT_DIRECTORY =
            Path.of("C:\\Users\\dmuna\\Documents\\GitHub\\DDD-Security\\bundleserver\\FileStore");

    private static void fetchADUsTest(DataStoreAdaptor adaptor, String clientId, String appId) throws IOException {
        List<ADU> adus = new ArrayList<>();
        //adus.add(
                /*new ADU(
                new File("/Users/adityasinghania/Downloads/Data/Shared/REGISTERED_APP_IDS.txt"),
                appId,
                1,
                0,
                clientId)*/
        //);
        adaptor.persistADUsForServer(clientId, appId, adus);
    }

    public static void main(String[] args) throws IOException {
        String clientId = "m.deepak";
        String appId = "com.android.mysignal";
        DataStoreAdaptor adaptor = new DataStoreAdaptor(ROOT_DIRECTORY);
        fetchADUsTest(adaptor, clientId, appId);
    }
}

