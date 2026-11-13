package net.discdd.app.k9;

import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import net.discdd.app.k9.common.ControlAdu;
import net.discdd.app.k9.repository.K9ClientIdToEmailMappingRepository;
import net.discdd.app.k9.repository.entity.K9ClientIdToEmailMapping;
import net.discdd.app.k9.service.EmailService;
import net.discdd.grpc.AppDataUnit;
import net.discdd.grpc.ExchangeADUsRequest;
import net.discdd.grpc.ExchangeADUsResponse;
import net.discdd.utils.StoreADUs;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class K9DDDAdapterLoginTest {
    private static final String CLIENT_ID = "client1";
    private static final String EMAIL = "alice@example.com";
    private static final String PASSWORD = "correct-password";
    private static final String FAILURE_MESSAGE = "Email doesn't exist or password is incorrect.";

    @TempDir
    Path tempDir;

    private K9ClientIdToEmailMappingRepository repository;
    private K9DDDAdapter adapter;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(K9ClientIdToEmailMappingRepository.class);
        adapter = new K9DDDAdapter(new StoreADUs(tempDir), Mockito.mock(EmailService.class));
        ReflectionTestUtils.setField(adapter, "clientToEmailRepository", repository);
        var hashedPassword = new BCryptPasswordEncoder().encode(PASSWORD);
        Mockito.when(repository.findById(EMAIL))
                .thenReturn(Optional.of(new K9ClientIdToEmailMapping(EMAIL, "otherClient", hashedPassword)));
    }

    @Test
    void wrongPasswordSendsFailureAck() throws IOException {
        var ack = loginAndGetAck(EMAIL, "wrong-password");
        Assertions.assertFalse(ack.success());
        Assertions.assertEquals(FAILURE_MESSAGE, ack.message());
    }

    @Test
    void unknownEmailSendsSameFailureAck() throws IOException {
        var ack = loginAndGetAck("nobody@example.com", PASSWORD);
        Assertions.assertFalse(ack.success());
        Assertions.assertEquals(FAILURE_MESSAGE, ack.message());
    }

    @Test
    void correctPasswordSendsSuccessAck() throws IOException {
        var ack = loginAndGetAck(EMAIL, PASSWORD);
        Assertions.assertTrue(ack.success());
        Assertions.assertEquals(EMAIL, ack.email());
    }

    private ControlAdu.LoginAckControlAdu loginAndGetAck(String email, String password) throws IOException {
        var login = new ControlAdu.LoginControlAdu(Map.of("email", email, "password", password, "locale", "en"));
        var request = ExchangeADUsRequest.newBuilder()
                .setClientId(CLIENT_ID)
                .addAdus(AppDataUnit.newBuilder().setAduId(1).setData(ByteString.copyFrom(login.toBytes())).build())
                .build();
        var responses = new ArrayList<ExchangeADUsResponse>();
        adapter.exchangeADUs(request, new StreamObserver<>() {
            @Override
            public void onNext(ExchangeADUsResponse response) {responses.add(response);}

            @Override
            public void onError(Throwable t) {Assertions.fail(t);}

            @Override
            public void onCompleted() {}
        });

        Assertions.assertEquals(1, responses.size());
        List<ControlAdu.LoginAckControlAdu> acks = new ArrayList<>();
        for (var adu : responses.get(0).getAdusList()) {
            var bytes = adu.getData().toByteArray();
            if (ControlAdu.isControlAdu(bytes) &&
                    ControlAdu.fromBytes(bytes) instanceof ControlAdu.LoginAckControlAdu loginAck) {
                acks.add(loginAck);
            }
        }
        Assertions.assertEquals(1, acks.size(), "expected exactly one login ack to be sent back to the client");
        return acks.get(0);
    }
}
