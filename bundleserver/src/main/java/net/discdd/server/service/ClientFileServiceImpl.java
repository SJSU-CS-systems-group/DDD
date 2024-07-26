package net.discdd.server.service;

import net.devh.boot.grpc.server.service.GrpcService;
import net.discdd.bundlerouting.BundleSender;
import net.discdd.bundlerouting.service.FileServiceImpl;
import net.discdd.server.bundlesecurity.ServerSecurity;
import net.discdd.server.bundletransmission.BundleTransmission;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import javax.annotation.PostConstruct;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

@GrpcService
public class ClientFileServiceImpl extends FileServiceImpl {
     @Value("${bundle-server.bundle-store-shared}")
    private String serverBasePath;
    @Autowired
    private ServerSecurity serverSecurity;
    @Autowired
    BundleTransmission bundleTransmission;
    private static final Logger logger = Logger.getLogger(ClientFileServiceImpl.class.getName());

   @PostConstruct
    private void init(){
       logger.log(Level.INFO, "inside ClientFileServiceImpl init method");
        this.SERVER_BASE_PATH = Path.of(serverBasePath);
        this.sender = BundleSender.Server;
        this.senderId = serverSecurity.getServerId();
        this.downloadingTo = "send";
        this.uploadingTo = "receive";
        this.setProcessBundle(this::settingProcessBundle);
    }

    public ClientFileServiceImpl() {
        super(null, null, null);
    }

    public void settingProcessBundle(){
       bundleTransmission.processReceivedBundles(BundleSender.Client, null);
    }
}
