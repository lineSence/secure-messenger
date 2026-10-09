# Код, написанный ИИ: риски (факты)

По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-09.

| Факт | Источник · дата |
|---|---|
| Код от 100+ LLM содержал уязвимости в 45% тестов; более крупные и новые модели не безопаснее | [Veracode GenAI Code Security Report](https://www.veracode.com/resources/analyst-reports/2025-genai-code-security-report/) · обновление 2025-10 |
| В исследовании 6 LLM на OWASP Top 10:2025 — подтверждённые уязвимости в 25,7% сгенерированного кода | [AppSec Santa](https://appsecsanta.com/research/ai-code-security-study-2026) · 2026-05-12 |
| Формальная проверка уязвимостей в коде ИИ («Broken by Default») | [arXiv 2604.05292](https://arxiv.org/abs/2604.05292v2) · 2026-04-08 |
| Рост числа CVE из кода ИИ, в том числе небезопасная криптография (CWE-327) | [Cloud Security Alliance](https://labs.cloudsecurityalliance.org/research/csa-research-note-ai-generated-code-vulnerability-surge-2026) · 2026 |

## Выводы (наши, не факты)
- Криптографию не генерировать — только вызывать проверенные библиотеки (ADR-1).
- Каждое изменение — через тесты и CI; парсеры сетевых данных — под фаззинг.
