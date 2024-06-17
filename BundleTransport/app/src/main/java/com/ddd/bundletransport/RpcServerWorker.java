package com.ddd.bundletransport;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ddd.bundletransport.service.FileServiceImpl;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

public class RpcServerWorker extends Worker implements RpcServerStateListener {
    private RpcServer rpcServer;
//    private final int port;
//    private final SocketAddress address;

    // the ip that wifi direct always uses and that bundle client connects to
//    private final String inetSocketAddressIP = "192.168.49.1";

    public RpcServerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
//        port = workerParams.getInputData().getInt("PORT", 1778);
//        address = new InetSocketAddress(inetSocketAddressIP, 1778);
    }

    private void startRpcServer() {

        rpcServer = new RpcServer(this);

        Log.d(MainActivity.TAG, "start rpc server at:" + rpcServer);
        try {
            rpcServer.startServer(this.getApplicationContext());

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void stopRpcServer() {
        Log.d(MainActivity.TAG, "stop rpc server");
        if (rpcServer != null && !rpcServer.isShutdown()) {
            rpcServer.shutdownServer();
        }
    }

    @Override
    public void onStopped() {
        stopRpcServer();
        super.onStopped();
    }

    @NonNull
    @Override
    public Result doWork() {
        startRpcServer();
        return Result.success();
    }

    @Override
    public void onStateChanged(RpcServer.ServerState newState) {

    }
}
