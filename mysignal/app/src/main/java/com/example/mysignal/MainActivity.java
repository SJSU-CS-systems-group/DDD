package com.example.mysignal;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    static final Uri CONTENT_URL=Uri.parse("content://com.ddd.datastore.providers/messages");
    EditText receiver, messageText, appName;
    Button insert, delete, view, update, startServiceBtn;

    TextView messageListLabel;
    ContentResolver resolver;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        resolver=getContentResolver();

        receiver=findViewById(R.id.receiver);
        messageText=findViewById(R.id.message);
        appName=findViewById(R.id.app_name);

        insert=findViewById(R.id.btn_insert);
        view=findViewById(R.id.btn_view_messages);
        update=findViewById(R.id.btn_update_status);
        delete=findViewById(R.id.btn_delete);
        startServiceBtn=findViewById(R.id.btn_start_service);
        //grantUriPermission();
        //getMessages();
        insert.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                addMessage();
            }
        });
        view.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                getMessages();
            }
        });
        startServiceBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startService(new Intent(getApplicationContext(), SocketService.class));
            }
        });

        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                /*String receiverTXT=receiver.getText().toString();
                String messageTXT=messageText.getText().toString();
                String appNameTXT=appName.getText().toString();
                Boolean checkDeleteStatus=resolver.delete(receiverTXT, messageTXT, appNameTXT);
                if(checkDeleteStatus){
                    Toast.makeText(MainActivity.this, "message deleted", Toast.LENGTH_SHORT);
                }
                else{
                    Toast.makeText(MainActivity.this, "error deleting message", Toast.LENGTH_SHORT);
                }*/
            }
        });
    }

    public void getMessages(){
        FileStoreHelper fileStoreHelper = new FileStoreHelper(getApplicationContext().getApplicationInfo().dataDir+"/ReceivedData");
        String messageList="";
        List<byte[]> arr = fileStoreHelper.getAppData();
        for(int i=0;i<arr.size();i++){
            messageList=messageList+arr.get(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
        builder.setCancelable(true);
        builder.setTitle("message list");
        builder.setMessage(messageList);
        builder.show();

    }
    public void addMessage(){
        String message=messageText.getText().toString();

        ContentValues values=new ContentValues();
        values.put("data", message.getBytes());
        values.put("appName", getApplicationContext().getPackageName());

        resolver.insert(CONTENT_URL, values);

        getMessages();
    }
}