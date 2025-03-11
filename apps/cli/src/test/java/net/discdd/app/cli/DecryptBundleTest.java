package net.discdd.app.cli;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import net.discdd.cli.DecryptBundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;

import static net.discdd.app.cli.TestUtils.escapeBackslash;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class DecryptBundleTest {
    public String bundlePath;
    public String applicationYml;
    public String appProps;
    public String expectedText = """
            Decrypting bundle
            Unzipped to
            Unzipped to
            Finished decrypting
            """;
    String baseDirPath;

    @BeforeEach
    public void setUp() throws URISyntaxException, IOException {
        //get applicationYaml from resources
        applicationYml = TestUtils.getFromResources("application_copy.yml");
        //get bundle from resources
        bundlePath = TestUtils.getFromResources("vPVNQFG6BTfxjf7y2w5BNvtLG5OM3ys8aenorTlejOo=.bundle");
        //creates temp directories
        baseDirPath = String.valueOf(TestUtils.makeDecryptsTempDirs());
        //create appProps
        appProps = TestUtils.createResource(
                "bundle-server.bundle-store-root = " + escapeBackslash(baseDirPath + File.separator));
    }
}