package com.ledgerline;

import com.ledgerline.dto.PaymentRequest;
import com.ledgerline.service.PaymentService;
import com.ledgerline.util.ChainVerificationResult;
import com.ledgerline.util.ChainVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The point of this test: prove the hash chain catches tampering that
 * happens OUTSIDE the application entirely — a direct SQL UPDATE, as if
 * someone had raw database access (a compromised admin account, a rogue
 * DBA). No amount of application-level validation can stop this; the hash
 * chain is what makes it detectable after the fact.
 */
@SpringBootTest
@Testcontainers
class ChainVerifierTamperDetectionTest {

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
    ChainVerifier chainVerifier;

    @Autowired
    JdbcTemplate jdbcTemplate;

    static final UUID ALICE = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID BOB = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void verifierPassesOnAnUntamperedChain() {
        paymentService.processPayment(
                new PaymentRequest(ALICE, BOB, new BigDecimal("25.00"), "INR"),
                "tamper-test-baseline-" + UUID.randomUUID());

        ChainVerificationResult result = chainVerifier.verify();

        assertThat(result.valid()).isTrue();
        assertThat(result.brokenTransactionId()).isNull();
    }

    @Test
    void verifierPinpointsATransactionAlteredByRawSql() {
        var response = paymentService.processPayment(
                new PaymentRequest(ALICE, BOB, new BigDecimal("25.00"), "INR"),
                "tamper-test-target-" + UUID.randomUUID());
        UUID targetTransactionId = response.transactionId();

        // A second, later transaction so the chain has something after the
        // tampered row too — proving detection isn't just "it's the last row."
        paymentService.processPayment(
                new PaymentRequest(ALICE, BOB, new BigDecimal("10.00"), "INR"),
                "tamper-test-after-" + UUID.randomUUID());

        // Simulate an attacker (or a well-meaning but wrong manual fix) with
        // direct database access — completely bypassing the application and
        // its @Transactional guarantees.
        jdbcTemplate.update(
                "UPDATE ledger_transactions SET amount = ? WHERE id = ?",
                new BigDecimal("999.00"), targetTransactionId);

        ChainVerificationResult result = chainVerifier.verify();

        assertThat(result.valid()).isFalse();
        assertThat(result.brokenTransactionId()).isEqualTo(targetTransactionId);
        assertThat(result.reason()).contains("recomputed hash does not match");
    }
}
