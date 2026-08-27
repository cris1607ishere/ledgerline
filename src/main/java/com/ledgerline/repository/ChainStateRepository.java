package com.ledgerline.repository;

import com.ledgerline.domain.ChainState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ChainStateRepository extends JpaRepository<ChainState, Short> {

    /**
     * Locks the single chain_state row so concurrent transactions append
     * to the hash chain one at a time, even though they may be updating
     * entirely different accounts. This is the one deliberate serialization
     * point in the system — see the note on this trade-off in the README.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChainState c where c.id = 1")
    ChainState findSingletonForUpdate();
}
