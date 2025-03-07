package net.discdd.transport;

import net.discdd.bundlesecurity.SecurityUtils;
import net.discdd.tls.GrpcSecurity;
import org.bouncycastle.operator.OperatorCreationException;

import java.io.IOException;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.cert.CertificateException;

public class GrpcSecurityHolder {
    private static GrpcSecurity grpcSecurity;

    public static GrpcSecurity getGrpcSecurityHolder(){
        return grpcSecurity;
    }

    public static GrpcSecurity setGrpcSecurityHolder(Path path) throws InvalidAlgorithmParameterException, CertificateException, NoSuchAlgorithmException, IOException, NoSuchProviderException, OperatorCreationException {
        grpcSecurity = new GrpcSecurity(path, SecurityUtils.TRANSPORT);
        return grpcSecurity;
    }
}
