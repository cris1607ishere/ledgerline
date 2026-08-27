package com.ledgerline.controller;

import com.ledgerline.util.ChainVerificationResult;
import com.ledgerline.util.ChainVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Not meant to be public-facing in a real deployment (put this behind auth /
 * an internal-only network path). Exists here so the tamper-evidence feature
 * is demoable with a single curl call: run a payment, corrupt a row directly
 * in psql, hit this endpoint, watch it name the exact transaction.
 */
@RestController
@RequestMapping("/admin/chain")
public class AdminController {

    private final ChainVerifier chainVerifier;

    public AdminController(ChainVerifier chainVerifier) {
        this.chainVerifier = chainVerifier;
    }

    @GetMapping("/verify")
    public ResponseEntity<ChainVerificationResult> verifyChain() {
        ChainVerificationResult result = chainVerifier.verify();
        HttpStatus status = result.valid() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(result);
    }
}
