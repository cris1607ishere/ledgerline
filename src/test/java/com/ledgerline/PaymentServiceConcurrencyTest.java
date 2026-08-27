package com.ledgerline;

import com.ledgerline.dto.PaymentRequest;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerTransactionRepository;
import com.ledgerline.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is the class that turns LedgerLine from "a design" into "a proven
 * design." It spins up a REAL Postgres (via Testcontainers, not a mock) and
 * fires genuinely concurrent requests at it — this is the only way to
 * actually exercise the row-locking and idempotency code paths; a
 * single-threaded test would never touch them.
 */
@SpringBootTest
@Testcontainers
class PaymentServiceConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ledgerline_test")
            .withUsername("ledgerline")
            .withPassword("ledgerline");

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    PaymentService paymentService;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    LedgerTransactionRepository transactionRepository;

    UUID alice;
    UUID bob;

    @BeforeEach
    void seedAccounts() {
        // The Flyway migration already seeds Alice (1000.00) and Bob (500.00) —
        // reuse the fixed IDs from V1__init_schema.sql for this test.
        alice = UUID.fromString("11111111-1111-1111-1111-111111111111");
        bob = UUID.fromString("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void fiftyConcurrentRequestsWithSameIdempotencyKeyProduceExactlyOneTransaction() throws InterruptedException {
        int concurrentRequests = 50;
        String sharedIdempotencyKey = "test-key-" + UUID.randomUUID();
        PaymentRequest request = new PaymentRequest(alice, bob, new BigDecimal("10.00"), "INR");

        ExecutorService pool = Executors.newFixedThreadPool(concurrentRequests);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(concurrentRequests);
        AtomicInteger successes = new AtomicInteger();

        for (int i = 0; i < concurrentRequests; i++) {
            pool.submit(() -> {
                try {
                    startLine.await();
                    paymentService.processPayment(request, sharedIdempotencyKey);
                    successes.incrementAndGet();
                } catch (Exception ignored) {
                    // A small number of DataIntegrityViolationExceptions on the
                    // idempotency key race is expected and fine — the important
                    // assertion is below: exactly one transaction ever gets created.
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startLine.countDown(); // release all 50 threads at once
        finishLine.await();
        pool.shutdown();

        long transactionsCreatedForThisKey = transactionRepository.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals(sharedIdempotencyKey))
                .count();

        assertThat(transactionsCreatedForThisKey).isEqualTo(1);
    }

    @Test
    void concurrentOverdraftAttemptsNeverAllowNegativeBalance() throws InterruptedException {
        // Bob has 500.00. Fire ten concurrent 100.00 transfers OUT of Bob's
        // account — at most 5 should succeed, and Bob's balance must never
        // go negative no matter how the row locks interleave.
        int attempts = 10;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(attempts);

        for (int i = 0; i < attempts; i++) {
            String key = "overdraft-test-" + UUID.randomUUID();
            PaymentRequest request = new PaymentRequest(bob, alice, new BigDecimal("100.00"), "INR");
            pool.submit(() -> {
                try {
                    startLine.await();
                    paymentService.processPayment(request, key);
                } catch (Exception ignored) {
                    // Expected: InsufficientBalanceException for the attempts that lose the race.
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startLine.countDown();
        finishLine.await();
        pool.shutdown();

        BigDecimal finalBalance = accountRepository.findById(bob).orElseThrow().getBalance();
        assertThat(finalBalance).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    @Test
    void concurrentOppositeDirectionTransfersNeverDeadlock() throws InterruptedException {
        // Alice -> Bob (10 threads, 10.00 each) and Bob -> Alice (10 threads, 10.00 each)
        // fired at the exact same instant. Without deterministic lock ordering (lower UUID first),
        // this would cause PostgreSQL deadlock exceptions.
        int threadsPerDirection = 10;
        int totalRequests = threadsPerDirection * 2;
        ExecutorService pool = Executors.newFixedThreadPool(totalRequests);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(totalRequests);
        AtomicInteger successfulTransfers = new AtomicInteger();

        for (int i = 0; i < threadsPerDirection; i++) {
            String keyAB = "deadlock-test-ab-" + UUID.randomUUID();
            PaymentRequest reqAB = new PaymentRequest(alice, bob, new BigDecimal("10.00"), "INR");
            pool.submit(() -> {
                try {
                    startLine.await();
                    paymentService.processPayment(reqAB, keyAB);
                    successfulTransfers.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    finishLine.countDown();
                }
            });

            String keyBA = "deadlock-test-ba-" + UUID.randomUUID();
            PaymentRequest reqBA = new PaymentRequest(bob, alice, new BigDecimal("10.00"), "INR");
            pool.submit(() -> {
                try {
                    startLine.await();
                    paymentService.processPayment(reqBA, keyBA);
                    successfulTransfers.incrementAndGet();
                } catch (Exception ignored) {
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startLine.countDown();
        finishLine.await();
        pool.shutdown();

        // Both accounts should have completed without deadlocks
        assertThat(successfulTransfers.get()).isEqualTo(totalRequests);
    }

    @Test
    void concurrentTransfersMaintainStrictTotalMoneyConservation() throws InterruptedException {
        // The total sum of balances across Alice and Bob must remain invariant,
        // no matter how many concurrent payments cross paths.
        BigDecimal initialAlice = accountRepository.findById(alice).orElseThrow().getBalance();
        BigDecimal initialBob = accountRepository.findById(bob).orElseThrow().getBalance();
        BigDecimal totalSystemMoneyBefore = initialAlice.add(initialBob);

        int transfers = 20;
        ExecutorService pool = Executors.newFixedThreadPool(transfers);
        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finishLine = new CountDownLatch(transfers);

        for (int i = 0; i < transfers; i++) {
            final boolean aliceToBob = (i % 2 == 0);
            String key = "conservation-test-" + UUID.randomUUID();
            PaymentRequest req = aliceToBob
                    ? new PaymentRequest(alice, bob, new BigDecimal("15.00"), "INR")
                    : new PaymentRequest(bob, alice, new BigDecimal("15.00"), "INR");

            pool.submit(() -> {
                try {
                    startLine.await();
                    paymentService.processPayment(req, key);
                } catch (Exception ignored) {
                } finally {
                    finishLine.countDown();
                }
            });
        }

        startLine.countDown();
        finishLine.await();
        pool.shutdown();

        BigDecimal finalAlice = accountRepository.findById(alice).orElseThrow().getBalance();
        BigDecimal finalBob = accountRepository.findById(bob).orElseThrow().getBalance();
        BigDecimal totalSystemMoneyAfter = finalAlice.add(finalBob);

        assertThat(totalSystemMoneyAfter).isEqualByComparingTo(totalSystemMoneyBefore);
    }
}
