package io.github.jacekkardys.systemproof.externalevidence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import io.github.jacekkardys.systemproof.observation.EvidenceCodec;
import io.github.jacekkardys.systemproof.observation.EvidenceSchemaId;

/**
 * Intentionally mutable test-only value that models an evidence type owned outside the journal
 * package.
 */
public final class MutableInteractionEvidence {
    private static final EvidenceCodec<MutableInteractionEvidence> CODEC = new Codec();

    private final byte[] payload;
    private final List<String> attributes;

    public MutableInteractionEvidence(byte[] payload, List<String> attributes) {
        this.payload = payload;
        this.attributes = attributes;
    }

    public byte[] payload() {
        return payload;
    }

    public List<String> attributes() {
        return attributes;
    }

    public static EvidenceCodec<MutableInteractionEvidence> codec() {
        return CODEC;
    }

    private static final class Codec implements EvidenceCodec<MutableInteractionEvidence> {
        private static final EvidenceSchemaId SCHEMA =
            new EvidenceSchemaId("test.external", "interaction", 1);

        @Override
        public EvidenceSchemaId schemaId() {
            return SCHEMA;
        }

        @Override
        public byte[] encode(MutableInteractionEvidence evidence) {
            if (evidence.attributes.stream().anyMatch(value -> value == null)) {
                throw new IllegalArgumentException(
                    "interaction attributes must not contain null"
                );
            }
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    output.writeInt(evidence.payload.length);
                    output.write(evidence.payload);
                    output.writeInt(evidence.attributes.size());
                    for (String attribute : evidence.attributes) {
                        output.writeUTF(attribute);
                    }
                }
                return bytes.toByteArray();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot encode test interaction evidence", failure);
            }
        }

        @Override
        public MutableInteractionEvidence decode(byte[] encodedEvidence) {
            try (DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(encodedEvidence)
            )) {
                byte[] payload = input.readNBytes(input.readInt());
                int attributeCount = input.readInt();
                List<String> attributes = new ArrayList<>(attributeCount);
                for (int index = 0; index < attributeCount; index++) {
                    attributes.add(input.readUTF());
                }
                if (input.available() != 0) {
                    throw new IllegalArgumentException(
                        "Encoded interaction evidence has trailing bytes"
                    );
                }
                return new MutableInteractionEvidence(payload, attributes);
            } catch (IOException | NegativeArraySizeException failure) {
                throw new IllegalArgumentException(
                    "Encoded interaction evidence is invalid",
                    failure
                );
            }
        }
    }
}
