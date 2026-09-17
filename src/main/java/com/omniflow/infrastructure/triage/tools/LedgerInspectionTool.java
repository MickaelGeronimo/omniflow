package com.omniflow.infrastructure.triage.tools;

import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.domain.ledger.LedgerAccount;
import com.omniflow.domain.model.AccountId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Component
public class LedgerInspectionTool {

    private final LedgerRepositoryPort ledgerRepository;

    public LedgerInspectionTool(LedgerRepositoryPort ledgerRepository) {
        this.ledgerRepository = ledgerRepository;
    }

    @Tool(description = "Inspect an account in the double-entry general ledger to check balance, currency and type")
    public Map<String, Object> inspectLedgerAccount(String accountId) {
        Optional<LedgerAccount> opt = ledgerRepository.findAccountById(AccountId.parse(accountId));
        if (opt.isEmpty()) {
            return Map.of("found", false, "error", "Account not found in ledger: " + accountId);
        }

        LedgerAccount acc = opt.get();
        return Map.of(
                "found", true,
                "accountId", acc.getId().toString(),
                "name", acc.getName(),
                "type", acc.getType().name(),
                "currency", acc.getCurrency(),
                "balance", acc.getBalance().amount().toPlainString(),
                "version", acc.getVersion()
        );
    }
}
