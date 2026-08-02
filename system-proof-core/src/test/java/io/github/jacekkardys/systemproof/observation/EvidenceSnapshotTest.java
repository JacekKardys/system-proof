package io.github.jacekkardys.systemproof.observation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import io.github.jacekkardys.systemproof.externalevidence.MutableInteractionEvidence;

class EvidenceSnapshotTest {
    @Test
    void shouldDetachEncodedBinaryDataAndEveryTypedDecode() {
        byte[] source = new byte[] {1, 2, 3};
        EvidenceCodec<byte[]> codec = new EvidenceCodec<>() {
            private final EvidenceSchemaId schema =
                new EvidenceSchemaId("test.external", "binary", 1);

            @Override
            public EvidenceSchemaId schemaId() {
                return schema;
            }

            @Override
            public byte[] encode(byte[] evidence) {
                return evidence;
            }

            @Override
            public byte[] decode(byte[] encodedEvidence) {
                return encodedEvidence;
            }
        };

        EvidenceSnapshot snapshot = EvidenceSnapshot.capture(codec, source);
        source[0] = 9;
        byte[] firstDecode = snapshot.decode(codec);

        assertThat(firstDecode).containsExactly(1, 2, 3);
        firstDecode[1] = 9;
        assertThat(snapshot.decode(codec)).containsExactly(1, 2, 3);
    }

    @Test
    void shouldRejectUnsupportedExternalEvidenceBeforeCreatingASnapshot() {
        MutableInteractionEvidence unsupported = new MutableInteractionEvidence(
            new byte[] {1},
            new ArrayList<>(Arrays.asList("valid", null))
        );

        assertThatThrownBy(() -> EvidenceSnapshot.capture(
            MutableInteractionEvidence.codec(),
            unsupported
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("interaction attributes must not contain null");
    }
}
