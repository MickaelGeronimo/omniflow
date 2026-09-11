package com.omniflow.infrastructure.adapter.in.web;

import com.omniflow.application.port.in.AuditTriageUseCase;
import com.omniflow.application.port.in.ReconcileLedgerUseCase;
import com.omniflow.infrastructure.adapter.in.web.dto.AuditTriageRequestDto;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/v1")
public class AuditAgentController {

    private final AuditTriageUseCase auditTriageUseCase;
    private final ReconcileLedgerUseCase reconcileLedgerUseCase;

    public AuditAgentController(AuditTriageUseCase auditTriageUseCase, ReconcileLedgerUseCase reconcileLedgerUseCase) {
        this.auditTriageUseCase = auditTriageUseCase;
        this.reconcileLedgerUseCase = reconcileLedgerUseCase;
    }

    @PostMapping("/ai/triage")
    public ResponseEntity<AuditTriageUseCase.TriageVerdict> triageIncident(@Valid @RequestBody AuditTriageRequestDto request) {
        AuditTriageUseCase.TriageRequest triageRequest = new AuditTriageUseCase.TriageRequest(
                request.triggerType(),
                request.transactionId(),
                request.payload(),
                request.errorMessage()
        );

        AuditTriageUseCase.TriageVerdict verdict = auditTriageUseCase.triageIncident(triageRequest);
        return ResponseEntity.ok(verdict);
    }

    @PostMapping("/reconciliation/run")
    public ResponseEntity<ReconcileLedgerUseCase.ReconciliationResult> runReconciliation(
            @RequestParam(value = "date", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate targetDate = (date != null) ? date : LocalDate.now();
        ReconcileLedgerUseCase.ReconciliationResult result = reconcileLedgerUseCase.runNightlyReconciliation(targetDate);
        return ResponseEntity.ok(result);
    }
}
