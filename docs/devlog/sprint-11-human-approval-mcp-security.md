# Sprint 11 — human approval, MCP and security of agent actions

## Contents

- [S11-01](#s11-01-tool-risk-classification-a-closed-deny-by-default-vocabulary-owned-by-the-catalogue) — tool risk classification policy (S11-01)
- [S11-02](#s11-02) — future: human-approval state machine for WRITE / EXTERNAL_SIDE_EFFECT actions
- [S11-03](#s11-03) — future: approvals store, consent lifecycle and audit (append-only)
- [S11-04](#s11-04) — future: tool allowlist enforcement + permission checks on agent actions
- [S11-05](#s11-05) — future: MCP tool allowlist and risk classification
- [S11-06](#s11-06) — future: MCP gateway hardening (transport, secrets, telemetry)

---

## S11-01 — tool risk classification: a closed, deny-by-default vocabulary owned by the catalogue

**Task (P0):** классифицировать tools по риску (READ_ONLY, WRITE, EXTERNAL_SIDE_EFFECT);
неизвестную risk class считать запрещённой по умолчанию.

**Результат:** tool risk policy — закрытый словарь risk class, определённый один в owning модуле, с deny-by-default.

### Что изменено

- **Тест приёмки** — `ToolRiskClassificationAvailabilityTest` (`toolexecution.application`).
  Новые файлы в репозитории не создаются; это единственный новый тест. Проверяет
  допустимость на уровне `ToolRegistry` (единственный источник «какие инструменты зарегистрированы»):
  - классифицированный инструмент доступен для каждого из трёх закрытых risk class;
  - инструмент без risk class (`null`) отклоняется на этапе создания `ToolDescriptor` (контрактное
    `IllegalArgumentException`), поэтому он не может попасть в реестр → не доступен;
  - реестр не содержит некласифицированного инструмента (`size()==0`, `resolve()` пуст).

- **ADR-017** — `docs/adr/ADR-017-tool-risk-classification.md`. Закрытый deny-by-default словарь
  risk class: `READ_ONLY` / `WRITE` / `EXTERNAL_SIDE_EFFECT`, определён в owning модуле `agentcatalog`,
  недоступен неизвестный risk class (`ToolRiskClass.valueOf` бросает), `ToolDescriptor` требует не-
  `null` risk class (отклоняет на construct-этапе), `ToolInvocationPolicy` переносит `REASON_UNKNOWN_RISK_CLASS`
  как defensive fail-safe для пути загрузки из БД. Инструмент без risk class нельзя зарегистрировать → он недоступен.

### Почему так

- **Недоверие к модели (invariant 7, ADR-006):** модель — неоригинал-планировщик, не орган авторизации; риск
  определяется бэкендом, а не моделью.
- **Закрытый словарь:** без закрытого набора «неизвестное» не имеет смысла; `valueOf` отклоняет всё, что вне трёх значений.
- **Владение:** `agentcatalog` владеет «какие инструменты существуют и насколько они опасны» (module-map edge 3);
  `toolexecution` только читает risk class.
- **Проверка (invariant 3):** классификация — закрытый, межимодульный, персистентный набор — поэтому её расширение
  — breaking change и требует ADR.

### Проверка

```bash
./gradlew :apps:agentflow-app:test \
  --tests 'com.example.agentflow.toolexecution.application.ToolRiskClassificationAvailabilityTest' \
  --tests 'com.example.agentflow.agentcatalog.domain.ToolRiskClassTest' \
  --tests 'com.example.agentflow.toolexecution.domain.ToolDescriptorTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolRegistryTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolInvocationPolicyTest' \
  --tests 'com.example.agentflow.toolexecution.application.ToolAuthoringGuideExamplesTest' \
  --rerun-tasks
```

`BUILD SUCCESSFUL`. Класс `ToolRiskClassificationAvailabilityTest` — 2 теста, оба pass (проверено по XML в
`apps/agentflow-app/build/test-results/test/`), mtime актуальна. Тесты DB-free: `ToolRegistry` и
`ToolDescriptor` — чистые значения.

### Документация

- `docs/adr/ADR-017-tool-risk-classification.md` — новый (ADR-017).
- `docs/adr/README.md` — строка в реестре ADR (ADR-017, Accepted, 2026-11-14).
- `docs/architecture/README.md` — строка в «Current architectural constraints» (ref. ADR-017).
- `docs/adr/000-template.md`, `docs/adr/README.md` — шаблон ADR (используется).
- `CHANGELOG.md` — запись в `[Unreleased] > Added`.

### Риски / follow-up

- Расшикрытие risk class — breaking change закрытого словаря (новый вариант + ADR + обновление `reason`/taxonomies).
- `unknown_risk_class` в `ToolInvocationPolicy` — defensive fail-safe: конструктор уже отклоняет unclassified
  descriptor, так что валидный descriptor туда не доходит. Убирать нельзя.
- Следующие шаги (S11-02…S11-06): human-approval lifecycle для WRITE/EXTERNAL_SIDE_EFFECT, approvals store,
  tool allowlist enforcement, MCP tool allowlist + risk classification, MCP gateway hardening.
