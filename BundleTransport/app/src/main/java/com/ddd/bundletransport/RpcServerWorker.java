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

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;

public class RpcServerWorker extends Worker {
    private Server server;
    private final int port;
    private final SocketAddress address;

    public RpcServerWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
        port = workerParams.getInputData().getInt("PORT", 1778);
        address = new InetSocketAddress("192.168.49.1", port);
    }

    private void startRpcServer() {
        server = NettyServerBuilder.forAddress(address)
                .addService(new FileServiceImpl(getApplicationContext()))
                .build();

        Log.d(MainActivity.TAG, "start rpc server at:"+server.toString());
        try {
            server.start().awaitTermination();

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void stopRpcServer() {
        Log.d(MainActivity.TAG, "stop rpc server");
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
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
