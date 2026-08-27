package com.ledgerline.service;

import com.ledgerline.domain.*;
import com.ledgerline.dto.PaymentRequest;
import com.ledgerline.dto.PaymentResponse;
import com.ledgerline.exception.AccountNotFoundException;
import com.ledgerline.exception.InsufficientBalanceException;
import com.ledgerline.repository.*;
import com.ledgerline.util.HashChainUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ChainStateRepository chainStateRepository;

    public PaymentService(
            AccountRepository accountRepository,
            LedgerTransactionRepository transactionRepository,
            LedgerEntryRepository entryRepository,
            IdempotencyRecordRepository idempotencyRepository,
            ChainStateRepository chainStateRepository
    ) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.chainStateRepository = chainStateRepository;
    }

    /**
     * The whole point of the project lives in this one method. Everything
     * inside it commits together or not at all, because of @Transactional.
     *
     * Order of operations matters:
     *   1. Idempotency check       -> stops duplicate processing on retry
     *   2. Deterministic locking   -> stops double-spending AND avoids deadlock
     *   3. Balance check + update  -> the actual money movement
     *   4. Ledger entries          -> the permanent, append-only record
     *   5. Hash chain append       -> makes that record tamper-evident
     *   6. Idempotency record      -> so future retries short-circuit at step 1
     */
    @Transactional
    public PaymentResponse processPayment(PaymentRequest request, String idempotencyKey) {

        // 1. Idempotency check. If we've seen this key before, hand back the
        //    original result instead of processing the payment again.
        Optional<IdempotencyRecord> existing = idempotencyRepository.findById(idempotencyKey);
        if (existing.isPresent()) {
            LedgerTransaction previous = transactionRepository
                    .findById(existing.get().getTransactionId())
                    .orElseThrow();
            return toResponse(previous);
        }

        // 2. Lock both accounts in a single deterministic order (smaller UUID
        //    first) regardless of who is source and who is destination. Two
        //    opposite-direction transfers (A->B and B->A at the same time)
        //    would deadlock if each transaction locked "source, then
        //    destination" — this ordering rule is what prevents that.
        UUID firstToLock = request.sourceAccountId().compareTo(request.destinationAccountId()) < 0
                ? request.sourceAccountId() : request.destinationAccountId();
        UUID secondToLock = request.sourceAccountId().compareTo(request.destinationAccountId()) < 0
                ? request.destinationAccountId() : request.sourceAccountId();

        Account lockedFirst = accountRepository.findByIdForUpdate(firstToLock)
                .orElseThrow(() -> new AccountNotFoundException(firstToLock));
        Account lockedSecond = accountRepository.findByIdForUpdate(secondToLock)
                .orElseThrow(() -> new AccountNotFoundException(secondToLock));

        Account source = lockedFirst.getId().equals(request.sourceAccountId()) ? lockedFirst : lockedSecond;
        Account destination = lockedFirst.getId().equals(request.destinationAccountId()) ? lockedFirst : lockedSecond;

        // 3. Balance check + update. Both accounts are locked, so no other
        //    transaction can read a stale balance while we do this.
        if (source.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientBalanceException(
                    "Account " + source.getId() + " has insufficient balance for this transfer");
        }
        source.setBalance(source.getBalance().subtract(request.amount()));
        destination.setBalance(destination.getBalance().add(request.amount()));
        accountRepository.save(source);
        accountRepository.save(destination);

        // 4 + 5. Write the transaction and its hash in the same step, since
        // the hash depends on the transaction's own fields.
        ChainState chainState = chainStateRepository.findSingletonForUpdate();
        // Truncated to microseconds up front — the SAME value gets stored
        // and hashed, so a later re-read from Postgres (which only keeps
        // microsecond precision anyway) always hashes identically. See the
        // note in HashChainUtil for why this matters.
        Instant now = HashChainUtil.normalizeInstant(Instant.now());

        LedgerTransaction transaction = new LedgerTransaction();
        transaction.setId(UUID.randomUUID());
        transaction.setIdempotencyKey(idempotencyKey);
        transaction.setSourceAccountId(request.sourceAccountId());
        transaction.setDestinationAccountId(request.destinationAccountId());
        transaction.setAmount(request.amount());
        transaction.setCurrency(request.currency());
        transaction.setStatus(LedgerTransaction.TransactionStatus.SETTLED);
        transaction.setCreatedAt(now);
        transaction.setPreviousHash(chainState.getLastHash());
        transaction.setHash(HashChainUtil.computeHash(
                chainState.getLastHash(),
                transaction.getId(),
                request.sourceAccountId(),
                request.destinationAccountId(),
                request.amount(),
                request.currency(),
                now
        ));
        transactionRepository.save(transaction);

        chainState.setLastHash(transaction.getHash());
        chainStateRepository.save(chainState);

        // The two ledger entries. This is the "double" in double-entry:
        // they must sum to zero, and the reconciliation job (Phase 2) checks
        // exactly that, across the whole table, on a schedule.
        LedgerEntry debit = new LedgerEntry();
        debit.setId(UUID.randomUUID());
        debit.setTransactionId(transaction.getId());
        debit.setAccountId(source.getId());
        debit.setAmount(request.amount().negate());
        debit.setEntryType(EntryType.DEBIT);
        debit.setCreatedAt(now);

        LedgerEntry credit = new LedgerEntry();
        credit.setId(UUID.randomUUID());
        credit.setTransactionId(transaction.getId());
        credit.setAccountId(destination.getId());
        credit.setAmount(request.amount());
        credit.setEntryType(EntryType.CREDIT);
        credit.setCreatedAt(now);

        entryRepository.save(debit);
        entryRepository.save(credit);

        // 6. Idempotency record last, so a duplicate request that arrives
        //    mid-transaction (before commit) still fails the UNIQUE constraint
        //    rather than racing past this check.
        IdempotencyRecord record = new IdempotencyRecord();
        record.setIdempotencyKey(idempotencyKey);
        record.setTransactionId(transaction.getId());
        record.setStatus("SETTLED");
        idempotencyRepository.save(record);

        return toResponse(transaction);
    }

    private PaymentResponse toResponse(LedgerTransaction transaction) {
        return new PaymentResponse(
                transaction.getId(),
                transaction.getStatus().name(),
                transaction.getSourceAccountId(),
                transaction.getDestinationAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getHash()
        );
    }
}
