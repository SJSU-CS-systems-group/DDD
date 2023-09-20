package edu.sjsu.dtn.model;

public class Metadata {
    //last messageId added by the app
    public long lastAddedMessageId;

    //last messageId sent successfully via DTN
    public long lastSentMessageId;

    //last messageId received via DTN
    public long lastReceivedMessageId;

    //latest messageId processed by the application
    public long lastProcessedMessageId;

    public Metadata(long lastAddedMessageId, long lastSentMessageId, long lastReceivedMessageId, long lastProcessedMessageId){
        this.lastAddedMessageId = lastAddedMessageId;
        this.lastSentMessageId = lastSentMessageId;
        this.lastReceivedMessageId = lastReceivedMessageId;
        this.lastProcessedMessageId = lastProcessedMessageId;
    }
}
