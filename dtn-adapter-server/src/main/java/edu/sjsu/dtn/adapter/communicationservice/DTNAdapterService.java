package edu.sjsu.dtn.adapter.communicationservice;

import com.google.protobuf.ByteString;

import edu.sjsu.dtn.adapter.signal.SignalCLIConnector;
import edu.sjsu.dtn.model.ADU;
import edu.sjsu.dtn.storage.FileStoreHelper;
import io.grpc.stub.StreamObserver;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/*
 * SendFileStoreHelper - store data that we get from adapter servers
 * ReceiveFileStoreHelper - store data that we get from transport
 * */

public class DTNAdapterService extends DTNAdapterGrpc.DTNAdapterImplBase {
	private static final String ROOT_DIRECTORY = "C:\\Users\\dmuna\\Documents\\GitHub\\DDD-Security\\dtn-adapter-server/FileStore";

	@Override
	public void saveData(AppData request, StreamObserver<AppData> responseObserver) {
		System.out.println("[DTNAdapterService] Saving Data for:"+request.getClientId());
		System.out.println("[DTNAdapterService] total number of ADUs in request:"+request.getDataListCount());
		System.out.println("[DTNAdapterService] last ADU ID received by server:"+request.getLastADUIdReceived());
		FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");

		FileStoreHelper receiveHelper = new FileStoreHelper(ROOT_DIRECTORY + "/receive");

		for (int i = 0; i < request.getDataListCount(); i++) {
			byte[] reply = null;
			try {
				receiveHelper.AddFile(request.getClientId(), request.getDataList(i).getData().toByteArray());
				reply = SignalCLIConnector.performRegistration(request.getClientId(), request.getDataList(i).getData().toByteArray());
				//(new String(request.getDataList(i).getData().toByteArray())+" was processed").getBytes();
				sendHelper.AddFile(request.getClientId(), reply);

			} catch (Exception e) {
				System.out.println("exception in register");
			}

		}

		removeAcknowledgedADUs(request.getClientId(), request.getLastADUIdReceived());

		List<ADU> dataList = sendHelper.getAppData(request.getClientId());
		List<AppDataUnit> dataListConverted = new ArrayList<>();
		System.out.println("[DTNAdapterService.saveData] data to send to bundle server: ");
		for (int i = 0; i < dataList.size(); i++) {
			try {
				byte[] data = FileStoreHelper.getStringFromFile(dataList.get(i).getSource().getAbsolutePath()).getBytes();
				System.out.println("data: " +
						new String(data));
				dataListConverted.add(AppDataUnit.newBuilder()
						.setData(ByteString.copyFrom(data))
						.setAduId(dataList.get(i).getADUId()).build()
				);
			}catch (Exception ex){
				ex.printStackTrace();
			}
		}
		AppData appData = AppData.newBuilder()
				.addAllDataList(dataListConverted)
				.setLastADUIdReceived(receiveHelper.getLastADUIdReceived(request.getClientId()))
				.build();

		responseObserver.onNext(appData);
		responseObserver.onCompleted();
	}

	private boolean removeAcknowledgedADUs(String clientId, long lastADUIdSent){
		FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");
		sendHelper.deleteAllFilesUpTo(clientId, lastADUIdSent);
		return true;
	}

	private void generateAdus(String clientId, int k){
		FileStoreHelper sendHelper = new FileStoreHelper(ROOT_DIRECTORY + "/send");
		for(int i=0;i<k;i++){
			sendHelper.AddFile(clientId, ("adding adu "+i+" of "+k+"").getBytes());
		}
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
