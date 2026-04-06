# Release Status

> **Status note (2026-03-21):** This file is a historical release log for earlier
> launcher/UI acceptance work. The current backend/runtime/deployment source of
> truth is [BACKEND_STATUS.md](BACKEND_STATUS.md). The current HTTPS/TLS source
> of truth is [HTTPS_STANDARD.md](HTTPS_STANDARD.md).

Current status: **ACCEPTED**

> **⚠️ Note (2026-02-28):** This document contains historical git diffs referencing
> `JwtFilter.java` and `JwtAuthenticationFilter.java` — both were merged into
> `JwtAuthFilter.java` in Phase 3 (PR3.2). The diffs below reflect pre-Phase-3 state.
> Current security architecture: see `docs/security-jwt.md`.

## Gates

- `scripts/verify-ai.sh` must exit `0`.
- Acceptance must run via internal port-forward (UI button or `scripts/product/jarvis-run-acceptance.sh`).
- Backend + DB must be ready: `kubectl -n jarvis get pods` shows all core pods `READY 1/1` (including `postgres`).
- UI smoke check: launcher + desktop client open and connect to API.

## Deployment assumptions

- k3s + ingress-nginx (class `nginx`).
- Tool endpoints are internal-only.
- Public ingress must return `401`/`403` for `/api/v1/tools/**` without JWT.

## Acceptance checklist

Primary path: **Launcher → Run Acceptance**.

CLI fallback:
```bash
scripts/verify-ai.sh
scripts/product/jarvis-run-acceptance.sh
```

### UI smoke check

Open **Jarvis 2.0** from the app menu, click **Start All**, then **Start Desktop**.

Expected:
- Launcher opens.
- Start All brings backend to READY.
- Desktop client opens and can log in to API gateway.

## Flyway repair (life-tracker)

If Flyway validation fails due to checksum mismatch (V1–V3), run a repair job
and restart `life-tracker` afterward. See:

- `docs/ops/flyway-repair-lifetracker-job.yaml`

## Diagnostics 2026-01-20

### System info
```
uname -a
Linux denis-pc 6.14.0-37-generic #37~24.04.1-Ubuntu SMP PREEMPT_DYNAMIC Thu Nov 20 10:25:38 UTC 2 x86_64 x86_64 x86_64 GNU/Linux

lsb_release -a
Distributor ID:	Ubuntu
Description:	Ubuntu 24.04.3 LTS
Release:	24.04
Codename:	noble

nvidia-smi
Tue Jan 20 23:35:09 2026
+-----------------------------------------------------------------------------------------+
| NVIDIA-SMI 580.95.05              Driver Version: 580.95.05      CUDA Version: 13.0     |
+-----------------------------------------+------------------------+----------------------+
| GPU  Name                 Persistence-M | Bus-Id          Disp.A | Volatile Uncorr. ECC |
| Fan  Temp   Perf          Pwr:Usage/Cap |           Memory-Usage | GPU-Util  Compute M. |
|                                         |                        |               MIG M. |
|=========================================+========================+======================|
|   0  NVIDIA GeForce RTX 5070        Off |   00000000:01:00.0  On |                  N/A |
|  0%   50C    P0             30W /  250W |     632MiB /  12227MiB |      4%      Default |
|                                         |                        |                  N/A |
+-----------------------------------------+------------------------+----------------------+

+-----------------------------------------------------------------------------------------+
| Processes:                                                                              |
|  GPU   GI   CI              PID   Type   Process name                        GPU Memory |
|        ID   ID                                                               Usage      |
|=========================================================================================|
|    0   N/A  N/A            6551      G   /usr/lib/xorg/Xorg                      277MiB |
|    0   N/A  N/A            6876      G   /usr/bin/gnome-shell                     29MiB |
|    0   N/A  N/A            7423      G   ...exec/xdg-desktop-portal-gnome         21MiB |
|    0   N/A  N/A            8870      G   ...3261198bd491daaecd3d3c88c0420        116MiB |
|    0   N/A  N/A           45349      G   ...setup.6.0.2/Telegram/Telegram          5MiB |
|    0   N/A  N/A         3491261      G   /proc/self/exe                           78MiB |
+-----------------------------------------------------------------------------------------+

docker --version
Docker version 28.2.2, build 28.2.2-0ubuntu1~24.04.1

java -version
openjdk version "21.0.9" 2025-10-21
OpenJDK Runtime Environment (build 21.0.9+10-Ubuntu-124.04)
OpenJDK 64-Bit Server VM (build 21.0.9+10-Ubuntu-124.04, mixed mode, sharing)

mvn -version
Apache Maven 3.8.7
Maven home: /usr/share/maven
Java version: 21.0.9, vendor: Ubuntu, runtime: /usr/lib/jvm/java-21-openjdk-amd64
Default locale: en_US, platform encoding: UTF-8
OS name: "linux", version: "6.14.0-37-generic", arch: "amd64", family: "unix"

gradle -version
openjdk version "21.0.9" 2025-10-21
OpenJDK Runtime Environment (build 21.0.9+10-Ubuntu-124.04)
OpenJDK 64-Bit Server VM (build 21.0.9+10-Ubuntu-124.04, mixed mode, sharing)

------------------------------------------------------------
Gradle 4.4.1
------------------------------------------------------------

Build time:   2012-12-21 00:00:00 UTC
Revision:     none

Groovy:       2.4.21
Ant:          Apache Ant(TM) version 1.10.14 compiled on September 25 2023
JVM:          21.0.9 (Ubuntu 21.0.9+10-Ubuntu-124.04)
OS:           Linux 6.14.0-37-generic amd64
```

### Git status
```
git status -sb
warning: could not open directory 'data/postgres-backup-pre16_2/': Permission denied
warning: could not open directory 'data/postgres/': Permission denied
warning: could not open directory 'data/postgres-backup-pre16_3/': Permission denied
warning: could not open directory 'data/postgres-backup-pre16/': Permission denied
## master...origin/master [ahead 2]
 D CLEANUP_PLAN.md
 D CODE_AUDIT.md
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/config/CorsConfig.java
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/controller/PcControlInternalController.java
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/controller/ToolProxyController.java
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/filter/JwtFilter.java
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/security/JwtAuthenticationFilter.java
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/security/SecurityConfig.java
 M apps/api-gateway/src/main/java/org/jarvis/apigateway/websocket/WebSocketConfig.java
 M apps/llm-service/src/main/java/org/jarvis/llm/config/WebSocketConfig.java
 M apps/pc-control/src/main/java/org/jarvis/pccontrol/service/impl/LinuxSystemControlService.java
 M apps/voice-gateway/src/main/java/org/jarvis/voicegateway/config/WebSocketConfig.java
 M docker/embedding-service/app/config.py
 M docker/embedding-service/app/main.py
 M docker/llm-server/app/config.py
 M docker/llm-server/app/main.py
```

### Git log (last commit)
```
git log -1 --stat
commit aab4064f8c333dca1e1b1929b8c3c880336d3530
Author: gajdukiewicz00 <gajdukiewicz99@gmail.com>
Date:   Tue Jan 20 20:34:55 2026 +0100

    add

 .github/workflows/desktop-entry-guard.yml          |   23 +
 .gitignore                                         |    8 +
 ARCHITECTURE.md                                    |   14 +-
 CLEANUP_PLAN.md                                    |   22 +
 CODE_AUDIT.md                                      |   15 +
 DEPLOYMENT_INSTRUCTIONS.md                         |  169 ++-
 LAUNCHER_README.md                                 |  223 +---
 Makefile                                           |  174 +--
 PROJECT_STRUCTURE.md                               |   18 +-
 README.md                                          |  157 +--
 RUNBOOK_LLM.md                                     |  141 +--
 SUMMARY.md                                         |   52 +
 ai-automation-examples.md                          |   13 +
 ai-calendar-flows.md                               |   19 +
 ai-finance-flows.md                                |   17 +
 ai-todo-flows.md                                   |   19 +
 .../config/AnalyticsDevSecurityConfig.java         |   15 -
 .../analytics/controller/AnalyticsController.java  |    8 +-
 .../java/org/jarvis/analytics/dto/ExpenseDTO.java  |    5 +-
 .../jarvis/analytics/service/AnalyticsService.java |   31 +-
 .../src/main/resources/application-dev.yml         |   12 -
 .../src/main/resources/application-docker.yml      |   21 -
 .../src/main/resources/application-k8s.yml         |   80 --
 .../src/main/resources/application.yml             |    2 +-
 .../apigateway/client/LifeTrackerClient.java       |   44 +
 .../apigateway/client/MemoryServiceClient.java     |   18 +
 .../jarvis/apigateway/client/PlannerClient.java    |   37 +
 .../apigateway/controller/ToolProxyController.java |  132 +++
 .../org/jarvis/apigateway/filter/JwtFilter.java    |   23 +-
 .../apigateway/security/DevSecurityConfig.java     |   39 -
 .../jarvis/apigateway/security/SecurityConfig.java |   18 +-
 .../src/main/resources/application-dev.yaml        |   12 -
 .../src/main/resources/application-docker.yml      |   28 -
 .../src/main/resources/application-k8s.yml         |  165 ---
 .../src/main/resources/application.yaml            |    5 +-
 .../kotlin/org/jarvis/desktop/config/AppConfig.kt  |    9 +-
 .../jarvis/common/config/JarvisServicePorts.java   |    5 +-
 .../jarvis/common/security/DevSecurityConfig.java  |   48 -
 apps/launcher-javafx/pom.xml                       |    8 -
 .../org/jarvis/launcher/HealthCheckService.kt      |  101 +-
 .../main/kotlin/org/jarvis/launcher/JarvisPaths.kt |   92 +-
 .../org/jarvis/launcher/LauncherApplication.kt     |  904 +++++++++++++-
 .../kotlin/org/jarvis/launcher/LauncherConfig.kt   |   35 +
 .../main/kotlin/org/jarvis/launcher/LogViewer.kt   |    5 +-
 .../kotlin/org/jarvis/launcher/ProcessRunner.kt    |   64 +-
 .../kotlin/org/jarvis/launcher/SecurityUtils.kt    |    2 +
 .../launcher-javafx/src/main/resources/logback.xml |    2 +
 apps/life-tracker/pom.xml                          |    4 +
 .../jarvis/lifetracker/LifeTrackerApplication.java |    2 +
 .../config/LifeTrackerDevSecurityConfig.java       |   15 -
 .../lifetracker/controller/CalendarController.java |   54 +-
 .../lifetracker/controller/ExpenseController.java  |   43 -
 .../lifetracker/controller/FinanceController.java  |  168 +++
 .../controller/TimeRecordController.java           |  106 +-
 .../controller/ToolCalendarController.java         |  108 ++
 .../controller/ToolExceptionHandler.java           |   72 ++
 .../controller/ToolFinanceController.java          |   67 ++
 .../lifetracker/domain/ActiveTimeRecord.java       |   36 +
 .../java/org/jarvis/lifetracker/domain/Budget.java |   49 +
 .../jarvis/lifetracker/domain/BudgetPeriod.java    |    7 +
 .../jarvis/lifetracker/domain/CalendarEvent.java   |   45 +-
 .../org/jarvis/lifetracker/domain/EntrySource.java |    7 +
 .../org/jarvis/lifetracker/domain/Expense.java     |   47 +-
 .../jarvis/lifetracker/domain/FinancialGoal.java   |   46 +
 .../org/jarvis/lifetracker/domain/GoalStatus.java  |    7 +
 .../lifetracker/domain/RecurringInterval.java      |    7 +
 .../lifetracker/domain/RecurringTransaction.java   |   59 +
 .../org/jarvis/lifetracker/domain/TimeRecord.java  |    4 +
 .../jarvis/lifetracker/domain/TransactionType.java |    6 +
 .../java/org/jarvis/lifetracker/dto/BudgetDTO.java |   26 +
 .../jarvis/lifetracker/dto/BudgetStatusDTO.java    |   15 +
 .../org/jarvis/lifetracker/dto/BudgetUsageDTO.java |   18 +
 .../lifetracker/dto/CalendarConflictDTO.java       |   26 +
 .../jarvis/lifetracker/dto/CalendarEventDTO.java   |   10 +
 .../org/jarvis/lifetracker/dto/ExpenseDTO.java     |   13 +-
 .../jarvis/lifetracker/dto/FinanceSummaryDTO.java  |   19 +
 .../jarvis/lifetracker/dto/FinancialGoalDTO.java   |   25 +
 .../org/jarvis/lifetracker/dto/FreeSlotDTO.java    |   19 +
 .../lifetracker/dto/RecurringTransactionDTO.java   |   30 +
 .../lifetracker/dto/SpendingAnalysisDTO.java       |   17 +
 .../jarvis/lifetracker/dto/SpendingBucketDTO.java  |   16 +
 .../repository/ActiveTimeRecordRepository.java     |   10 +
 .../lifetracker/repository/BudgetRepository.java   |   10 +
 .../repository/CalendarEventRepository.java        |   19 +
 .../lifetracker/repository/ExpenseRepository.java  |   11 +
 .../repository/FinancialGoalRepository.java        |   10 +
 .../repository/RecurringTransactionRepository.java |   10 +
 .../repository/TimeRecordRepository.java           |    6 +
 .../service/CalendarConflictException.java         |   18 +
 .../lifetracker/service/CalendarService.java       |  297 +++++
 .../org/jarvis/lifetracker/service/DTOMapper.java  |   19 +-
 .../jarvis/lifetracker/service/FinanceService.java |  232 ++++
 .../tooling/IdempotencyConflictException.java      |    7 +
 .../jarvis/lifetracker/tooling/ToolRequest.java    |   40 +
 .../lifetracker/tooling/ToolRequestCleanup.java    |   30 +
 .../lifetracker/tooling/ToolRequestRepository.java |   12 +
 .../lifetracker/tooling/ToolRequestService.java    |   90 ++
 .../lifetracker/tooling/ToolUserIdFilter.java      |   47 +
 .../tooling/dto/AnalyzeSpendingToolRequest.java    |   20 +
 .../tooling/dto/BudgetStatusToolRequest.java       |   13 +
 .../tooling/dto/CreateEventToolRequest.java        |   38 +
 .../tooling/dto/FindFreeSlotToolRequest.java       |   24 +
 .../tooling/dto/ListEventsToolRequest.java         |   11 +
 .../tooling/dto/ListTransactionsToolRequest.java   |   14 +
 .../tooling/dto/MoveEventToolRequest.java          |   19 +
 .../lifetracker/tooling/dto/StrictToolRequest.java |   13 +
 .../tooling/dto/SummarizeMonthToolRequest.java     |   13 +
 .../src/main/resources/application-dev.yaml        |    7 -
 .../src/main/resources/application-docker.yml      |   46 -
 .../src/main/resources/application-k8s.yml         |   82 --
 .../src/main/resources/application.yaml            |    6 +-
 .../db/migration/V4__finance_calendar_tooling.sql  |   78 ++
 .../db/migration/V5__time_record_active_state.sql  |   15 +
 .../resources/db/migration/V6__harden_user_id.sql  |   31 +
 .../lifetracker/LifeTrackerIntegrationTest.java    |   28 +-
 .../repository/ExpenseRepositoryTest.java          |   13 +-
 apps/llm-service/pom.xml                           |    4 +
 .../org/jarvis/llm/config/DevSecurityConfig.java   |   39 -
 .../llm/controller/LlmOrchestratorController.java  |   38 +
 .../llm/orchestrator/LlmOrchestratorService.java   |  184 +++
 .../llm/orchestrator/SystemPromptProvider.java     |   44 +
 .../llm/orchestrator/ToolSchemaRegistry.java       |   44 +
 .../jarvis/llm/orchestrator/dto/ModelToolCall.java |   19 +
 .../jarvis/llm/orchestrator/dto/ModelToolPlan.java |   20 +
 .../llm/orchestrator/dto/OrchestrationRequest.java |   30 +
 .../orchestrator/dto/OrchestrationResponse.java    |   17 +
 .../jarvis/llm/orchestrator/dto/ToolCallDto.java   |   17 +
 .../src/main/resources/application-dev.yml         |   26 -
 .../src/main/resources/application-docker.yml      |   49 -
 .../llm-service/src/main/resources/application.yml |   12 +-
 .../resources/prompts/llm-orchestrator-system.txt  |   32 +
 .../src/main/resources/tools/registry.json         |  180 +++
 .../memory/controller/ToolExceptionHandler.java    |   60 +
 .../memory/controller/ToolMemoryController.java    |   40 +
 .../jarvis/memory/tooling/ToolUserIdFilter.java    |   47 +
 .../tooling/dto/SearchMemoryToolRequest.java       |   18 +
 .../memory/tooling/dto/StrictToolRequest.java      |   13 +
 .../src/main/resources/application-docker.yml      |   29 -
 .../src/main/resources/application.yml             |    9 +-
 .../.gradle/4.4.1/fileHashes/fileHashes.lock       |  Bin 17 -> 0 bytes
 .../.gradle/4.4.1/taskHistory/taskHistory.lock     |  Bin 17 -> 0 bytes
 .../.gradle/8.9/checksums/checksums.lock           |  Bin 0 -> 17 bytes
 .../checksums/md5-checksums.bin}                   |  Bin 18969 -> 18647 bytes
 .../.gradle/8.9/checksums/sha1-checksums.bin       |  Bin 0 -> 18767 bytes
 .../8.9/dependencies-accessors/gc.properties       |    0
 .../8.9/executionHistory/executionHistory.lock     |  Bin 0 -> 17 bytes
 .../{4.4.1 => 8.9}/fileChanges/last-build.bin      |  Bin
 .../.gradle/8.9/fileHashes/fileHashes.lock         |  Bin 0 -> 17 bytes
 apps/mobile-client/.gradle/8.9/gc.properties       |    0
 .../buildOutputCleanup/buildOutputCleanup.lock     |  Bin 17 -> 17 bytes
 .../.gradle/buildOutputCleanup/cache.properties    |    4 +-
 apps/mobile-client/.gradle/vcs-1/gc.properties     |    0
 .../src/main/resources/application-docker.yml      |    9 -
 .../{application-k8s.yml => application.yml}       |   10 +-
 .../orchestrator/config/SecurityConfig.java}       |   14 +-
 .../src/main/resources/application-docker.yml      |   19 -
 .../src/main/resources/application.yml             |   11 +-
 .../config/PcControlDevSecurityConfig.java         |   20 -
 .../service/impl/LinuxSystemControlService.java    |    6 +-
 .../service/impl/StubSystemControlService.java     |    6 +-
 .../src/main/resources/application-docker.yml      |   16 -
 .../src/main/resources/application-security.yml    |   18 -
 .../{application-k8s.yml => application.yml}       |   26 +-
 apps/planner-service/pom.xml                       |    4 +
 .../jarvis/planner/config/DevSecurityConfig.java   |   39 -
 .../org/jarvis/planner/config/SecurityConfig.java  |    2 +-
 .../planner/controller/ToolExceptionHandler.java   |   70 ++
 .../planner/controller/ToolTodoController.java     |  167 +++
 .../main/java/org/jarvis/planner/dto/TaskDto.java  |    9 +-
 .../main/java/org/jarvis/planner/model/Task.java   |   23 +-
 .../java/org/jarvis/planner/model/TaskSource.java  |    6 +
 .../jarvis/planner/model/TaskTagsConverter.java    |   35 +
 .../jarvis/planner/repository/TaskRepository.java  |    6 +-
 .../jarvis/planner/service/DailyPlanGenerator.java |    3 +-
 .../planner/service/LlmEnhancementService.java     |    4 +-
 .../jarvis/planner/service/ScheduleOptimizer.java  |    4 +-
 .../org/jarvis/planner/service/TaskService.java    |   56 +-
 .../tooling/IdempotencyConflictException.java      |    7 +
 .../org/jarvis/planner/tooling/ToolRequest.java    |   40 +
 .../jarvis/planner/tooling/ToolRequestCleanup.java |   30 +
 .../planner/tooling/ToolRequestRepository.java     |   12 +
 .../jarvis/planner/tooling/ToolRequestService.java |   90 ++
 .../jarvis/planner/tooling/ToolUserIdFilter.java   |   47 +
 .../planner/tooling/dto/CompleteTodoRequest.java   |   11 +
 .../planner/tooling/dto/CreateTodoRequest.java     |   26 +
 .../planner/tooling/dto/ListTodosRequest.java      |   15 +
 .../planner/tooling/dto/StrictToolRequest.java     |   13 +
 .../planner/tooling/dto/UpdateTodoRequest.java     |   42 +
 .../src/main/resources/application-docker.yml      |   40 -
 .../src/main/resources/application-k8s.yml         |   91 --
 .../src/main/resources/application.yml             |   19 +-
 .../resources/db/migration/V4__todo_tooling.sql    |   19 +
 .../jarvis/security/config/DevSecurityConfig.java  |   39 -
 .../src/main/resources/application-dev.yml         |   13 -
 .../src/main/resources/application-docker.yml      |   17 -
 .../src/main/resources/application-k8s.yml         |   63 -
 .../config/SmartHomeDevSecurityConfig.java         |   15 -
 .../src/main/resources/application-docker.yml      |   17 -
 .../src/main/resources/application-k8s.yml         |   72 --
 .../src/main/resources/application-security.yml    |   16 -
 .../src/main/resources/application.yml             |   59 +
 .../src/main/resources/application-docker.yml      |   21 -
 .../src/main/resources/application-k8s.yml         |   61 -
 .../src/main/resources/application.yaml            |    6 +-
 .../migration/V4__add_user_habits_updated_at.sql   |   11 +
 .../config/VoiceGatewayDevSecurityConfig.java      |   15 -
 .../src/main/resources/application-dev.yaml        |    7 -
 .../src/main/resources/application-docker.yml      |   26 -
 .../src/main/resources/application-k8s.yml         |   77 --
 .../src/main/resources/application.yaml            |    5 +-
 architecture.md                                    |   52 +
 assets/icons/README.md                             |    2 +
 automation-engine.md                               |   26 +
 calendar-api.md                                    |   39 +
 calendar-tools.json                                |   61 +
 docker/certs/jarvis.crt                            |   22 -
 docker/embedding-service/Dockerfile                |    5 +-
 docker/llm-server/Dockerfile                       |   27 +-
 docker/llm-server/README.md                        |    4 +-
 docker/llm-server/app/backends/llamacpp_backend.py |    3 +-
 docker/llm-server/requirements.txt                 |    2 +-
 docs/HTTPS_STANDARD.md                             |   22 +-
 docs/K8S_LLM_MEMORY.md                             |  360 +-----
 docs/LLM_INTEGRATION.md                            |   51 +-
 docs/STATUS.md                                     |   42 +
 docs/VERIFY_MODES.md                               |  203 +---
 docs/api-error-handling.md                         |    5 +-
 docs/auth-flow.md                                  |   11 +-
 docs/jarvis-launch.md                              |  184 +--
 .../legacy/ARCHITECTURE_AUDIT_REPORT.md            |    5 +-
 docs/{ => legacy}/BACKLOG.md                       |    0
 .../legacy/CODE_REVIEW_REPORT.md                   |    1 +
 docs/{ => legacy}/DIAGNOSTIC_P4_P2.md              |    5 +-
 docs/{ => legacy}/HIKARI_CONFIG.md                 |    0
 docs/{ => legacy}/ITERATION_1.1_HOTFIX.md          |    9 +-
 docs/{ => legacy}/ITERATION_1.1_VERIFICATION.md    |    0
 .../ITERATION_1.1_VERIFICATION_SUMMARY.md          |    1 +
 docs/{ => legacy}/ITERATION_1.4_ACCEPTANCE.md      |    1 +
 docs/{ => legacy}/ITERATION_1.4_ACCEPTED.md        |    1 +
 docs/{ => legacy}/ITERATION_1.4_MASTER_PLAN.md     |    1 +
 .../ITERATION_1.4_QUESTIONS_ANSWERS.md             |    1 +
 .../ITERATION_1.4_STAGE1_COMPLETE.md               |    1 +
 .../ITERATION_1.4_STAGE1_STAGE2_SUMMARY.md         |    1 +
 .../ITERATION_1.4_STAGE1_VERIFICATION.md           |    1 +
 .../ITERATION_1.4_STAGE2_COMPLETE.md               |    1 +
 .../ITERATION_1.4_STAGE2_PRODUCT_PATHS.md          |    1 +
 .../ITERATION_1.4_STAGE3_ACCEPTANCE.md             |    1 +
 .../ITERATION_1.4_STAGE3_IMPLEMENTATION.md         |    1 +
 .../ITERATION_1.4_STAGE3_PLAN.md                   |    1 +
 .../ITERATION_1.4_STAGE4.md                        |    1 +
 .../ITERATION_1.4_STAGE5.md                        |    1 +
 .../ITERATION_1.4_STAGE6_ACCEPTANCE.md             |    1 +
 docs/{ => legacy}/ITERATION_1.5_ACCEPTANCE.md      |    1 +
 docs/{ => legacy}/ITERATION_1.5_ACCEPTANCE_RUN.md  |    0
 .../ITERATION_1.5_STAGE12_ACCEPTANCE_RUN.md        |    1 +
 .../{ => legacy}/ITERATION_1.5_STAGE12_ACCEPTED.md |    1 +
 .../ITERATION_1.5_STAGE8_ACCEPTANCE.md             |    1 +
 .../ITERATION_1.5_STAGE8_RUNTIME_HARDENING.md      |    1 +
 .../ITERATION_1.5_STAGE9_ACCEPTANCE.md             |    1 +
 .../ITERATION_1.5_STAGE9_RELEASE_DESIGN.md         |    1 +
 docs/{ => legacy}/ITERATION_1.5_TLS_HTTPS.md       |    1 +
 docs/{ => legacy}/ITERATION_1_CHANGES.md           |    9 +-
 docs/{ => legacy}/ITERATION_ENABLE_MEMORY.md       |    1 +
 .../ITERATION_ENABLE_MEMORY_ACCEPTANCE.md          |    1 +
 docs/{ => legacy}/ITERATION_LLM_GPU.md             |    4 +-
 docs/{ => legacy}/ITERATION_LLM_GPU_ACCEPTANCE.md  |    3 +-
 .../ITERATION_LLM_GPU_ACCEPTANCE_RUN.md            |    2 +-
 docs/{ => legacy}/ITERATION_LLM_GPU_STATUS.md      |    5 +-
 docs/{ => legacy}/ITERATION_PRODUCT_POLISH.md      |    0
 .../ITERATION_PRODUCT_POLISH_ACCEPTANCE.md         |    1 +
 docs/{ => legacy}/ITERATION_STAGE10_ZERO_RED.md    |    7 +-
 docs/legacy/ITERATION_STAGE16_ACCEPTANCE.md        |  186 +++
 docs/legacy/ITERATION_STAGE16_LOCAL_SECRETS.md     |  391 +++++++
 docs/{ => legacy}/KUBERNETES_MIGRATION.md          |    0
 docs/{ => legacy}/LLM_MEMORY_SETUP.md              |    3 +-
 docs/{ => legacy}/MASTER_PLAN_PRODUCTION.md        |   10 +-
 docs/legacy/README.md                              |    9 +
 docs/{ => legacy}/REFACTORING_REPORT.md            |    0
 docs/{ => legacy}/VERIFICATION_PACK_SUMMARY.md     |    0
 docs/{ => legacy}/architecture-overview.md         |    0
 .../__jarvis_audit__}/20251213_162559/00_basic.txt |    0
 .../20251213_162559/01_tree_top.txt                |    0
 .../20251213_162559/02_apps_list.txt               |    0
 .../20251213_162559/03_maven_modules.txt           |    0
 .../20251213_162559/04_dockerfiles.txt             |    0
 .../20251213_162559/05_k8s_files.txt               |    0
 .../__jarvis_audit__}/20251213_162559/06_llm.txt   |    0
 .../20251213_162559/07_ws_gateway.txt              |    0
 .../20251213_162559/08_orchestrator.txt            |    0
 .../20251213_162559/09_flyway_db.txt               |    0
 .../20251213_162559/10_legacy_hunt.txt             |    0
 .../20251213_162559/11_build_quick.txt             |    0
 .../20251213_162559/12_k8s_status.txt              |    0
 .../__jarvis_audit__}/20251213_162559/99_done.txt  |    0
 .../legacy/audit/__jarvis_audit_dump.tgz           |  Bin
 docs/{ => legacy}/config-matrix.md                 |    0
 docs/{ => legacy}/database-configs.md              |    0
 docs/{ => legacy}/dev-setup.md                     |    0
 docs/{ => legacy}/dev-workflow.md                  |    0
 docs/{ => legacy}/known-issues.md                  |    0
 docs/{ => legacy}/observability.md                 |    0
 docs/{ => legacy}/refactoring-report-2025-12-02.md |    0
 docs/{ => legacy}/smart-home-and-pc-control.md     |    0
 docs/{ => legacy}/stack-versions.md                |    0
 docs/{ => legacy}/voice-architecture.md            |    0
 docs/ops/flyway-repair-lifetracker-job.yaml        |   46 +
 docs/security-jwt.md                               |   16 +-
 docs/security/SECRETS_POLICY.md                    |  259 ++++
 example-rules.yaml                                 |   39 +
 finance-api.md                                     |   52 +
 finance-tools.json                                 |   51 +
 jarvis-launch.sh                                   | 1236 +++++++-------------
 jarvis-launcher.desktop                            |   35 -
 jarvis-logs.desktop                                |   10 -
 jarvis-logs.sh                                     |    2 +-
 jarvis-stop.sh                                     |   85 +-
 jarvis.desktop                                     |   24 -
 k8s/README.md                                      |  116 +-
 k8s/base/analytics-service/deployment.yaml         |    6 -
 k8s/base/api-gateway/deployment.yaml               |   32 +-
 k8s/base/configmap.yaml                            |  111 --
 k8s/base/embedding-service/deployment.yaml         |   78 --
 k8s/base/kustomization.yaml                        |    2 -
 k8s/base/life-tracker/deployment.yaml              |   11 +-
 k8s/base/llm-server/deployment.yaml                |  108 --
 k8s/base/llm-service/deployment.yaml               |   78 --
 k8s/base/memory-service/deployment.yaml            |   79 --
 k8s/base/nlp-service/deployment.yaml               |    4 -
 k8s/base/orchestrator/deployment.yaml              |   12 -
 k8s/base/pc-control/deployment.yaml                |   30 +-
 k8s/base/planner-service/deployment.yaml           |   15 +-
 k8s/base/postgres-init-configmap.yaml              |  146 ---
 k8s/base/postgres/statefulset.yaml                 |   31 +-
 k8s/base/secrets.yaml                              |   71 --
 k8s/base/security-service/deployment.yaml          |    4 -
 k8s/base/smart-home-service/deployment.yaml        |    9 +-
 k8s/base/tls-secret-generated.yaml                 |   18 -
 k8s/base/user-profile/deployment.yaml              |    2 -
 k8s/base/voice-gateway/deployment.yaml             |   12 +-
 k8s/ingress/ingress.yaml                           |   59 -
 k8s/legacy/dev/00-namespace.yaml                   |   14 -
 k8s/legacy/dev/01-configmap.yaml                   |  114 --
 k8s/legacy/dev/02-secrets-dev.yaml                 |   56 -
 k8s/legacy/dev/README.md                           |  128 --
 k8s/legacy/dev/ingress.yaml                        |   98 --
 k8s/legacy/dev/kafka/kafka.yaml                    |  130 --
 k8s/legacy/dev/kafka/topics-init-job.yaml          |   82 --
 k8s/legacy/dev/kafka/zookeeper.yaml                |  128 --
 k8s/legacy/dev/postgres/configmap.yaml             |   98 --
 k8s/legacy/dev/postgres/service.yaml               |   22 -
 k8s/legacy/dev/postgres/statefulset.yaml           |   98 --
 k8s/legacy/dev/rabbitmq/configmap.yaml             |  241 ----
 k8s/legacy/dev/rabbitmq/service.yaml               |   58 -
 k8s/legacy/dev/rabbitmq/statefulset.yaml           |  101 --
 k8s/legacy/dev/services/analytics-service.yaml     |  131 ---
 k8s/legacy/dev/services/api-gateway.yaml           |  188 ---
 k8s/legacy/dev/services/life-tracker.yaml          |  138 ---
 k8s/legacy/dev/services/llm-stack.yaml             |  273 -----
 k8s/legacy/dev/services/pc-control.yaml            |  155 ---
 k8s/legacy/dev/services/remaining-services.yaml    |  376 ------
 k8s/legacy/dev/services/security-service.yaml      |  116 --
 k8s/legacy/dev/services/smart-home-service.yaml    |  138 ---
 k8s/legacy/dev/services/voice-gateway.yaml         |  123 --
 k8s/namespaces/jarvis-namespaces.yaml              |   35 -
 k8s/overlays/dev/kustomization.yaml                |   77 --
 k8s/overlays/local/kustomization.yaml              |   54 -
 k8s/overlays/local/secrets-local.yaml              |   22 -
 .../{local => prod}/embedding-service.yaml         |    0
 k8s/overlays/prod/kustomization.yaml               |   49 +
 k8s/overlays/{local => prod}/llm-server.yaml       |    0
 k8s/overlays/{local => prod}/llm-service.yaml      |    5 +-
 k8s/overlays/{local => prod}/memory-service.yaml   |    2 -
 k8s/overlays/prod/postgres-init-scripts.yaml       |   36 +
 .../{local => prod}/postgres-pgvector.yaml         |   22 +-
 k8s/overlays/{local => prod}/pv-models.yaml        |    7 +-
 k8s/prod-like/kafka/kafka.yaml                     |  150 ---
 k8s/prod-like/kafka/topics-init-job.yaml           |   84 --
 k8s/prod-like/kafka/zookeeper.yaml                 |  146 ---
 k8s/prod-like/postgres/configmap.yaml              |  118 --
 k8s/prod-like/postgres/service.yaml                |   68 --
 k8s/prod-like/postgres/statefulset.yaml            |  161 ---
 k8s/prod-like/rabbitmq/configmap.yaml              |  252 ----
 k8s/prod-like/rabbitmq/service.yaml                |   59 -
 k8s/prod-like/rabbitmq/statefulset.yaml            |  132 ---
 k8s/secrets/db-credentials.yaml                    |   35 -
 k8s/secrets/jwt-secret.yaml                        |   22 -
 llm-orchestrator.md                                |   70 ++
 memory-tools.json                                  |   15 +
 scripts/acceptance-ai.sh                           |  133 +++
 scripts/build-images.sh                            |  130 +-
 scripts/ci/check-desktop-entry.sh                  |   49 +
 scripts/convert-to-gguf.sh                         |    8 +-
 jarvis-launch.sh                                   |  195 +--
 scripts/product/jarvis-generate-certs.sh           |   86 +-
 scripts/install-k8s-tools.sh                       |   31 +-
 jarvis-launch.sh (ENABLE_LLM/ENABLE_MEMORY flags) |  285 +----
 scripts/legacy/README.md                           |   79 --
 .../legacy/docker-compose/docker-compose-dev.yml   |   65 -
 .../legacy/docker-compose/docker-compose-full.yml  |  600 ----------
 scripts/legacy/docker-compose/docker-compose.yml   |  374 ------
 scripts/legacy/jarvis-k8s-launch.sh                |  309 -----
 scripts/legacy/jarvis-k8s-logs.sh                  |   81 --
 scripts/legacy/jarvis-k8s-stop.sh                  |   77 --
 scripts/legacy/jarvis-k8s.desktop                  |   25 -
 scripts/{ => legacy}/verify-iteration-1.1.sh       |    0
 scripts/{ => legacy}/verify-iteration-1.4.sh       |  146 ++-
 scripts/product/jarvis-build-release.sh            |  229 +++-
 scripts/product/jarvis-diagnostics.sh              |    9 +-
 scripts/product/jarvis-disk-cleanup.sh             |   82 ++
 scripts/product/jarvis-fix-docker-root.sh          |   39 +
 scripts/product/jarvis-fix-tls.sh                  |   52 +
 scripts/product/jarvis-generate-certs.sh           |  170 +++
 scripts/product/jarvis-gpu-setup.sh                |   59 +
 scripts/product/jarvis-install-tls.sh              |  111 +-
 scripts/product/jarvis-install.sh                  |  232 +++-
 scripts/product/jarvis-launcher.sh                 |  139 ++-
 scripts/product/jarvis-reset-namespace.sh          |   91 ++
 scripts/product/jarvis-run-acceptance.sh           |   54 +
 scripts/product/jarvis-secrets-apply.sh            |  270 +++++
 scripts/product/jarvis-setup-hosts.sh              |   47 +-
 scripts/product/jarvis-stop.sh                     |    9 +-
 scripts/product/jarvis-system-setup.sh             |   39 +
 jarvis-stop.sh                                     |   29 +-
 scripts/verify-ai.sh                               |  109 ++
 scripts/verify-prod.sh                             |   65 +
 system-prompt.md                                   |   36 +
 todo-api.md                                        |   41 +
 todo-tools.json                                    |   59 +
 tool-call-examples.md                              |   76 ++
 verify-ai.md                                       |   22 +
```

### Launcher scripts discovered
```
rg --files -g '*launch*.sh' -g '*launcher*.sh' -g 'jarvis-*.sh' scripts jarvis-launch.sh jarvis-logs.sh jarvis-stop.sh
jarvis-stop.sh
jarvis-logs.sh
jarvis-launch.sh
jarvis-launch.sh  # replacement for archived scripts/jarvis-k8s-up.sh
scripts/product/jarvis-system-setup.sh
scripts/product/jarvis-launcher.sh
scripts/product/jarvis-diagnostics.sh
scripts/product/jarvis-install.sh
scripts/product/jarvis-setup-hosts.sh
scripts/product/jarvis-secrets-apply.sh
scripts/product/jarvis-stop.sh
scripts/product/jarvis-run-acceptance.sh
scripts/product/jarvis-disk-cleanup.sh
scripts/product/jarvis-fix-docker-root.sh
scripts/product/jarvis-build-release.sh
scripts/product/jarvis-reset-namespace.sh
scripts/product/jarvis-generate-certs.sh
scripts/product/jarvis-gpu-setup.sh
scripts/product/jarvis-install-tls.sh
scripts/product/jarvis-fix-tls.sh
```

### Desktop entries with jarvis
```
rg -l -i "jarvis" ~/.local/share/applications /usr/share/applications /usr/local/share/applications
/home/kwaqa/.local/share/applications/jarvis-launcher.desktop
/home/kwaqa/.local/share/applications/jarvis.desktop
/home/kwaqa/.local/share/applications/SmartJARVIS.desktop
/home/kwaqa/.local/share/applications/mimeinfo.cache
/usr/share/applications/SmartJARVIS.desktop
```

### Launcher runs (logs in /tmp)
```
bash -x jarvis-launch.sh > /tmp/jarvis-launch.sh.log 2>&1
Result: command timed out after 120s
Last lines:
+ ensure_cluster
+ kubectl cluster-info

bash -x scripts/product/jarvis-launcher.sh > /tmp/jarvis-product-launcher.sh.log 2>&1
Result: exit 0
Last lines:
+ flock -n 9
+ exit 0
```
