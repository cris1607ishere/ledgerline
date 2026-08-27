package com.ledgerline.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single row (id = 1) holding the hash of the most recently written
 * LedgerTransaction. Every new transaction locks this row with
 * SELECT ... FOR UPDATE, links its previousHash to the current value,
 * computes its own hash, and updates this row — forming a chain where
 * altering any historical transaction breaks every hash after it.
 */
@Entity
@Table(name = "chain_state")
@Getter
@Setter
@NoArgsConstructor
public class ChainState {

    @Id
    private Short id = 1;

    @Column(name = "last_hash", nullable = false, length = 64)
    private String lastHash;
}
