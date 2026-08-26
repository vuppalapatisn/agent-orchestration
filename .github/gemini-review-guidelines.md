# Review guidelines for the Gemini PR agent

These rules are injected into the reviewer's prompt on every run. They take
precedence over the agent's built-in defaults. Keep them short and specific —
long guideline files dilute the review.

## Project shape

Java 21 / Spring Boot 3 service that orchestrates AI agents. Layers:

- `controller/` — REST entry points, DTO in / DTO out only
- `orchestration/` — planner, parallel workers, reviewer agent
- `service/` — approval workflow and business rules
- `persistence/` — JPA entities, repositories, mappers
- `tools/` — Spring AI `@Tool` methods callable by the model
- `config/` — Spring configuration and security

## Always flag

- Secrets, API keys, endpoints, or passwords hardcoded in Java or YAML instead
  of read from environment variables.
- `permitAll()` or disabled CSRF on any state-changing endpoint in
  `config/SecurityConfig.java`.
- User input or tool output interpolated into a model prompt without
  sanitisation (prompt injection into the orchestrator).
- Mutable instance fields on singleton beans — orchestration beans are shared
  across request threads.
- Outbound calls (Azure OpenAI, MCP servers, HTTP tools) without a timeout, or
  `Future.get()` / `join()` without a timeout in `ParallelExecutionService`.
- `@Transactional` missing on multi-write approval state transitions, or applied
  to a private/self-invoked method where the proxy will not intercept it.
- Approval state changes that skip `ApprovalService` and touch the repository
  directly.
- New `@Tool` methods without a clear `description` — the model routes on it.
- Entities leaked out of the controller layer instead of being mapped to a DTO.
- N+1 query patterns in `ApprovalRepository` usage.
- Actuator or Swagger endpoints newly exposed without authentication.
- New branching logic in `orchestration/` or `service/` with no test added under
  `src/test/java`.

## Do not flag

- Formatting, import order, or line length — no formatter is enforced here.
- Lombok-vs-record style preferences.
- Missing Javadoc on self-explanatory methods.
- The mock implementations in `tools/OpsTools.java` and
  `tools/KnowledgeTools.java` — those are intentional placeholders.
- Anything in `deploy/` sample secret files (`secret-sample.yml`) — they exist
  to be replaced at deploy time.

## Conventions to enforce

- Constructor injection, never field `@Autowired`.
- DTOs are Java records and immutable.
- Custom exceptions surface through `controller/GlobalExceptionHandler.java`;
  controllers should not build error responses inline.
- Configuration keys live in `application.yml` under the `app.` prefix and are
  bound with `@ConfigurationProperties` or `@Value`, not read ad hoc.
