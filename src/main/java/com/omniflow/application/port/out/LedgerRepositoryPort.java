package com.omniflow.application.port.out;

import com.omniflow.domain.ledger.JournalEntry;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.model.AccountId;

import java.util.List;
import java.util.Optional;

public interface LedgerRepositoryPort {

    Optional<LedgerAccount> findAccountById(AccountId id);

    void saveAccount(LedgerAccount account);

    void saveJournalEntry(JournalEntry entry);

    List<JournalEntry> findAllJournalEntries();

    List<JournalEntry> findJournalEntriesPaged(int page, int size);
}
