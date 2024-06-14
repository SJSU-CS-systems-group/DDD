package com.ddd.bundletransport.utils;

import android.widget.TextView;

import com.ddd.bundletransport.R;
import com.ddd.bundletransport.RpcServer;

public class RpcUtils {
    public static void onStateChanged(RpcServer.ServerState newState) {
        runOnUiThread(() -> {
            TextView grpcServerState = findViewById(R.id.grpc_server_state);
            if (newState == RpcServer.ServerState.RUNNING) {
                grpcServerState.setText("GRPC Server State: RUNNING");
                startGRPCServerBtn.setEnabled(false);
                stopGRPCServerBtn.setEnabled(true);
            } else if (newState == RpcServer.ServerState.PENDING) {
                grpcServerState.setText("GRPC Server State: PENDING");
                startGRPCServerBtn.setEnabled(false);
                stopGRPCServerBtn.setEnabled(false);
            } else {
                grpcServerState.setText("GRPC Server State: SHUTDOWN");
                startGRPCServerBtn.setEnabled(true);
                stopGRPCServerBtn.setEnabled(false);
            }
        });
    }
}
