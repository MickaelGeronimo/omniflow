package com.omniflow.infrastructure.ai;

import com.omniflow.application.port.in.AuditTriageUseCase;
import com.omniflow.application.port.out.AuditEventStorePort;
import com.omniflow.infrastructure.ai.guardrails.FinancialPolicyGuardrails;
import com.omniflow.infrastructure.ai.tools.DlqPayloadInspectionTool;
import com.omniflow.infrastructure.ai.tools.LedgerInspectionTool;
import com.omniflow.infrastructure.ai.tools.MongoAuditInspectionTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/**
 * Automated Incident Triage Service.
 * Orchestrates forensic evidence collection across SQS DLQ, MongoDB audit trail,
 * and ledger balances. Delegates diagnostic reasoning to an IncidentReasoningEngine
 * (deterministic in production; Spring AI ready as preview), with financial actions
 * strictly guarded by deterministic policy rules and Human-in-the-Loop (HITL) approval.
 */
@Service
public class IncidentTriageService implements AuditTriageUseCase {

    private static final Logger log = LoggerFactory.getLogger(IncidentTriageService.class);

    private final LedgerInspectionTool ledgerTool;
    private final MongoAuditInspectionTool mongoAuditTool;
    private final DlqPayloadInspectionTool dlqTool;
    private final FinancialPolicyGuardrails guardrails;
    private final AuditEventStorePort auditStore;
    private final IncidentReasoningEngine reasoningEngine;

    public IncidentTriageService(
            LedgerInspectionTool ledgerTool,
            MongoAuditInspectionTool mongoAuditTool,
            DlqPayloadInspectionTool dlqTool,
            FinancialPolicyGuardrails guardrails,
            AuditEventStorePort auditStore,
            IncidentReasoningEngine reasoningEngine) {
        this.ledgerTool = ledgerTool;
        this.mongoAuditTool = mongoAuditTool;
        this.dlqTool = dlqTool;
        this.guardrails = guardrails;
        this.auditStore = auditStore;
        this.reasoningEngine = (reasoningEngine != null) ? reasoningEngine : new DeterministicIncidentReasoningEngine();
    }

    public IncidentTriageService(
            LedgerInspectionTool ledgerTool,
            MongoAuditInspectionTool mongoAuditTool,
            DlqPayloadInspectionTool dlqTool,
            FinancialPolicyGuardrails guardrails,
            AuditEventStorePort auditStore) {
        this(ledgerTool, mongoAuditTool, dlqTool, guardrails, auditStore, new DeterministicIncidentReasoningEngine());
    }

    @Override
    public TriageVerdict triageIncident(TriageRequest request) {
        String incidentId = "INCIDENT-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[INCIDENT-TRIAGE] Initiating automated incident triage [{}] for tx [{}] triggered by [{}]",
                incidentId, request.transactionId(), request.triggerType());

        List<String> toolsExecuted = new ArrayList<>();
        Map<String, Object> evidence = new HashMap<>();

        // Step 1: Execute DlqPayloadInspectionTool
        toolsExecuted.add("inspectDlqPayload");
        Map<String, Object> dlqInspection = dlqTool.inspectDlqPayload(
                request.payload() != null ? request.payload() : "{}",
                request.errorMessage()
        );
        evidence.put("dlqAnalysis", dlqInspection);

        // Step 2: Execute MongoAuditInspectionTool
        toolsExecuted.add("queryTransactionAuditTrail");
        Map<String, Object> auditTrail = mongoAuditTool.queryTransactionAuditTrail(request.transactionId());
        evidence.put("auditTrail", auditTrail);

        // Step 3: Parse transaction amount for policy check
        BigDecimal txAmount = null;
        if (dlqInspection.containsKey("payload") && dlqInspection.get("payload") instanceof Map<?, ?> map) {
            Object amtObj = map.get("amount");
            if (amtObj != null) {
                try {
                    txAmount = new BigDecimal(amtObj.toString());
                } catch (Exception ignored) {}
            }
        }

        // Step 4: Diagnostic reasoning via configured IncidentReasoningEngine strategy
        IncidentReasoningEngine.ReasoningContext reasoningCtx = new IncidentReasoningEngine.ReasoningContext(
                incidentId,
                request.transactionId(),
                request.triggerType(),
                dlqInspection,
                auditTrail
        );
        IncidentReasoningEngine.ReasoningResult reasoningResult = reasoningEngine.reason(reasoningCtx);
        evidence.put("reasoningEngine", reasoningResult.engineModel());

        String rootCauseAnalysis = reasoningResult.rootCauseAnalysis();
        String recommendedAction = reasoningResult.recommendedAction();
        double confidenceScore = reasoningResult.confidenceScore();

        // Step 5: Enforce Safety Guardrails & Human-in-the-Loop policy (STRICTLY EXTERNAL TO AI ENGINE)
        boolean requiresHuman = guardrails.requiresHumanApproval(txAmount, confidenceScore, recommendedAction);

        TriageVerdict verdict = new TriageVerdict(
                incidentId,
                request.transactionId(),
                rootCauseAnalysis,
                recommendedAction,
                requiresHuman,
                confidenceScore,
                toolsExecuted,
                evidence
        );

        // Step 6: Persist complete reasoning trail and evidence to MongoDB
        auditStore.recordAgentDecision(
                incidentId,
                request.transactionId(),
                rootCauseAnalysis,
                recommendedAction,
                evidence
        );

        log.info("[INCIDENT-TRIAGE] Completed triage [{}]. Requires Human: {}, Confidence: {}, Action: {}, Engine: {}",
                incidentId, requiresHuman, confidenceScore, recommendedAction, reasoningResult.engineModel());

        return verdict;
    }
}