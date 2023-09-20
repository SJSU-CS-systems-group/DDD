package com.ddd.server.applicationdatamanager;


import com.ddd.model.ADU;

import java.io.File;
import java.util.ArrayList;
import java.util.List;



public class DataStoreAdaptorTest {
    private static final String ROOT_DIRECTORY = "C:\\Users\\dmuna\\Documents\\GitHub\\DDD-Security\\bundleserver\\FileStore";

    private static void fetchADUsTest(DataStoreAdaptor adaptor, String clientId, String appId){
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

    public static void main(String[] args){
        String clientId="m.deepak";
        String appId = "com.android.mysignal";
        DataStoreAdaptor adaptor = new DataStoreAdaptor(ROOT_DIRECTORY);
        fetchADUsTest(adaptor, clientId, appId);
    }
}

