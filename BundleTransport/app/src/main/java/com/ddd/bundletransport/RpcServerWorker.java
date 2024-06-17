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

public class RpcServerWorker extends Worker{
    private RpcServer rpcServer;

    public RpcServerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    private void startRpcServer() {

        rpcServer = new RpcServer();

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
}
