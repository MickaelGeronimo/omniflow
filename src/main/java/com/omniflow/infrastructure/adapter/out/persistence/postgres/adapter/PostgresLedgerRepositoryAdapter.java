package com.omniflow.infrastructure.adapter.out.persistence.postgres.adapter;

import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.application.service.FinancialOrchestratorService;
import com.omniflow.domain.exception.OptimisticConcurrencyException;
import com.omniflow.domain.ledger.AccountType;
import com.omniflow.domain.ledger.JournalEntry;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.ledger.PostingLeg;
import com.omniflow.domain.model.AccountId;
import com.omniflow.domain.model.Money;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.JournalEntryJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.LedgerAccountJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.entity.PostingLegJpaEntity;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataJournalEntryRepository;
import com.omniflow.infrastructure.adapter.out.persistence.postgres.repository.SpringDataLedgerAccountRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
public class PostgresLedgerRepositoryAdapter implements LedgerRepositoryPort {

    private final SpringDataLedgerAccountRepository accountRepo;
    private final SpringDataJournalEntryRepository journalRepo;

    public PostgresLedgerRepositoryAdapter(
            SpringDataLedgerAccountRepository accountRepo,
            SpringDataJournalEntryRepository journalRepo) {
        this.accountRepo = accountRepo;
        this.journalRepo = journalRepo;
    }

    @PostConstruct
    public void seedDefaultAccountsIfEmpty() {
        if (accountRepo.count() == 0) {
            // Seed Settlement Transit Account
            accountRepo.save(new LedgerAccountJpaEntity(
                    FinancialOrchestratorService.SETTLEMENT_TRANSIT_ACCOUNT.toString(),
                    "Central Clearing Transit Buffer",
                    AccountType.LIABILITY,
                    "USD",
                    Money.zero("USD").amount(),
                    true
            ));

            // Seed Debtor Corporate Account
            AccountId debtor = AccountId.of("OMNI", "0001", "1001-9");
            accountRepo.save(new LedgerAccountJpaEntity(
                    debtor.toString(),
                    "Corporate Operating Account",
                    AccountType.LIABILITY,
                    "USD",
                    Money.of("500000.00", "USD").amount(),
                    false
            ));

            // Seed Creditor Merchant Account
            AccountId creditor = AccountId.of("OMNI", "0001", "2002-8");
            accountRepo.save(new LedgerAccountJpaEntity(
                    creditor.toString(),
                    "Merchant Settlement Account",
                    AccountType.LIABILITY,
                    "USD",
                    Money.of("1000.00", "USD").amount(),
                    false
            ));

            // Seed Marketplace Demo Accounts (Buyer, Merchant, Take-Rate Fee, Escrow Reserve)
            AccountId buyer = AccountId.of("OMNI", "0001", "BUYER-01");
            accountRepo.save(new LedgerAccountJpaEntity(
                    buyer.toString(),
                    "Demo Marketplace Buyer",
                    AccountType.LIABILITY,
                    "USD",
                    Money.of("50000.00", "USD").amount(),
                    false
            ));

            AccountId mktMerchant = AccountId.of("OMNI", "0001", "MERCHANT-99");
            accountRepo.save(new LedgerAccountJpaEntity(
                    mktMerchant.toString(),
                    "Demo Marketplace Merchant",
                    AccountType.LIABILITY,
                    "USD",
                    Money.of("500.00", "USD").amount(),
                    false
            ));

            AccountId feeAccount = AccountId.of("OMNI", "0001", "FEE-001");
            accountRepo.save(new LedgerAccountJpaEntity(
                    feeAccount.toString(),
                    "Platform Take-Rate Revenue Account",
                    AccountType.REVENUE,
                    "USD",
                    Money.zero("USD").amount(),
                    true
            ));

            AccountId escrowAccount = AccountId.of("OMNI", "0001", "ESCROW-001");
            accountRepo.save(new LedgerAccountJpaEntity(
                    escrowAccount.toString(),
                    "Dispute & Chargeback Risk Escrow Buffer",
                    AccountType.LIABILITY,
                    "USD",
                    Money.zero("USD").amount(),
                    true
            ));
        }
    }

    @Override
    public Optional<LedgerAccount> findAccountById(AccountId id) {
        return accountRepo.findById(id.toString()).map(this::toDomain);
    }

    @Override
    @Transactional
    public void saveAccount(LedgerAccount account) {
        accountRepo.findById(account.getId().toString())
                .ifPresentOrElse(entity -> {
                    long currentVersion = entity.getVersion() != null ? entity.getVersion() : -1L;
                    if (account.getVersion() >= 0 && account.getVersion() != currentVersion) {
                        throw new OptimisticConcurrencyException(
                                String.format("Optimistic lock mismatch on account [%s]. Expected version: %d, current database version: %d",
                                        account.getId(), account.getVersion(), currentVersion));
                    }
                    entity.setBalance(account.getBalance().amount());
                    accountRepo.save(entity);
                }, () -> {
                    accountRepo.save(new LedgerAccountJpaEntity(
                            account.getId().toString(),
                            account.getName(),
                            account.getType(),
                            account.getCurrency(),
                            account.getBalance().amount(),
                            account.isAllowOverdraft()
                    ));
                });
    }

    @Override
    @Transactional
    public void saveJournalEntry(JournalEntry entry) {
        JournalEntryJpaEntity entity = new JournalEntryJpaEntity(
                entry.getEntryId(),
                entry.getReferenceId(),
                entry.getTimestamp(),
                entry.getMemo(),
                new ArrayList<>()
        );

        List<PostingLegJpaEntity> legEntities = entry.getLegs().stream()
                .map(leg -> new PostingLegJpaEntity(
                        entity,
                        leg.accountId().toString(),
                        leg.type(),
                        leg.amount().amount(),
                        leg.amount().currencyCode(),
                        leg.description()
                ))
                .toList();

        entity.getLegs().addAll(legEntities);
        journalRepo.save(entity);
    }

    @Override
    public List<JournalEntry> findAllJournalEntries() {
        return journalRepo.findAll().stream().map(this::toDomainJournal).toList();
    }

    @Override
    public List<JournalEntry> findJournalEntriesPaged(int page, int size) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size);
        return journalRepo.findAll(pageable).stream().map(this::toDomainJournal).toList();
    }

    @Override
    public List<JournalEntry> findJournalEntriesKeyset(String lastEntryId, java.time.Instant cutoff, int limit) {
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, limit);
        return journalRepo.findByKeyset(lastEntryId, cutoff, pageable).stream().map(this::toDomainJournal).toList();
    }

    private JournalEntry toDomainJournal(JournalEntryJpaEntity entity) {
        List<PostingLeg> domainLegs = entity.getLegs().stream().map(leg -> new PostingLeg(
                AccountId.parse(leg.getAccountId()),
                leg.getPostingType(),
                Money.of(leg.getAmount(), leg.getCurrency()),
                leg.getDescription()
        )).toList();

        return new JournalEntry(
                entity.getEntryId(),
                entity.getReferenceId(),
                entity.getTimestamp(),
                entity.getMemo(),
                domainLegs
        );
    }

    private LedgerAccount toDomain(LedgerAccountJpaEntity entity) {
        return new LedgerAccount(
                AccountId.parse(entity.getAccountId()),
                entity.getAccountName(),
                entity.getAccountType(),
                entity.getCurrency(),
                Money.of(entity.getBalance(), entity.getCurrency()),
                entity.isAllowOverdraft(),
                entity.getVersion() != null ? entity.getVersion() : 0L
        );
    }
}
