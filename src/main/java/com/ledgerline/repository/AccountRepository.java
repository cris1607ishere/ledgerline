package com.ledgerline.repository;

import com.ledgerline.domain.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * SELECT ... FOR UPDATE. This is the entire correctness mechanism for
     * preventing double-spending — it must only ever be called from inside
     * an @Transactional method, and callers must always lock accounts in a
     * single deterministic order (see PaymentService) to avoid deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(UUID id);
}
