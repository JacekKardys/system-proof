package io.github.jacekkardys.systemproof.junit.internal;

import io.github.jacekkardys.systemproof.junit.annotation.SystemProof;
import java.util.LinkedHashMap;
import lombok.val;

/** Publishes optional System Proof scenario metadata through the standard JUnit report channel. */
final class SystemProofMetadataReporter {

    private static final String TITLE = "system-proof.title";
    private static final String DESCRIPTION = "system-proof.description";

    void report(SystemProofSharedContext context, SystemProof declaration) {
        val entries = new LinkedHashMap<String, String>();
        addIfPresent(entries, TITLE, declaration.title());
        addIfPresent(entries, DESCRIPTION, declaration.description());
        if (!entries.isEmpty()) {
            context.publishReportEntry(entries);
        }
    }

    private static void addIfPresent(
        LinkedHashMap<String, String> entries,
        String key,
        String value
    ) {
        val normalized = value.strip();
        if (!normalized.isEmpty()) {
            entries.put(key, normalized);
        }
    }
}
