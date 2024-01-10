package com.ddd.bundletransport;

public interface RpcServerStateListener {
    void onStateChanged(RpcServer.ServerState newState);
}
