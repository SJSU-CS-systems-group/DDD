package net.discdd.bundletransport;

public interface RpcServerStateListener {
    void onStateChanged(RpcServer.ServerState newState);
}
