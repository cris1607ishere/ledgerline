package com.ledgerline.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Tamper-evidence primitive: each LedgerTransaction's hash is a function of
 * its own fields PLUS the previous transaction's hash. Change any historical
 * transaction's amount (even via a direct SQL UPDATE bypassing the app entirely)
 * and every hash computed after it will no longer match what's stored —
 * detectable by walking the chain and recomputing.
 *
 * This is the same hash-linking idea Git commits and blockchains use, applied
 * to a financial ledger instead of source history or a currency.
 *
 * IMPORTANT: both inputs below are normalized before hashing, on purpose.
 *   - Instant is truncated to microseconds, because Postgres TIMESTAMPTZ only
 *     stores microsecond precision. Instant.now() has nanosecond precision,
 *     so hashing the un-truncated value at write time and recomputing after
 *     a round trip through the database would silently disagree — a false
 *     "tamper detected" caused by a formatting mismatch, not real corruption.
 *   - BigDecimal is rescaled to the column's fixed scale (4 decimal places),
 *     because a value can come back from the database with a different
 *     internal scale than it was constructed with, which changes
 *     toPlainString() output ("50.00" vs "50.0000") without the value itself
 *     having changed.
 * Normalizing here means every caller — write path and verify path alike —
 * always hashes the same representation, so a mismatch only ever means the
 * data actually changed.
 */
public final class HashChainUtil {

    public static final String GENESIS_HASH = "0".repeat(64);
    private static final int AMOUNT_SCALE = 4;

    private HashChainUtil() {
    }

    public static String computeHash(
            String previousHash,
            UUID transactionId,
            UUID sourceAccountId,
            UUID destinationAccountId,
            BigDecimal amount,
            String currency,
            Instant createdAt
    ) {
        String payload = String.join("|",
                previousHash,
                transactionId.toString(),
                sourceAccountId.toString(),
                destinationAccountId.toString(),
                normalizeAmount(amount).toPlainString(),
                currency,
                normalizeInstant(createdAt).toString()
        );
        return sha256Hex(payload);
    }

    /** Truncate to the precision Postgres actually stores — call this BEFORE
     *  persisting a timestamp, not just before hashing, so the stored value
     *  and the hashed value are always identical. */
    public static Instant normalizeInstant(Instant instant) {
        return instant.truncatedTo(ChronoUnit.MICROS);
    }

    /** Rescale to the column's fixed precision so re-reads hash identically. */
    public static BigDecimal normalizeAmount(BigDecimal amount) {
        return amount.setScale(AMOUNT_SCALE, RoundingMode.UNNECESSARY);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present on every JVM; this is unreachable.
            throw new IllegalStateException(e);
        }
    }
}
