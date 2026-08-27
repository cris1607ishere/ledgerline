package com.ledgerline.controller;

import com.ledgerline.domain.Account;
import com.ledgerline.dto.AccountResponse;
import com.ledgerline.dto.CreateAccountRequest;
import com.ledgerline.repository.AccountRepository;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountRepository accountRepository;

    public AccountController(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @GetMapping
    public List<AccountResponse> listAccounts() {
        return accountRepository.findAll().stream()
                .map(a -> new AccountResponse(a.getId(), a.getOwnerName(), a.getCurrency(), a.getBalance()))
                .toList();
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setOwnerName(request.ownerName());
        account.setBalance(request.initialBalance());
        account.setCurrency(request.currency().toUpperCase());
        
        Account saved = accountRepository.save(account);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AccountResponse(saved.getId(), saved.getOwnerName(), saved.getCurrency(), saved.getBalance()));
    }
}
