package com.example.agentorchestration.orchestration;

    import com.example.agentorchestration.dto.*;
    import com.example.agentorchestration.service.ApprovalService;
    import com.example.agentorchestration.tools.OpsTools;
    import com.example.agentorchestration.util.MessageParsers;
    import io.micrometer.observation.Observation;
    import io.micrometer.observation.ObservationRegistry;
    import org.springframework.stereotype.Service;

    import java.util.List;
    import java.util.stream.Collectors;

    @Service
    public class AgentOrchestratorService {

        private final PlannerAgent plannerAgent;
        private final ReviewerAgent reviewerAgent;
        private final WorkerAgents workerAgents;
        private final ParallelExecutionService parallelExecutionService;
        private final ApprovalService approvalService;
        private final OpsTools opsTools;
        private final ObservationRegistry observationRegistry;

        public AgentOrchestratorService(PlannerAgent plannerAgent,
                                        ReviewerAgent reviewerAgent,
                                        WorkerAgents workerAgents,
                                        ParallelExecutionService parallelExecutionService,
                                        ApprovalService approvalService,
                                        OpsTools opsTools,
                                        ObservationRegistry observationRegistry) {
            this.plannerAgent = plannerAgent;
            this.reviewerAgent = reviewerAgent;
            this.workerAgents = workerAgents;
            this.parallelExecutionService = parallelExecutionService;
            this.approvalService = approvalService;
            this.opsTools = opsTools;
            this.observationRegistry = observationRegistry;
        }

        public AgentResponse orchestrate(AgentRequest request) {
            return Observation.createNotStarted("agent.orchestration", observationRegistry)
                    .lowCardinalityKeyValue("entrypoint", "execute")
                    .observe(() -> doOrchestrate(request));
        }

        private AgentResponse doOrchestrate(AgentRequest request) {
            RoutePlan plan = plannerAgent.plan(request.message());
            return switch (plan.route()) {
                case OPS -> orchestrateOps(request, plan);
                case SUPPORT -> orchestrateSupport(request, plan);
                case GENERAL -> orchestrateGeneral(request, plan);
            };
        }

        private AgentResponse orchestrateOps(AgentRequest request, RoutePlan plan) {
            List<WorkerResult> results = parallelExecutionService.joinAll(
                    workerAgents.opsHealth(request.message()),
                    workerAgents.opsRunbook(request.message()),
                    workerAgents.opsMcp(request.message())
            );

            String draft = composeDraft(plan, results);

            if (plan.approvalRequired()) {
                String target = MessageParsers.extractServiceName(request.message());
                ApprovalRecord approval = approvalService.create(
                        plan.route(),
                        plan.approvalAction(),
                        target,
                        plan.reason(),
                        draft,
                        results
                );
                return new AgentResponse(
                        AgentStatus.APPROVAL_REQUIRED,
                        plan.route(),
                        plan.reason(),
                        results,
                        draft,
                        approval.id(),
                        approval.action() + " on " + approval.target()
                );
            }

            return new AgentResponse(
                    AgentStatus.COMPLETED,
                    plan.route(),
                    plan.reason(),
                    results,
                    reviewerAgent.review(draft),
                    null,
                    null
            );
        }

        private AgentResponse orchestrateSupport(AgentRequest request, RoutePlan plan) {
            List<WorkerResult> results = parallelExecutionService.joinAll(workerAgents.support(request.message()));
            String draft = composeDraft(plan, results);
            return new AgentResponse(
                    AgentStatus.COMPLETED,
                    plan.route(),
                    plan.reason(),
                    results,
                    reviewerAgent.review(draft),
                    null,
                    null
            );
        }

        private AgentResponse orchestrateGeneral(AgentRequest request, RoutePlan plan) {
            List<WorkerResult> results = parallelExecutionService.joinAll(workerAgents.general(request));
            String draft = composeDraft(plan, results);
            return new AgentResponse(
                    AgentStatus.COMPLETED,
                    plan.route(),
                    plan.reason(),
                    results,
                    reviewerAgent.review(draft),
                    null,
                    null
            );
        }

        public ApprovalRecord getApproval(String id) {
            return approvalService.get(id);
        }

        public AgentResponse decideApproval(String id, ApprovalDecisionRequest decisionRequest) {
            ApprovalRecord record = approvalService.decide(id, decisionRequest.approve(), decisionRequest.approver(), decisionRequest.comments());

            if (record.status() == ApprovalStatus.REJECTED) {
                String rejection = reviewerAgent.review(record.draft()
                        + "\n\nApproval decision: REJECTED by "
                        + decisionRequest.approver() + ". Comments: " + decisionRequest.comments());
                return new AgentResponse(AgentStatus.REJECTED, record.route(), record.plannerReason(), record.evidence(), rejection,
                        record.id(), record.action() + " on " + record.target());
            }

            String actionResult = executeApprovedAction(record);
            String completedDraft = record.draft()
                    + "\n\nApproved action result:\n" + actionResult
                    + "\nApproved by: " + decisionRequest.approver()
                    + "\nComments: " + (decisionRequest.comments() == null ? "N/A" : decisionRequest.comments());

            return new AgentResponse(AgentStatus.COMPLETED,
                    record.route(),
                    record.plannerReason(),
                    record.evidence(),
                    reviewerAgent.review(completedDraft),
                    record.id(),
                    record.action() + " on " + record.target());
        }

        private String executeApprovedAction(ApprovalRecord record) {
            if ("RESTART_SERVICE".equalsIgnoreCase(record.action())) {
                return opsTools.restartService(record.target());
            }
            return "Approved action acknowledged, but no concrete handler exists for action: " + record.action();
        }

        private String composeDraft(RoutePlan plan, List<WorkerResult> workerResults) {
            String evidence = workerResults.stream()
                    .map(w -> "## " + w.workerName() + "\n" + w.summary())
                    .collect(Collectors.joining("\n\n"));
            return "Route: " + plan.route() + "\n"
                    + "Planner reason: " + plan.reason() + "\n"
                    + "Confidence: " + plan.confidence() + "\n"
                    + "Approval required: " + plan.approvalRequired() + "\n"
                    + "Approval action: " + plan.approvalAction() + "\n\n"
                    + evidence;
        }
    }
