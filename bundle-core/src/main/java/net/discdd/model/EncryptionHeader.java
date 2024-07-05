package net.discdd.model;

import lombok.Builder;
import lombok.Data;
import lombok.Getter;

import java.io.File;

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
