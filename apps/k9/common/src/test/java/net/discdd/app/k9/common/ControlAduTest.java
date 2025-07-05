package net.discdd.app.k9.common;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

public class ControlAduTest {
    @Test
    public void testControlAdus() throws IOException {
        var origLoginAdu = new ControlAdu.LoginControlAdu(Map.of("email", "testuser", "password", "testpass"));
        var serDeserLoginAdu = ControlAdu.fromBytes(origLoginAdu.toBytes());
        Assertions.assertEquals(origLoginAdu, serDeserLoginAdu);

        var origLoginAckAdu = new ControlAdu.LoginAckControlAdu(Map.of("email",
                                                                       "testemail",
                                                                       "success",
                                                                       "true"));
        var serDeserLoginAckAdu = (ControlAdu.EmailAck)ControlAdu.fromBytes(origLoginAckAdu.toBytes());
        Assertions.assertTrue(serDeserLoginAckAdu.success());
        Assertions.assertEquals(origLoginAckAdu, serDeserLoginAckAdu);

        var origRegisterAdu = new ControlAdu.RegisterControlAdu(Map.of("prefix",
                                                                       "testprefix",
                                                                       "suffix",
                                                                       "testsuffix",
                                                                       "password",
                                                                       "testpass"));
        var serDeserRegisterAdu = ControlAdu.fromBytes(origRegisterAdu.toBytes());
        Assertions.assertEquals(origRegisterAdu, serDeserRegisterAdu);

        var origRegisterAckAdu =
                new ControlAdu.RegisterAckControlAdu(Map.of("email", "testemail"));
        var serDeserRegisterAckAdu = (ControlAdu.RegisterAckControlAdu)ControlAdu.fromBytes(origRegisterAckAdu.toBytes());
        Assertions.assertFalse(serDeserRegisterAckAdu.success());
        Assertions.assertEquals(origRegisterAckAdu, serDeserRegisterAckAdu);

        var origWhoAmIControlAdu = new ControlAdu.WhoAmIControlAdu();
        var serDeserWhoAmIControlAdu = ControlAdu.fromBytes(origWhoAmIControlAdu.toBytes());
        Assertions.assertEquals(origWhoAmIControlAdu, serDeserWhoAmIControlAdu);

        var origWhoAmIAckControlAdu = new ControlAdu.WhoAmIAckControlAdu(Map.of("email", "testemail"));
        var serDeserWhoAmIAckControlAdu = ControlAdu.fromBytes(origWhoAmIAckControlAdu.toBytes());
        Assertions.assertEquals(origWhoAmIAckControlAdu, serDeserWhoAmIAckControlAdu);
    }

    @Test
    public void testControlAduExtraData() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            new ControlAdu.LoginControlAdu(Map.of("username",
                                                  "testuser",
                                                  "password",
                                                  "testpass",
                                                  "extraData",
                                                  "testdata"));
        });
    }
}
