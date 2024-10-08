package net.discdd.bundletransport;

import static android.content.Context.MODE_PRIVATE;
import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import net.discdd.pathutils.TransportPaths;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SubmissionPublisher;
import java.util.logging.Logger;

/**
 * A Fragment to manage server uploads
 */
public class ServerUploadFragment extends Fragment {
    private static final Logger logger = Logger.getLogger(ServerUploadFragment.class.getName());
    private SubmissionPublisher<BundleTransportActivity.ConnectivityEvent> connectivityFlow;
    private Button connectServerBtn;
    private TextView domainInput;
    private TextView portInput;
    private Button restoreDomainAndPortBtn;
    private Button saveDomainAndPortBtn;
    private SharedPreferences sharedPref;
    private TextView serverConnnectedStatus;
    private String transportID;
    private ExecutorService executor = Executors.newFixedThreadPool(2);
    private TransportPaths transportPaths;

    public ServerUploadFragment(SubmissionPublisher<BundleTransportActivity.ConnectivityEvent> connectivityFlow,
                                String transportID, TransportPaths transportPaths) {
        this.connectivityFlow = connectivityFlow;
        this.transportID = transportID;
        this.transportPaths = transportPaths;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate the layout for this fragment
        View mainView = inflater.inflate(R.layout.fragment_server_upload, container, false);
        domainInput = mainView.findViewById(R.id.domain_input);
        portInput = mainView.findViewById(R.id.port_input);
        connectServerBtn = mainView.findViewById(R.id.btn_connect_bundle_server);
        saveDomainAndPortBtn = mainView.findViewById(R.id.save_domain_port);
        restoreDomainAndPortBtn = mainView.findViewById(R.id.restore_domain_port);
        connectServerBtn.setOnClickListener(view -> connectToServer());
        connectivityFlow.consume(event -> connectServerBtn.setEnabled(event.internetAvailable()));
        serverConnnectedStatus = mainView.findViewById(R.id.server_upload_status);

        // save the domain and port inputs
        saveDomainAndPortBtn.setOnClickListener(view -> {
            saveDomainPort();
            Toast.makeText(getContext(), "Saved", Toast.LENGTH_SHORT).show();
        });

        // set saved domain and port to inputs
        restoreDomainAndPortBtn.setOnClickListener(view -> restoreDomainPort());

        sharedPref = requireActivity().getSharedPreferences("server_endpoint", MODE_PRIVATE);
        restoreDomainPort();

        return mainView;
    }

    private void connectToServer() {
        String serverDomain = domainInput.getText().toString();
        String serverPort = portInput.getText().toString();
        if (!serverDomain.isEmpty() && !serverPort.isEmpty()) {
            connectServerBtn.setEnabled(false);
            logger.log(INFO, "Sending to " + serverDomain + ":" + serverPort);

            requireActivity().runOnUiThread(() -> serverConnnectedStatus.setText(
                    "Initiating server exchange to " + serverDomain + ":" + serverPort + "...\n"));

            TransportToBundleServerManager transportToBundleServerManager =
                    new TransportToBundleServerManager(transportPaths,
                                                       serverDomain, serverPort,
                                                       this::connectToServerComplete,
                                                       e -> connectToServerError(e, serverDomain + ":" + serverPort));
            executor.execute(transportToBundleServerManager);
        } else {
            Toast.makeText(getContext(), "Enter the domain and port", Toast.LENGTH_SHORT).show();
        }
    }

    private void saveDomainPort() {
        SharedPreferences.Editor editor = sharedPref.edit();
        String domain = domainInput.getText().toString();
        String port = portInput.getText().toString();

        editor.putString("domain", domain);
        editor.putString("port", port);
        editor.apply();
    }

    private void restoreDomainPort() {
        domainInput.setText(sharedPref.getString("domain", ""));
        portInput.setText(sharedPref.getString("port", ""));
    }

    private Void sendTask(Exception thrown) {
        requireActivity().runOnUiThread(() -> {
            if (thrown != null) {
                serverConnnectedStatus.append("Bundles upload failed. " + thrown.getMessage());
                logger.log(SEVERE, "Failed bundle upload", thrown);
            } else {
                serverConnnectedStatus.append("Bundles uploaded successfully.\n");
            }
        });

        return null;
    }

    private Void receiveTask(Exception thrown) {
        requireActivity().runOnUiThread(() -> {
            if (thrown != null) {
                appendToActivityLog("Bundles download failed. " + thrown.getMessage());
                logger.log(SEVERE, "Failed bundle download", thrown);
            } else {
                appendToActivityLog("Bundles downloaded successfully.");
            }
        });

        return null;
    }

    private Void connectToServerComplete(Void x) {
        requireActivity().runOnUiThread(() -> {
            serverConnnectedStatus.append("Server exchange complete.\n");
            connectServerBtn.setEnabled(true);
        });
        return null;
    }

    private Void connectToServerError(Exception e, String transportTarget) {
        requireActivity().runOnUiThread(() -> {
            serverConnnectedStatus.append("Server exchange incomplete with error.\n");
            serverConnnectedStatus.append("Error: " + e.getMessage() + " \n");
            connectServerBtn.setEnabled(true);
            Toast.makeText(getContext(), "Invalid hostname: " + transportTarget, Toast.LENGTH_SHORT).show();
        });
        return null;
    }

    private void appendToActivityLog(String message) {
        requireActivity().runOnUiThread(() -> {
            if (serverConnnectedStatus.getLineCount() > 20) {
                int nl = serverConnnectedStatus.getText().toString().indexOf('\n');
                serverConnnectedStatus.getEditableText().delete(0, nl + 1);
            }
            serverConnnectedStatus.append(message + '\n');
        });
    }
}
