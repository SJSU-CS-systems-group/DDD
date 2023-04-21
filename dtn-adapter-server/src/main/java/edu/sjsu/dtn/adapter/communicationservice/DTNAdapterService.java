package edu.sjsu.dtn.adapter.communicationservice;

import com.google.protobuf.ByteString;

import edu.sjsu.dtn.adapter.signal.SignalCLIConnector;
import edu.sjsu.dtn.storage.FileStoreHelper;
import io.grpc.stub.StreamObserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class DTNAdapterService extends DTNAdapterGrpc.DTNAdapterImplBase {
	private static final String ROOT_DIRECTORY = "/Users/adityasinghania/Downloads/Data/DTN-bundle-server-adapter/FileStore";

	@Override
	public void saveData(AppData request, StreamObserver<AppData> responseObserver) {
		 System.out.println("[DAS] Saving Data for:"+request.getClientId());
		FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");

		FileStoreHelper helper = new FileStoreHelper(ROOT_DIRECTORY + "/receive");

		for (int i = 0; i < request.getDataCount(); i++) {
			byte[] reply = null;
			try {
				reply = SignalCLIConnector.performRegistration(request.getClientId(), request.getData(i).toByteArray());
				sendHelper.AddFile(request.getClientId(), reply);

			} catch (Exception e) {
				System.out.println("exception in register");
			}

		}

		List<byte[]> dataList = sendHelper.getAppData(request.getClientId());
		List<ByteString> dataListConverted = new ArrayList<>();
		System.out.println("[DTNAdapterService.saveData] data to send: ");
		for (int i = 0; i < dataList.size(); i++) {
			System.out.println("data: " + ByteString.copyFrom(dataList.get(i)));
			dataListConverted.add(ByteString.copyFrom(dataList.get(i)));
		}
		AppData appData = AppData.newBuilder().addAllData(dataListConverted).build();

		responseObserver.onNext(appData);
		responseObserver.onCompleted();
	}

	@Override
	public void prepareData(ClientData request, StreamObserver<PrepareResponse> responseObserver) {
		
		FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");
		List<String> messageLocations = SignalCLIConnector.receiveMessages(request.getClientId());
		
		for (String loc : messageLocations) {
			try {
				sendHelper.AddFile(request.getClientId(), Files.readAllBytes(new File(loc).toPath()));
			} catch (IOException e) {
				System.out.println("Exception in retreiving message files");
			}
		}
		responseObserver.onNext(PrepareResponse.newBuilder().setCode(StatusCode.SUCCESS).build());
		responseObserver.onCompleted();
	}
}
