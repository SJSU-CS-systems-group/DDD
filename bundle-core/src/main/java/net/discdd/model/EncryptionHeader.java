package net.discdd.model;

import java.io.File;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

@Data
@Getter
@Builder
public class EncryptionHeader {
    private final File serverSignedPreKey;
    private final File serverIdentityKey;
    private final File serverRatchetKey;
    private File clientBaseKey;
    private File clientIdentityKey;
}
