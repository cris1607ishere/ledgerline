package com.ledgerline.util;

import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.repository.LedgerTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Walks every LedgerTransaction in the order they were chained (oldest
 * first), recomputing each hash from its stored fields and checking:
 *   1. previousHash on this row matches the hash of the row before it
 *      (or GENESIS_HASH, for the very first transaction)
 *   2. the recomputed hash matches what's stored in the hash column
 *
 * The first row that fails either check is where investigation starts —
 * everything before it is provably untouched since it was written.
 *
 * This does NOT modify anything. Recovery from a genuine break is a
 * manual, out-of-band process (see README) — never an automatic rewrite.
 */
@Component
public class ChainVerifier {

    private final LedgerTransactionRepository transactionRepository;

    public ChainVerifier(LedgerTransactionRepository transactionRepository) {
        this.transactionRepository = transactionRepository;
    }

    public ChainVerificationResult verify() {
        List<LedgerTransaction> chain = transactionRepository.findAll();
        chain.sort(Comparator.comparing(LedgerTransaction::getCreatedAt));

        String expectedPreviousHash = HashChainUtil.GENESIS_HASH;
        long checked = 0;

        for (LedgerTransaction transaction : chain) {
            checked++;

            if (!transaction.getPreviousHash().equals(expectedPreviousHash)) {
                return ChainVerificationResult.brokenAt(checked, transaction.getId(),
                        "previousHash does not match the hash of the prior transaction in chain order — "
                                + "either this row was reordered/altered, or an earlier row was.");
            }

            String recomputed = HashChainUtil.computeHash(
                    transaction.getPreviousHash(),
                    transaction.getId(),
                    transaction.getSourceAccountId(),
                    transaction.getDestinationAccountId(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    transaction.getCreatedAt()
            );

            if (!recomputed.equals(transaction.getHash())) {
                return ChainVerificationResult.brokenAt(checked, transaction.getId(),
                        "recomputed hash does not match the stored hash — one or more of this "
                                + "transaction's fields were changed after it was written.");
            }

            expectedPreviousHash = transaction.getHash();
        }

        return ChainVerificationResult.ok(checked);
    }
}
