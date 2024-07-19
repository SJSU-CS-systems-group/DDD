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
        this.sendADUsStorage = new StoreADUs(rootDataDir.resolve("send").toFile().toPath(), true);
        this.receiveADUsStorage = new StoreADUs(rootDataDir.resolve("receive").toFile().toPath(), false);
    }

    public StoreADUs getReceiveADUsStorage() {
        return receiveADUsStorage;
    }

    public StoreADUs getSendADUsStorage() {
        return sendADUsStorage;
    }
}
