package net.discdd.server.applicationdatamanager;

import net.discdd.utils.StoreADUs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class AduStores {
    private final StoreADUs receiveADUsStorage;
    private final StoreADUs sendADUsStorage;

    public AduStores(@Value("${bundle-server.bundle-store-root}") Path rootDataDir) {
        System.out.println("****** rootDataDir " + rootDataDir);
        this.sendADUsStorage = new StoreADUs(rootDataDir.resolve("send"), true);
        this.receiveADUsStorage = new StoreADUs(rootDataDir.resolve("receive"), false);
    }

    public StoreADUs getReceiveADUsStorage() {
        return receiveADUsStorage;
    }

    public StoreADUs getSendADUsStorage() {
        return sendADUsStorage;
    }
}
