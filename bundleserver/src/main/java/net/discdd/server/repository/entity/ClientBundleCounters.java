package net.discdd.server.repository.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@AllArgsConstructor
public class ClientBundleCounters {
    @Id
    public String clientId;
    public long lastReceivedBundleCounter;
    public String lastReceivedBundleId;
    public long lastSentBundleCounter;
    public String lastSentBundleId;
}
