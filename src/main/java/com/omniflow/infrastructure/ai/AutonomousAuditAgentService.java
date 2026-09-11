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

@Service
public class AutonomousAuditAgentService implements AuditTriageUseCase {

    private static final Logger log = LoggerFactory.getLogger(AutonomousAuditAgentService.class);

    private final LedgerInspectionTool ledgerTool;
    private final MongoAuditInspectionTool mongoAuditTool;
    private final DlqPayloadInspectionTool dlqTool;
    private final FinancialPolicyGuardrails guardrails;
    private final AuditEventStorePort auditStore;

    public AutonomousAuditAgentService(
            LedgerInspectionTool ledgerTool,
            MongoAuditInspectionTool mongoAuditTool,
            DlqPayloadInspectionTool dlqTool,
            FinancialPolicyGuardrails guardrails,
            AuditEventStorePort auditStore) {
        this.ledgerTool = ledgerTool;
        this.mongoAuditTool = mongoAuditTool;
        this.dlqTool = dlqTool;
        this.guardrails = guardrails;
        this.auditStore = auditStore;
    }

    @Override
    public TriageVerdict triageIncident(TriageRequest request) {
        String incidentId = "INCIDENT-" + UUID.randomUUID().toString().substring(0, 8);
        log.info("[AI-AGENT] Initiating autonomous incident triage [{}] for tx [{}] triggered by [{}]",
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

        // Step 4: Autonomous reasoning & root-cause determination
        String suspectedCause = (String) dlqInspection.getOrDefault("suspectedCause", "UNKNOWN_CAUSE");
        String rootCauseAnalysis;
        String recommendedAction;
        double confidenceScore;

        if ("TRANSIENT_INSUFFICIENT_FUNDS_DURING_SETTLEMENT".equals(suspectedCause)) {
            rootCauseAnalysis = "Customer account balance depleted concurrently between funds hold and final settlement window.";
            recommendedAction = "MANUAL_REVERSAL_REQUIRED";
            confidenceScore = 0.94;
        } else if ("CORRUPTED_PAYLOAD_MISSING_CREDITOR".equals(suspectedCause)) {
            rootCauseAnalysis = "Message schema corruption: creditor account field missing from SQS payload.";
            recommendedAction = "QUARANTINE_POISON_PILL";
            confidenceScore = 0.98;
        } else {
            rootCauseAnalysis = "Transient network error or timeout during async SQS delivery.";
            recommendedAction = "REPLAY_TRANSACTION_FROM_OUTBOX";
            confidenceScore = 0.88;
        }

        // Step 5: Enforce Safety Guardrails & Human-in-the-Loop policy
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

        log.info("[AI-AGENT] Completed triage [{}]. Requires Human: {}, Confidence: {}, Action: {}",
                incidentId, requiresHuman, confidenceScore, recommendedAction);

        return verdict;
    }
}
