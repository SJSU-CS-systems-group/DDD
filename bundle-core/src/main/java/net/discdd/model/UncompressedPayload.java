package net.discdd.model;

import java.io.File;
import java.util.List;

public class UncompressedPayload {

    private final File source;

    public File getSource() {
        return this.source;
    }

    private final String bundleId;

    private final Acknowledgement ackRecord;

    private final List<ADU> ADUs;
    private final List<String> appIds;

    private UncompressedPayload(String bundleId, Acknowledgement ackRecord, List<ADU> ADUs, File source, List<String> appIds) {
        this.bundleId = bundleId;
        this.ackRecord = ackRecord;
        this.ADUs = ADUs;
        this.source = source;
        this.appIds = appIds;
    }

    public String getBundleId() {
        return this.bundleId;
    }

    public Acknowledgement getAckRecord() {
        return this.ackRecord;
    }

    public List<ADU> getADUs() {
        return this.ADUs;
    }
    public List<String> getAppIds() {
        return this.appIds;
    }

    public static class Builder {

        private File source;

        private String bundleId;

        private Acknowledgement ackRecord;

        private List<ADU> ADUs;
        private List<String> appIds;

        public String getBundleId() {
            return this.bundleId;
        }

        public Acknowledgement getAckRecord() {
            return this.ackRecord;
        }

        public List<ADU> getADUs() {
            return this.ADUs;
        }

        public File getSource() {
            return this.source;
        }

        public List<String> getAppIds() {
            return this.appIds;
        }

        public Builder setAckRecord(Acknowledgement ackRecord) {
            this.ackRecord = ackRecord;
            return this;
        }

        public Builder setADUs(List<ADU> ADUs) {
            this.ADUs = ADUs;
            return this;
        }

        public Builder setBundleId(String bundleId) {
            this.bundleId = bundleId;
            return this;
        }

        public Builder setSource(File source) {
            this.source = source;
            return this;
        }

        public Builder setAppIds(List<String> appIds) {
            this.appIds = appIds;
            return this;
        }

        public UncompressedPayload build() {
            return new UncompressedPayload(this.bundleId, this.ackRecord, this.ADUs, this.source, this.appIds == null ? List.of() : this.appIds);
        }
    }
}
