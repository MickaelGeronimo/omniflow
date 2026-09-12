package com.omniflow.infrastructure.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omniflow.application.port.in.AuditTriageUseCase;
import com.omniflow.application.port.out.AuditEventStorePort;
import com.omniflow.application.port.out.LedgerRepositoryPort;
import com.omniflow.infrastructure.ai.guardrails.FinancialPolicyGuardrails;
import com.omniflow.infrastructure.ai.tools.DlqPayloadInspectionTool;
import com.omniflow.infrastructure.ai.tools.LedgerInspectionTool;
import com.omniflow.infrastructure.ai.tools.MongoAuditInspectionTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentTriageTest {

    private LedgerInspectionTool ledgerTool;
    private MongoAuditInspectionTool mongoAuditTool;
    private DlqPayloadInspectionTool dlqTool;
    private FinancialPolicyGuardrails guardrails;
    private AuditEventStorePort auditStore;
    private IncidentTriageService triageService;

    @BeforeEach
    void setUp() {
        LedgerRepositoryPort ledgerRepository = Mockito.mock(LedgerRepositoryPort.class);
        ledgerTool = new LedgerInspectionTool(ledgerRepository);

        auditStore = Mockito.mock(AuditEventStorePort.class);
        when(auditStore.findByTransactionId(any())).thenReturn(Collections.emptyList());
        mongoAuditTool = new MongoAuditInspectionTool(auditStore);

        dlqTool = new DlqPayloadInspectionTool(new ObjectMapper());
        guardrails = new FinancialPolicyGuardrails(new BigDecimal("10000.00"), 0.85);

        triageService = new IncidentTriageService(
                ledgerTool, mongoAuditTool, dlqTool, guardrails, auditStore
        );
    }

    @Test
    @DisplayName("Should triage corrupted poison pill and recommend quarantine with high confidence")
    void shouldTriagePoisonPill() {
        String corruptedPayload = "{\"transactionId\":\"tx-999\",\"debtorAccount\":\"OMNI:0001:A1\",\"amount\":\"500.00\"}";
        AuditTriageUseCase.TriageRequest request = new AuditTriageUseCase.TriageRequest(
                "DLQ_POISON_PILL",
                "tx-999",
                corruptedPayload,
                "NullPointerException: missing creditorAccount"
        );

        AuditTriageUseCase.TriageVerdict verdict = triageService.triageIncident(request);

        assertThat(verdict.transactionId()).isEqualTo("tx-999");
        assertThat(verdict.recommendedAction()).isEqualTo("QUARANTINE_POISON_PILL");
        assertThat(verdict.confidenceScore()).isGreaterThanOrEqualTo(0.95);
        assertThat(verdict.toolsExecuted()).contains("inspectDlqPayload", "queryTransactionAuditTrail");

        verify(auditStore).recordAgentDecision(
                eq(verdict.incidentId()), eq("tx-999"), any(), eq("QUARANTINE_POISON_PILL"), any()
        );
    }

    @Test
    @DisplayName("Should enforce human-in-the-loop approval when transaction exceeds financial threshold")
    void shouldEnforceHumanApprovalForHighValueTransaction() {
        String highValuePayload = "{\"transactionId\":\"tx-whale\",\"debtorAccount\":\"OMNI:0001:A1\",\"creditorAccount\":\"OMNI:0001:A2\",\"amount\":\"75000.00\"}";
        AuditTriageUseCase.TriageRequest request = new AuditTriageUseCase.TriageRequest(
                "HIGH_VALUE_AUDIT",
                "tx-whale",
                highValuePayload,
                "insufficient funds in settlement buffer"
        );

        AuditTriageUseCase.TriageVerdict verdict = triageService.triageIncident(request);

        assertThat(verdict.requiresHumanApproval()).isTrue();
        assertThat(verdict.recommendedAction()).isEqualTo("MANUAL_REVERSAL_REQUIRED");
    }
}