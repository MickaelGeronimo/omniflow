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
    private final IncidentReasoningEngine reasoningEngine;

    public AutonomousAuditAgentService(
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

    public AutonomousAuditAgentService(
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

        // Step 4: Autonomous reasoning via configured ReasoningEngine strategy
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

        log.info("[AI-AGENT] Completed triage [{}]. Requires Human: {}, Confidence: {}, Action: {}, Engine: {}",
                incidentId, requiresHuman, confidenceScore, recommendedAction, reasoningResult.engineModel());

        return verdict;
    }
}
