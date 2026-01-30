package net.discdd.server.applicationdatamanager;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import net.discdd.utils.StoreADUs;

@Component
public class AduStores {
    private final StoreADUs receiveADUsStorage;
    private final StoreADUs sendADUsStorage;

    public AduStores(@Value("${bundle-server.bundle-store-root}")
    Path rootDataDir) {
        this.sendADUsStorage = new StoreADUs(rootDataDir.resolve("send"));
        this.receiveADUsStorage = new StoreADUs(rootDataDir.resolve("receive"));
    }

    public StoreADUs getReceiveADUsStorage() { return receiveADUsStorage; }

    public StoreADUs getSendADUsStorage() { return sendADUsStorage; }
}
