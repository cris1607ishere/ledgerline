package com.ledgerline.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record PaymentResponse(
        UUID transactionId,
        String status,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        String hash
) {
}
