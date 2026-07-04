# Obsidian duplicate cleanup — audit

- **Generated:** 2026-06-07T18:14:01+02:00
- **DB:** jarvis_memory @ postgres-pgvector-0 (table memory_notes)
- **Scope:** source LIKE 'obsidian:%' AND status <> 'deleted' ONLY
- **Action planned:** reversible soft-delete (status='deleted', deleted_at=now()), keep newest per source
- **Total active obsidian rows BEFORE:** 144
- **duplicate_sources:** 13
- **rows_under_soft_delete:** 119
- **Expected active AFTER:** 25

## Per-source active counts (sources with >1 active row)
```
                                              source                                               | active_rows 
---------------------------------------------------------------------------------------------------+-------------
 obsidian:01_Daily/2026-06-05.md                                                                   |          13
 obsidian:03_Memory/20260605-233918-test-secret.md                                                 |          13
 obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md |          13
 obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    |          13
 obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  |          13
 obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          |          12
 obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                |          12
 obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      |          11
 obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             |          10
 obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             |           9
 obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             |           6
 obsidian:03_Memory/20260606-172825-Smoke-smoke-1780759705.md                                      |           4
 obsidian:03_Memory/20260606-173133-Smoke-smoke-1780759893.md                                      |           3
(13 rows)

```

## Kept newest memory_id per duplicate source (RETAINED)
```
                                              source                                               |               kept_newest                |          created_at           
---------------------------------------------------------------------------------------------------+------------------------------------------+-------------------------------
 obsidian:01_Daily/2026-06-05.md                                                                   | mem-447cb11d-235c-49b0-a8eb-3c03f0fd5aff | 2026-06-06 15:37:04.151363+00
 obsidian:03_Memory/20260605-233918-test-secret.md                                                 | mem-9d6a3b08-8129-43b6-bddd-29f86ef99a1e | 2026-06-06 15:37:04.235066+00
 obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | mem-2203028d-2385-4868-9617-129ce6ff0bba | 2026-06-06 15:37:04.302353+00
 obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | mem-78e39060-b4d0-414a-b0f5-854a303162a5 | 2026-06-06 15:37:04.315895+00
 obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | mem-cb164037-cbea-48e6-ae29-ab895c25a62e | 2026-06-06 15:37:04.391101+00
 obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | mem-ceb2f107-0564-46c9-a414-69de466b53e8 | 2026-06-06 15:37:04.251294+00
 obsidian:03_Memory/20260606-172825-Smoke-smoke-1780759705.md                                      | mem-53dda810-67f8-46c0-bffc-12fcc2b784a8 | 2026-06-06 15:37:04.343963+00
 obsidian:03_Memory/20260606-173133-Smoke-smoke-1780759893.md                                      | mem-c4f3c5be-ec67-4320-b832-301d74df50b5 | 2026-06-06 15:37:04.329781+00
 obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | mem-455ecac4-de18-4bb6-9420-5e70041c8519 | 2026-06-06 15:37:04.221079+00
 obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | mem-84589a68-6cd6-4b38-aee3-ab4c109837a7 | 2026-06-06 15:37:04.207027+00
 obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | mem-40a266e9-387b-4938-87dd-b2f29a89867c | 2026-06-06 15:37:04.165178+00
 obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | mem-c0ad2745-654e-45f0-a8ba-b8f28b00ca33 | 2026-06-06 15:37:04.179231+00
 obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             | mem-339d41c0-c3b2-4e46-969b-bd97d4504679 | 2026-06-06 15:37:04.193124+00
(13 rows)

```

## Full list of memory_id that WOULD be soft-deleted (119 rows)
```
                memory_id                 |                                              source                                               |          created_at           
------------------------------------------+---------------------------------------------------------------------------------------------------+-------------------------------
 mem-cd9fa3d6-3b69-4540-83a4-83836494c4e6 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:36:08.30374+00
 mem-e913550f-0c99-4b62-a4fa-14c54fa33907 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:31:33.446008+00
 mem-7c590b55-6b06-45bd-8a16-d067d8aa15b0 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:28:25.142589+00
 mem-c7b455a2-4afa-4e3c-9f44-b6597b0c4fb5 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:16:31.256312+00
 mem-b700a00f-b263-44ab-8168-01db288a8959 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:10:13.888098+00
 mem-d809e3af-b581-4336-8118-9edbcefcccac | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:08:05.018482+00
 mem-372c1d36-41d3-4d4a-8d48-1303722cbf12 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:05:13.049849+00
 mem-f833d9e2-0347-4700-b90c-693cf5f4b0ee | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:01:19.056084+00
 mem-494ace77-e99f-4fc1-ad8e-fa430f8eb630 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 15:01:10.768913+00
 mem-2c6dbdb1-ce8a-41e0-8236-72859ed368eb | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 13:37:15.362923+00
 mem-3c84d3f2-59a3-44ee-9487-c7103c47b7cd | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-06 13:36:18.371978+00
 mem-df62269b-9e4b-41a5-b9e7-3ff99eafe3b5 | obsidian:01_Daily/2026-06-05.md                                                                   | 2026-06-05 21:46:37.066598+00
 mem-abb1eb9d-a22a-4b8b-904d-047eba569f29 | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:36:08.430291+00
 mem-21ce50c4-1620-4234-b8b2-68d514841efe | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:31:33.81541+00
 mem-68e57786-0d1e-4e14-9471-571950db68cf | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:28:25.451944+00
 mem-64a45a12-e0eb-43f4-ac53-18fae794671d | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:16:31.744336+00
 mem-f8af56ee-0c27-4502-b21a-93305cc2b730 | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:10:15.038561+00
 mem-19cf73ae-b25a-4b23-b8c1-406d3ba08199 | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:08:05.15893+00
 mem-9ea07d5d-f63e-4c0e-93aa-212e584987c2 | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:05:13.521841+00
 mem-045a9988-4fcf-4279-9b1b-d198af107611 | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:01:19.139515+00
 mem-5350f78b-113d-419b-8033-9b30cfadf38c | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 15:01:10.840523+00
 mem-6a127519-6f6b-4a0b-96d3-316e06be15fa | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 13:37:15.53197+00
 mem-6523e9f2-ab06-4bf8-b001-db48a7c91ccc | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-06 13:36:18.422679+00
 mem-9a697dae-f4bb-4bd2-a573-f80abb1defa2 | obsidian:03_Memory/20260605-233918-test-secret.md                                                 | 2026-06-05 21:46:37.179308+00
 mem-b00bfcad-3ad7-40d3-8695-94b22ba7bed4 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:36:08.504242+00
 mem-47ac933e-82af-40ca-8fb4-f77ff54cf4be | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:31:33.910953+00
 mem-bdc46cde-e492-40b3-99d3-01e24eab9217 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:28:25.544129+00
 mem-5cfd0511-e040-4795-8cde-db90b7f69c18 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:16:31.841332+00
 mem-6a38b1a2-97a6-41bf-b103-7bdc0fc7a928 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:10:15.436321+00
 mem-7dce7031-fb1f-41e0-a450-9bff82178fb8 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:08:05.192586+00
 mem-cddc1882-f4bc-495b-b6e8-e26f700d3e6f | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:05:13.556352+00
 mem-110c41df-5678-4933-8e43-c028467517b4 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:01:19.165918+00
 mem-28c1a9ae-bed9-4be6-8908-715df7a57fbf | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 15:01:10.870732+00
 mem-023bd219-cb46-408b-a844-0a703828f0c5 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 13:37:15.564461+00
 mem-2313c0ba-da3f-4948-9219-27c18e908306 | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-06 13:36:18.440663+00
 mem-21a1c1ba-2536-494a-922a-750d33c900ba | obsidian:03_Memory/20260605-233918-Дмитрий-работает-над-движение-Джарвисом-на-RTX-5070-предпоч.md | 2026-06-05 21:46:37.199737+00
 mem-afd129e9-b934-4703-b7ce-aaaea4c32831 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:36:08.519267+00
 mem-c0e0e68e-c005-4e10-9f30-0e819f67189c | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:31:33.929505+00
 mem-36868c9c-3fb6-4114-93d0-6639ff7a6bc1 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:28:25.615408+00
 mem-faaa6b84-4170-4547-b9de-4c28f833be64 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:16:31.913828+00
 mem-456d1f4e-4bed-43e6-af86-281af687dd1c | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:10:15.695773+00
 mem-49ee9cee-9aa8-496a-93e5-3b821d3726f7 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:08:05.282228+00
 mem-fd5dc30c-ab5d-4238-bcd3-82dddaf57e2a | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:05:13.630866+00
 mem-9941aedf-56a5-427e-ac51-83b7b0931664 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:01:19.179291+00
 mem-afc27a6a-a6a8-4e13-bc1b-eeae33bf91c3 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 15:01:10.938657+00
 mem-dd3db54d-bf8b-4ea4-9f93-6391aa30de06 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 13:37:15.625452+00
 mem-66306cde-9ec3-4d9d-acc3-3f6ea5e7b9e8 | obsidian:03_Memory/20260605-234843-Финальная-проверка.md                                          | 2026-06-06 13:36:18.455625+00
 mem-3a2faa6c-f8ca-4cfd-977b-a199e2bde6db | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:36:08.61978+00
 mem-696630b0-ec6b-4b3a-8032-76a000e1cbc1 | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:31:34.113972+00
 mem-038f33ee-841c-4d36-a175-04ee473426ae | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:28:25.735067+00
 mem-61c414c7-dca4-4dd8-b2bf-3f2c264d4e1e | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:16:31.931199+00
 mem-43c347da-f4e1-4cff-8335-d18278699910 | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:10:15.714143+00
 mem-71174f35-cd16-4079-9854-9323877f8562 | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:08:05.487532+00
 mem-022997f5-85c6-40f7-956e-4f0c077e904e | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:05:13.648072+00
 mem-a328d87c-0c35-471e-8140-c50e708ec8cd | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:01:19.232129+00
 mem-9d3478bd-a77b-4156-bfbc-05c4abd02077 | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 15:01:10.954289+00
 mem-6ec817a2-c783-4a6c-aa90-a922485f42c9 | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 13:37:15.64142+00
 mem-6161ecaf-1c11-41f4-aba1-bd97be707f18 | obsidian:03_Memory/20260606-153618-Любимый-кофе.md                                                | 2026-06-06 13:36:18.484946+00
 mem-93b7159b-3098-4f59-84a6-cde0d41f1cc5 | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:36:08.489689+00
 mem-5e7f4dfa-8f42-455c-b409-b793b20d3ba0 | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:31:33.891454+00
 mem-bac9bf49-5ba3-4415-bdd2-7bc9d61a6e5b | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:28:25.52693+00
 mem-c636152c-c726-4f12-b65e-cefa1cc96836 | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:16:31.823249+00
 mem-18f69107-975b-44e4-94de-4882830455c3 | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:10:15.236685+00
 mem-137efee7-934c-46b9-9a57-c161d4ea842c | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:08:05.174503+00
 mem-76cd0e3a-abf3-401c-93c6-46f2f1d0c12f | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:05:13.539305+00
 mem-9995507a-43a2-4850-a1e2-eb075a4c6889 | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:01:19.152551+00
 mem-beba49ed-6dc8-4785-a145-6c9642a00fd5 | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 15:01:10.856226+00
 mem-af67e4b2-9dc3-4481-accc-b28fb1e162ff | obsidian:03_Memory/20260606-153715-Smoke-smoke-1780753035.md                                      | 2026-06-06 13:37:15.548281+00
 mem-5bfbe738-b68d-4e0f-bbc2-b425429dd37a | obsidian:03_Memory/20260606-172825-Smoke-smoke-1780759705.md                                      | 2026-06-06 15:36:08.603598+00
 mem-e4ee5de8-e1a2-48df-bb29-176cacf7ea7e | obsidian:03_Memory/20260606-172825-Smoke-smoke-1780759705.md                                      | 2026-06-06 15:31:34.092106+00
 mem-a5aaa1a7-ba5b-47ff-b61a-7578c0f08065 | obsidian:03_Memory/20260606-172825-Smoke-smoke-1780759705.md                                      | 2026-06-06 15:28:25.631916+00
 mem-1f08e24f-379e-4d8d-88e8-ae4467f89f50 | obsidian:03_Memory/20260606-173133-Smoke-smoke-1780759893.md                                      | 2026-06-06 15:36:08.53463+00
 mem-5f338d67-c110-4ced-8966-9b844527cc79 | obsidian:03_Memory/20260606-173133-Smoke-smoke-1780759893.md                                      | 2026-06-06 15:31:34.000089+00
 mem-176add28-b0f2-41b0-b086-097e9fd0f6c5 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:36:08.415144+00
 mem-16d80791-433c-4b43-a863-6ca1eac8abf9 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:31:33.797293+00
 mem-87a5474e-1726-49f4-8c78-f0c21ddb8328 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:28:25.435394+00
 mem-e85c2ef8-27a7-4cda-91fe-1e583f28a49c | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:16:31.72398+00
 mem-88ea4328-4dd9-4c99-a4d4-42186b26e88b | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:10:14.837566+00
 mem-a2d85bf8-282c-44ec-a684-bbef51e51896 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:08:05.142202+00
 mem-28630ddf-9df0-40d5-b869-0383fc288522 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:05:13.451481+00
 mem-01b555f0-c997-4a4e-b1a0-a38b6a25be0d | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:01:19.11308+00
 mem-65936bd2-a038-4dce-b952-d0e226b8d9ba | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 15:01:10.823739+00
 mem-00ecf553-3032-4466-a38a-f2662694dd60 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 13:37:15.456757+00
 mem-091e15ee-45c2-4a3d-b665-d6c76bda784b | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-06 13:36:18.407533+00
 mem-e5e0ee93-a95e-455e-b075-7bd7e20a1e93 | obsidian:04_Tasks/20260605-233918-Доделать-pairing-телефона.md                                    | 2026-06-05 21:46:37.110179+00
 mem-3bd87d93-83eb-42c9-80fb-ca5b37c113be | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:36:08.399567+00
 mem-dbe04bea-3133-4dd2-8a25-04a7c1f1e98a | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:31:33.720827+00
 mem-ccd34c14-00f5-4122-ac64-4a653b4496e1 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:28:25.416631+00
 mem-231c1416-e114-40ba-86b7-627e0106fc49 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:16:31.645879+00
 mem-d4e9ca33-7a9a-4335-ba5e-fe50d37f41d7 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:10:14.590911+00
 mem-c40e9b62-42e6-4694-aeeb-2d94b5c43642 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:08:05.127162+00
 mem-42acf811-4e7b-445d-add3-4373c95e2c58 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:05:13.431741+00
 mem-1e3afaec-a23f-4b66-8701-79b7178f51b3 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:01:19.098957+00
 mem-aa62cb3a-2db7-4f85-8ed4-e68980ef5571 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 15:01:10.807353+00
 mem-ff8318c6-8176-4c42-8bd0-0ed284e4befc | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 13:37:15.439466+00
 mem-c219380e-3b8f-4334-b7dc-5ca6bb9c6aa9 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-06 13:36:18.392018+00
 mem-19cd2336-e3e1-4435-a7b4-0fc99a6f0626 | obsidian:05_Ideas/20260605-233918-Утренний-briefing-голосом-focus-день-погода.md                  | 2026-06-05 21:46:37.091883+00
 mem-b9471dfa-82d0-4bc9-9a87-e45e53108d23 | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:36:08.318955+00
 mem-7736fbf5-70d1-4d80-80f9-5500ea690ea6 | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:31:33.601825+00
 mem-93678b45-0902-4c0a-b162-28357511e06b | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:28:25.240423+00
 mem-907b6217-f686-42cc-9069-50281050868b | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:16:31.514856+00
 mem-371f644f-bb5c-42f0-89de-cba2e165b53b | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:10:14.203516+00
 mem-f0df560e-7294-4d69-838e-5d2bd88c2bc0 | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:08:05.036626+00
 mem-dbc26b25-90dc-4141-a284-38d3994d722a | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:05:13.329308+00
 mem-3425aee6-04dc-4190-8c40-482af4d77e0e | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:01:19.069797+00
 mem-d0f1460d-3a90-4c40-8d91-3a1565342297 | obsidian:05_Ideas/20260606-170110-Морская-прогулка.md                                             | 2026-06-06 15:01:10.792218+00
 mem-50faf471-ea8e-4173-be11-68306eff27e9 | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:36:08.34719+00
 mem-f0a8aadf-c8cb-4686-9117-6027fa26ed8d | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:31:33.626167+00
 mem-2e63261a-3a34-4781-87c0-1b9e224296cd | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:28:25.326856+00
 mem-deba3f01-6e3b-4ac2-a651-9c919df98ea6 | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:16:31.545118+00
 mem-c8745900-375d-4ef2-81fb-35e9c7162e9c | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:10:14.305682+00
 mem-44a01167-2289-4f84-9171-c90b5f70e5c2 | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:08:05.059809+00
 mem-b98e1a2d-987f-425c-9b3f-2e64131ac36a | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:05:13.34928+00
 mem-62a934ef-7512-4ba7-a025-052ab2854e45 | obsidian:05_Ideas/20260606-170118-Морская-прогулка.md                                             | 2026-06-06 15:01:19.085662+00
 mem-3b54ed97-aedd-45b7-a9cb-16a65292addf | obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             | 2026-06-06 15:36:08.363464+00
 mem-ab890e8a-8f8b-45b8-af67-2e1cc722b2af | obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             | 2026-06-06 15:31:33.702+00
 mem-9527d275-b851-411f-b679-f1bbc9df2c43 | obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             | 2026-06-06 15:28:25.343769+00
 mem-9e09834a-f1d1-4ea6-814e-723ad93e41da | obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             | 2026-06-06 15:16:31.626053+00
 mem-5a871730-dec1-4182-a512-44ea0f513f81 | obsidian:05_Ideas/20260606-171013-Морская-прогулка.md                                             | 2026-06-06 15:10:14.437723+00
(119 rows)

```

## Soft-delete SQL (planned)
```sql
UPDATE memory_notes SET status='deleted', deleted_at=now()
 WHERE source LIKE 'obsidian:%' AND status <> 'deleted'
   AND memory_id NOT IN (
     SELECT DISTINCT ON (source) memory_id FROM memory_notes
      WHERE source LIKE 'obsidian:%' AND status <> 'deleted'
      ORDER BY source, created_at DESC);
```

## Rollback SQL (reverse the soft-delete)
```sql
-- Restores exactly the rows this audit soft-deleted (only obsidian rows marked deleted by this run).
UPDATE memory_notes SET status='active', deleted_at=NULL
 WHERE source LIKE 'obsidian:%' AND status='deleted' AND memory_id IN (
   'mem-00ecf553-3032-4466-a38a-f2662694dd60','mem-01b555f0-c997-4a4e-b1a0-a38b6a25be0d','mem-022997f5-85c6-40f7-956e-4f0c077e904e','mem-023bd219-cb46-408b-a844-0a703828f0c5','mem-038f33ee-841c-4d36-a175-04ee473426ae','mem-045a9988-4fcf-4279-9b1b-d198af107611','mem-091e15ee-45c2-4a3d-b665-d6c76bda784b','mem-110c41df-5678-4933-8e43-c028467517b4','mem-137efee7-934c-46b9-9a57-c161d4ea842c','mem-16d80791-433c-4b43-a863-6ca1eac8abf9','mem-176add28-b0f2-41b0-b086-097e9fd0f6c5','mem-18f69107-975b-44e4-94de-4882830455c3','mem-19cd2336-e3e1-4435-a7b4-0fc99a6f0626','mem-19cf73ae-b25a-4b23-b8c1-406d3ba08199','mem-1e3afaec-a23f-4b66-8701-79b7178f51b3','mem-1f08e24f-379e-4d8d-88e8-ae4467f89f50','mem-21a1c1ba-2536-494a-922a-750d33c900ba','mem-21ce50c4-1620-4234-b8b2-68d514841efe','mem-2313c0ba-da3f-4948-9219-27c18e908306','mem-231c1416-e114-40ba-86b7-627e0106fc49','mem-28630ddf-9df0-40d5-b869-0383fc288522','mem-28c1a9ae-bed9-4be6-8908-715df7a57fbf','mem-2c6dbdb1-ce8a-41e0-8236-72859ed368eb','mem-2e63261a-3a34-4781-87c0-1b9e224296cd','mem-3425aee6-04dc-4190-8c40-482af4d77e0e','mem-36868c9c-3fb6-4114-93d0-6639ff7a6bc1','mem-371f644f-bb5c-42f0-89de-cba2e165b53b','mem-372c1d36-41d3-4d4a-8d48-1303722cbf12','mem-3a2faa6c-f8ca-4cfd-977b-a199e2bde6db','mem-3b54ed97-aedd-45b7-a9cb-16a65292addf','mem-3bd87d93-83eb-42c9-80fb-ca5b37c113be','mem-3c84d3f2-59a3-44ee-9487-c7103c47b7cd','mem-42acf811-4e7b-445d-add3-4373c95e2c58','mem-43c347da-f4e1-4cff-8335-d18278699910','mem-44a01167-2289-4f84-9171-c90b5f70e5c2','mem-456d1f4e-4bed-43e6-af86-281af687dd1c','mem-47ac933e-82af-40ca-8fb4-f77ff54cf4be','mem-494ace77-e99f-4fc1-ad8e-fa430f8eb630','mem-49ee9cee-9aa8-496a-93e5-3b821d3726f7','mem-50faf471-ea8e-4173-be11-68306eff27e9','mem-5350f78b-113d-419b-8033-9b30cfadf38c','mem-5a871730-dec1-4182-a512-44ea0f513f81','mem-5bfbe738-b68d-4e0f-bbc2-b425429dd37a','mem-5cfd0511-e040-4795-8cde-db90b7f69c18','mem-5e7f4dfa-8f42-455c-b409-b793b20d3ba0','mem-5f338d67-c110-4ced-8966-9b844527cc79','mem-6161ecaf-1c11-41f4-aba1-bd97be707f18','mem-61c414c7-dca4-4dd8-b2bf-3f2c264d4e1e','mem-62a934ef-7512-4ba7-a025-052ab2854e45','mem-64a45a12-e0eb-43f4-ac53-18fae794671d','mem-6523e9f2-ab06-4bf8-b001-db48a7c91ccc','mem-65936bd2-a038-4dce-b952-d0e226b8d9ba','mem-66306cde-9ec3-4d9d-acc3-3f6ea5e7b9e8','mem-68e57786-0d1e-4e14-9471-571950db68cf','mem-696630b0-ec6b-4b3a-8032-76a000e1cbc1','mem-6a127519-6f6b-4a0b-96d3-316e06be15fa','mem-6a38b1a2-97a6-41bf-b103-7bdc0fc7a928','mem-6ec817a2-c783-4a6c-aa90-a922485f42c9','mem-71174f35-cd16-4079-9854-9323877f8562','mem-76cd0e3a-abf3-401c-93c6-46f2f1d0c12f','mem-7736fbf5-70d1-4d80-80f9-5500ea690ea6','mem-7c590b55-6b06-45bd-8a16-d067d8aa15b0','mem-7dce7031-fb1f-41e0-a450-9bff82178fb8','mem-87a5474e-1726-49f4-8c78-f0c21ddb8328','mem-88ea4328-4dd9-4c99-a4d4-42186b26e88b','mem-907b6217-f686-42cc-9069-50281050868b','mem-93678b45-0902-4c0a-b162-28357511e06b','mem-93b7159b-3098-4f59-84a6-cde0d41f1cc5','mem-9527d275-b851-411f-b679-f1bbc9df2c43','mem-9941aedf-56a5-427e-ac51-83b7b0931664','mem-9995507a-43a2-4850-a1e2-eb075a4c6889','mem-9a697dae-f4bb-4bd2-a573-f80abb1defa2','mem-9d3478bd-a77b-4156-bfbc-05c4abd02077','mem-9e09834a-f1d1-4ea6-814e-723ad93e41da','mem-9ea07d5d-f63e-4c0e-93aa-212e584987c2','mem-a2d85bf8-282c-44ec-a684-bbef51e51896','mem-a328d87c-0c35-471e-8140-c50e708ec8cd','mem-a5aaa1a7-ba5b-47ff-b61a-7578c0f08065','mem-aa62cb3a-2db7-4f85-8ed4-e68980ef5571','mem-ab890e8a-8f8b-45b8-af67-2e1cc722b2af','mem-abb1eb9d-a22a-4b8b-904d-047eba569f29','mem-af67e4b2-9dc3-4481-accc-b28fb1e162ff','mem-afc27a6a-a6a8-4e13-bc1b-eeae33bf91c3','mem-afd129e9-b934-4703-b7ce-aaaea4c32831','mem-b00bfcad-3ad7-40d3-8695-94b22ba7bed4','mem-b700a00f-b263-44ab-8168-01db288a8959','mem-b9471dfa-82d0-4bc9-9a87-e45e53108d23','mem-b98e1a2d-987f-425c-9b3f-2e64131ac36a','mem-bac9bf49-5ba3-4415-bdd2-7bc9d61a6e5b','mem-bdc46cde-e492-40b3-99d3-01e24eab9217','mem-beba49ed-6dc8-4785-a145-6c9642a00fd5','mem-c0e0e68e-c005-4e10-9f30-0e819f67189c','mem-c219380e-3b8f-4334-b7dc-5ca6bb9c6aa9','mem-c40e9b62-42e6-4694-aeeb-2d94b5c43642','mem-c636152c-c726-4f12-b65e-cefa1cc96836','mem-c7b455a2-4afa-4e3c-9f44-b6597b0c4fb5','mem-c8745900-375d-4ef2-81fb-35e9c7162e9c','mem-ccd34c14-00f5-4122-ac64-4a653b4496e1','mem-cd9fa3d6-3b69-4540-83a4-83836494c4e6','mem-cddc1882-f4bc-495b-b6e8-e26f700d3e6f','mem-d0f1460d-3a90-4c40-8d91-3a1565342297','mem-d4e9ca33-7a9a-4335-ba5e-fe50d37f41d7','mem-d809e3af-b581-4336-8118-9edbcefcccac','mem-dbc26b25-90dc-4141-a284-38d3994d722a','mem-dbe04bea-3133-4dd2-8a25-04a7c1f1e98a','mem-dd3db54d-bf8b-4ea4-9f93-6391aa30de06','mem-deba3f01-6e3b-4ac2-a651-9c919df98ea6','mem-df62269b-9e4b-41a5-b9e7-3ff99eafe3b5','mem-e4ee5de8-e1a2-48df-bb29-176cacf7ea7e','mem-e5e0ee93-a95e-455e-b075-7bd7e20a1e93','mem-e85c2ef8-27a7-4cda-91fe-1e583f28a49c','mem-e913550f-0c99-4b62-a4fa-14c54fa33907','mem-f0a8aadf-c8cb-4686-9117-6027fa26ed8d','mem-f0df560e-7294-4d69-838e-5d2bd88c2bc0','mem-f833d9e2-0347-4700-b90c-693cf5f4b0ee','mem-f8af56ee-0c27-4502-b21a-93305cc2b730','mem-faaa6b84-4170-4547-b9de-4c28f833be64','mem-fd5dc30c-ab5d-4238-bcd3-82dddaf57e2a','mem-ff8318c6-8176-4c42-8bd0-0ed284e4befc'
 );
```

---
## EXECUTED RESULT (2026-06-07T18:19:01+02:00)
- Soft-delete ran: `UPDATE 119` (exactly as audited).
- Active obsidian rows AFTER: **25**
- Duplicate sources AFTER: **0**
- Soft-deleted obsidian rows: **119** (status='deleted', physically retained)
- Non-obsidian rows: **untouched** (4 active).
- Kept rows with embedding: **25/25** (semantic search intact).
- Semantic search 'парусное судно в океане' → **5 obsidian results**.
- smoke-verify: **8/8**. final-check: **10/10**. No pod restarts.
- No physical DELETE. No vault file changes. No Git writes.
- Rollback: run the 'Rollback SQL' block above (restores exactly these 119 memory_id).
