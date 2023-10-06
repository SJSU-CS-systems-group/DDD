package com.ddd.server;

import com.ddd.server.storage.MySQLConnection;
import edu.sjsu.dtn.server.communicationservice.*;
import io.grpc.stub.StreamObserver;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

public class DTNCommunicationService extends DTNCommunicationGrpc.DTNCommunicationImplBase {
    
    @Override
    public void registerAdapter(ConnectionData connectionData,
                                StreamObserver<ResponseStatus> responseObserver){
        try {
            MySQLConnection mysql = new MySQLConnection();
            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();

            stmt.executeUpdate("insert into registered_app_adapter_table (app_id, address) values (" +
                    "'"+connectionData.getAppName()+"', '"+connectionData.getUrl()+"');");

            con.close();
            responseObserver.onNext(ResponseStatus.newBuilder().setCode(0).build());
        } catch (Exception ex){
            ex.printStackTrace();
        }
        responseObserver.onCompleted();
    }
/*
    public void saveData(AppData request,
                         StreamObserver<ResponseStatus> responseObserver) {
        //receive data from adapter
        //if app does not exist, discard data
        if(request.getDestination() == DataTarget.BUNDLE_SERVER){
            if(DoesAppAdapterExist(request.getAppName())){
                SaveData(request);
            }
            else{
                System.out.println("APP ADAPTER NOT REGISTERED");
            }
        }
        else{
            SaveData(request);
        }

    }

    private boolean SaveData(AppData data){
        boolean isSuccess=false;
        try {
            MySQLConnection mysql = new MySQLConnection();
            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();
            String dataTarget = data.getDestination() == DataTarget.APPLICATION?"APP":"SERVER";

            ResultSet rs = stmt.executeQuery("select max(adu_id) from app_data_table where app_name='"+data.getAppName()+"';");
            int maxValue = 0;
            while(rs.next()) {
                System.out.println("max value for app- "+rs.getInt(1) );
                maxValue = rs.getInt(1);
            }
            maxValue++;

            SaveADUToFile(data.getData().toByteArray(), data.getClientID() , data.getAppName());
            stmt.executeQuery("insert into app_data_table (adu_id, app_name, direction, data) values (" +
                    maxValue+",'"+data.getAppName()+"', '"+dataTarget+"', "+data.getData()+");");

            con.close();
        } catch (Exception ex){
            ex.printStackTrace();
        }
        return isSuccess;
    }

    private void SaveADUToFile(byte[] data, String clientId, String appId){
        FileStoreHelper fileStoreHelper = new FileStoreHelper(ROOT_DIRECTORY+"/Send");
        fileStoreHelper.AddFile(clientId, appId, data);
    }

    private boolean DoesAppAdapterExist(String appName){
        boolean appFound = false;
        try {
            MySQLConnection mysql = new MySQLConnection();
            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery("select * from registered_app_adapter_table where appName="+appName);
            while(rs.next()) {
                System.out.println(rs.getInt(1) + "  " + rs.getString(2) + "  " + rs.getString(3));
                appFound = true;
            }
            con.close();
        } catch (Exception ex){
            ex.printStackTrace();
        }
        return appFound;
    }

    public void deleteADUs(String clientId, String appId, Long aduIdEnd){

    }

    private void generateADUPath(ADU adu){
        String direction = adu.getDestination() == DataTarget.APPLICATION?"Send":"Receive";
        String path = ROOT_DIRECTORY+"\\"+ direction+"\\"+adu.getAppId()+"\\"+adu.getClientId()+"\\"+adu.getADUId()+".txt";
        adu.setSource(new File(path));
    }*/

    /*private ADU fetchADU(String clientId, String appId, long aduId){
        try {
            boolean aduIdFound = false;
            MySQLConnection mysql = new MySQLConnection();
            Connection con = mysql.GetConnection();
            Statement stmt = con.createStatement();
            ResultSet rs = stmt.executeQuery("select * from app_data_table where app_name='"+appId+
                    "' and adu_id="+aduId+" and client_id='"+clientId+"';");
            while(rs.next()) {
                System.out.println(rs.getInt(1) );
                aduIdFound = true;
            }
            if(aduIdFound){
                ADU adu = new ADU(null, appId, aduId, -1, clientId);
                generateADUPath(adu);
            }else{
                return null;
            }
            con.close();
        } catch (Exception ex){
            ex.printStackTrace();
        }

        return null;
    }*/

    public static void main(String args[]){
        try{
            //code for testing
            Class.forName("com.mysql.jdbc.Driver");
            Connection con= DriverManager.getConnection(
                    "jdbc:mysql://localhost:3306/dtn_server_db","root","password");
            Statement stmt=con.createStatement();
            ResultSet rs=stmt.executeQuery("select * from registered_app_adapter_table");
            while(rs.next())
                System.out.println(rs.getInt(1)+"  "+rs.getString(2)+"  "+rs.getString(3));
            con.close();
        }catch(Exception e){
            System.out.println(e);
        }
    }
}
