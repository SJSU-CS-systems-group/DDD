package com.ddd.bundletransport;

import com.ddd.bundletransport.service.FileServiceImpl;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

public class RpcServer {
    private final String TAG = "dddTransport";
    private final String inetSocketAddressIP = "192.168.49.1";
    private final int port = 7777;
    private ServerState state = ServerState.SHUTDOWN;

    public enum ServerState {
        RUNNING, PENDING, SHUTDOWN
    }

    private Server server;
    private static RpcServer rpcServerInstance;

    private List<RpcServerStateListener> listeners = new ArrayList<>();

    public RpcServer(RpcServerStateListener ssl) {
        if (null != ssl) listeners.add(ssl);
    }

    public static RpcServer getInstance(RpcServerStateListener ssl) {
        if (null == rpcServerInstance) {
            rpcServerInstance = new RpcServer(ssl);
        }
        return rpcServerInstance;
    }

    public void startServer(Context context) {
        Log.d(TAG, "Server state is : " + state.name());
        if (state == ServerState.RUNNING || state == ServerState.PENDING) {
            return;
        }

        SocketAddress address = new InetSocketAddress(inetSocketAddressIP, port);
        notifyStateChange(ServerState.PENDING);
        server = NettyServerBuilder.forAddress(address).addService(new FileServiceImpl(context)).build();

        Log.d(TAG, "Starting rpc server at: " + server.toString());

        try {
            server.start();
            notifyStateChange(ServerState.RUNNING);
            Log.d(TAG, "Rpc server running at: " + server.toString());
        } catch (IOException e) {
            Log.e(TAG, "RpcServer -> startServer() IOException: " + e.getMessage());
            notifyStateChange(ServerState.SHUTDOWN);
        }
    }

    public void shutdownServer() {
        if (state == ServerState.SHUTDOWN || state == ServerState.PENDING) {
            return;
        }

        Log.d(TAG, "Stopping rpc server");
        notifyStateChange(ServerState.PENDING);
        if (server != null) {
            try {
                boolean stopped = server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                if (!stopped) {
                    throw new Exception("Server not stopped");
                }
                Log.d(TAG, "Stopped rpc server");
                notifyStateChange(ServerState.SHUTDOWN);
            } catch (IOException e) {
                Log.e(TAG, "RpcServer -> terminateServer() IOException: " + e.getMessage());
                notifyStateChange(ServerState.RUNNING);
            } catch (Exception e) {
                Log.e(TAG, "RpcServer -> terminateServer() Exception: " + e.getMessage());
                notifyStateChange(ServerState.RUNNING);
            }
        }
    }

    public void addStateListener(RpcServerStateListener listener) {
        listeners.add(listener);
    }

    public ServerState getState() {
        return state;
    }

    public boolean isShutdown() {
        if (server == null) {
            return true;
        }
        return server.isShutdown();
    }

    private void notifyStateChange(ServerState newState) {
        state = newState;
        for (RpcServerStateListener listener : listeners) {
            listener.onStateChanged(state);
        }
    }
}
