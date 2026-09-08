package org.openphc.tiberbu.cce.emitter.cloudevents;

import org.openphc.tiberbu.cce.emitter.model.SourceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * Generates the CloudEvents {@code id} for one bundle entry — deterministic
 * whenever there's enough information to be confident about it.
 *
 * <p><b>Why not a header-derived key.</b> tibERbu sends no per-event header
 * (confirmed), so the id cannot be keyed on anything like {@code
 * X-Source-Event-Id}. Two payload-derived candidates were ruled out using real
 * tibERbu sample data: {@code meta.bundleId + entry.resource.id} and {@code
 * entry.request.url} both collided across two genuinely distinct real events
 * (same bundleId, resourceId, and request URL, ~26 minutes apart, from two
 * different consent-capture flows for the same person) — the Collector would
 * have silently dropped the second one as a duplicate. That is active data
 * loss, strictly worse than dedup simply not firing.
 *
 * <p><b>The key actually used.</b> {@code meta.traceId} — the field that
 * genuinely differed between those two colliding samples — combined with the
 * entry's own {@code resource.id} as the per-entry differentiator (a bundle
 * has one {@code traceId} but N entries). {@link #generate} hashes {@code
 * traceId + ":" + resourceId} into a UUID v5 (SHA-1, RFC 4122 § 4.3, DNS
 * namespace): the same pair always produces the same id, so a genuine replay
 * is caught, while two requests with different {@code traceId}s never collide
 * even if everything else about them matches.
 *
 * <p><b>When {@code traceId} is absent or blank.</b> There is no confirmed
 * guarantee yet that tibERbu always sends one — its global uniqueness across
 * all tibERbu traffic is still unconfirmed with the integration owner.
 * Falling back to a payload-derived key without it (e.g. {@code bundleId +
 * resourceId} alone) would resurrect the exact collision already proven with
 * real data above. Instead this falls back to {@link UUID#randomUUID()}: the
 * one event loses dedup (a replay of it would get a different id and not be
 * caught), but no genuinely new event risks being silently dropped as a false
 * duplicate — the same "never guess into a collision" principle that ruled
 * out the two candidates above.
 *
 * <p><b>When {@code resource.id} is absent but {@code traceId} is present.</b>
 * The per-entry differentiator falls back to {@link
 * SourceMetadata#bundleEntryIndex()} instead — always available, and still
 * unique within one bundle — rather than giving up determinism entirely just
 * because one FHIR resource happened to omit its own {@code id}.
 */
@Component
public class EventIdGenerator {

    private static final Logger log = LoggerFactory.getLogger(EventIdGenerator.class);

    /**
     * UUID v5 namespace — the well-known DNS namespace, RFC 4122 Appendix C.
     * Any fixed namespace works for our purposes (we only need
     * self-consistency, not interoperability with DNS-based UUIDs elsewhere);
     * this one is simply the standard, recognizable choice.
     */
    private static final UUID NAMESPACE_DNS = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    /**
     * Generates the CloudEvents {@code id} for one bundle entry.
     *
     * <p>Example — {@code metadata.traceId()="ef1cb56375"}, {@code
     * entryResourceId="VCR-20260901-57098420"}: hashes {@code
     * "ef1cb56375:VCR-20260901-57098420"} into a UUID v5. Calling this again
     * with the exact same two inputs (a genuine replay) returns the identical
     * id; a different {@code traceId} (a genuinely new submission) or a
     * different {@code entryResourceId} (a different entry in the same
     * bundle) both produce a different id.
     *
     * @param metadata        this entry's source metadata; supplies {@code
     *                        traceId} and, as a fallback, {@code
     *                        bundleEntryIndex}
     * @param entryResourceId the entry's own {@code resource.id}, or {@code
     *                        null}/blank when the resource doesn't carry one
     * @return a UUID string — deterministic when {@code traceId} is present,
     *         random otherwise
     */
    public String generate(SourceMetadata metadata, String entryResourceId) {
        String traceId = metadata.traceId();
        if (traceId == null || traceId.isBlank()) {
            String randomId = UUID.randomUUID().toString();
            log.warn("No traceId available for source='{}' path='{}' — generated a random, "
                            + "non-deterministic event id '{}'; a replay of this request will NOT be caught as a duplicate",
                    metadata.sourceIdentifier(), metadata.sourcePath(), randomId);
            return randomId;
        }

        String perEntryDifferentiator = (entryResourceId != null && !entryResourceId.isBlank())
                ? entryResourceId
                : String.valueOf(metadata.bundleEntryIndex());
        if (entryResourceId == null || entryResourceId.isBlank()) {
            log.debug("Entry has no resource.id — falling back to bundleEntryIndex={} as the per-entry differentiator",
                    metadata.bundleEntryIndex());
        }

        String name = traceId + ":" + perEntryDifferentiator;
        String deterministicId = generateUuidV5(name).toString();
        log.debug("Generated deterministic event id '{}' from name '{}'", deterministicId, name);
        return deterministicId;
    }

    /**
     * Generates a UUID v5 (SHA-1 name-based) from the given name string, under
     * {@link #NAMESPACE_DNS}.
     */
    private UUID generateUuidV5(String name) {
        return nameUuidFromNamespaceAndString(NAMESPACE_DNS, name);
    }

    /**
     * Implements UUID v5 generation per RFC 4122 § 4.3: combines a namespace
     * UUID with a name string, hashes with SHA-1, and sets the version (5) and
     * variant (RFC 4122) bits on the resulting digest.
     */
    static UUID nameUuidFromNamespaceAndString(UUID namespace, String name) {
        try {
            MessageDigest sha1Digest = MessageDigest.getInstance("SHA-1");
            sha1Digest.update(toBytes(namespace));
            sha1Digest.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1Digest.digest();

            // Set version 5 (bits 4-7 of byte 6)
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            // Set variant to RFC 4122 (bits 6-7 of byte 8)
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);

            long mostSignificantBits = 0;
            for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
                mostSignificantBits = (mostSignificantBits << 8) | (hash[byteIndex] & 0xFF);
            }
            long leastSignificantBits = 0;
            for (int byteIndex = 8; byteIndex < 16; byteIndex++) {
                leastSignificantBits = (leastSignificantBits << 8) | (hash[byteIndex] & 0xFF);
            }

            return new UUID(mostSignificantBits, leastSignificantBits);
        } catch (NoSuchAlgorithmException impossibleOnAnyJvm) {
            // SHA-1 is guaranteed present by the Java Cryptography Architecture spec.
            throw new IllegalStateException("SHA-1 algorithm not available", impossibleOnAnyJvm);
        }
    }

    private static byte[] toBytes(UUID uuid) {
        long mostSignificantBits = uuid.getMostSignificantBits();
        long leastSignificantBits = uuid.getLeastSignificantBits();
        byte[] bytes = new byte[16];
        for (int byteIndex = 0; byteIndex < 8; byteIndex++) {
            bytes[byteIndex] = (byte) (mostSignificantBits >>> (8 * (7 - byteIndex)));
        }
        for (int byteIndex = 8; byteIndex < 16; byteIndex++) {
            bytes[byteIndex] = (byte) (leastSignificantBits >>> (8 * (15 - byteIndex)));
        }
        return bytes;
    }
}
