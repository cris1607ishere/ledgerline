package com.ledgerline.util;

import java.util.UUID;

/**
 * Result of walking the hash chain from genesis. If valid is false, the
 * break was found at brokenTransactionId — everything before it in chain
 * order is still provably untouched, so triage starts exactly there.
 */
public record ChainVerificationResult(
        boolean valid,
        long transactionsChecked,
        UUID brokenTransactionId,
        String reason
) {
    public static ChainVerificationResult ok(long transactionsChecked) {
        return new ChainVerificationResult(true, transactionsChecked, null, null);
    }

    public static ChainVerificationResult brokenAt(long transactionsChecked, UUID transactionId, String reason) {
        return new ChainVerificationResult(false, transactionsChecked, transactionId, reason);
    }
}
