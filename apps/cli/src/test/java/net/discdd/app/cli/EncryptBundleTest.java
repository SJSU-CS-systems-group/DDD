package net.discdd.app.cli;

import com.github.stefanbirkner.systemlambda.SystemLambda;

import net.discdd.cli.EncryptBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.net.URISyntaxException;
import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
class EncryptBundleTest {
    public String bundlePath;
    public String applicationYml;
    public String appProps;
    public String clientId;
    String baseDirPath;

    @BeforeEach
    public void setUp() throws URISyntaxException, IOException {
        //get applicationYaml from resources
        applicationYml = TestUtils.getFromResources("application_copy.yml");
        //get bundle from resources
        bundlePath = TestUtils.getFromResources("vPVNQFG6BTfxjf7y2w5BNvtLG5OM3ys8aenorTlejOo=.bundle");
        //creates temp directories
        baseDirPath = TestUtils.makeEncryptsTempDirs();
        //create appProps
        clientId = "1EKo0fp8EKHarKiWehyAXCihSqs=";
    }
    @Test
    void testEncryptBundle() throws Exception {
        appProps = TestUtils.createResource("bundle-server.bundle-store-root = " + baseDirPath + File.separator);
        String expectedOutput =
                "Encrypting bundle\n" +
                        "Finished encrypting\n";
        StringBuilder sb = new StringBuilder();
        String errText = SystemLambda.tapSystemErr(() -> {
            String outText = SystemLambda.tapSystemOutNormalized(() -> {
                new CommandLine(new EncryptBundle()).execute("--decrypted-bundle=" + bundlePath, "--clientId=" + clientId,
                                                             "--applicationYaml=" + applicationYml, "--appProps=" + appProps);
            });
            sb.append(outText);
        });
        var outText = sb.toString();
        System.out.println("Standard Error: " + errText);
        assertEquals(expectedOutput, TestUtils.trimMessage(outText));
        assertEquals("", errText);
    }
}