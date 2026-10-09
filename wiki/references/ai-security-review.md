# Проверка кода ИИ: модели и инструменты (факты)

По [DOCS-SOURCE-DATE]. Дата сбора: 2026-10-09. Рейтинги моделей устаревают за 1–2 месяца — перед выбором модели перепроверять (см. [DOCS-RECHECK-BEFORE-USE]).

## Модели и агенты для поиска уязвимостей

| Факт | Источник · дата |
|---|---|
| Бенчмарк Aikido: 10 моделей, 32 свежие CVE, по 3 прогона. DeepSeek V4 Pro 0813 — 28/32 по сумме трёх прогонов (17 за первый), ~$295 за три прогона; один прогон Opus 5, Grok 4.6 или Sol — ~$450–590 и меньше находок; Grok — самый стабильный между прогонами; GLM-5.3-preview — 75% CVE, на 40% дешевле GPT 5.6 Terra | [Aikido](https://www.aikido.dev/blog/ai-model-benchmarks-aug-21-2026) · 2026-08-21, обновлено 2026-10-05 |
| Вывод того же бенчмарка: одна модель за один прогон пропускает много; объединение нескольких прогонов резко повышает охват | там же |
| CyberGym (Berkeley, ICLR 2026) — бенчмарк воспроизведения реальных уязвимостей; живой рейтинг Frontier AI Cybersecurity Observatory | [cybergym.io](https://www.cybergym.io/); [HF Observatory](https://huggingface.co/spaces/FrontierAICybersecurity/Cybersecurity_leaderboard) · на 2026-10-09 |
| Gemini 3.5 Flash Cyber: 55 подтверждённых проблем в V8 (Gemini 3.5 Flash — 47, Claude Opus 4.6 — 36), CyberGym ~83,2% — данные производителя | [orcarouter](https://www.orcarouter.ai/ko/blog/codemender-managed-agent-preview) · 2026-07-21 |
| Google CodeMender (агент поиска и исправления): 72 исправления в open source; managed preview в Google Cloud | [SDxCentral](https://www.sdxcentral.com/news/google-deepmind-unveils-ai-agent-that-automatically-patches-software-vulnerabilities/) · 2025-10-07; [Google Cloud blog](https://cloud.google.com/blog/products/identity-security/find-and-fix-software-vulnerabilities-with-codemender) · 2026-07-21 |
| Google Big Sleep нашёл CVE-2025-6965 (SQLite) до использования злоумышленниками; список находок — публичный трекер | [The Record](https://therecord.media/google-big-sleep-ai-tool-found-bug) · 2025-07; [Google Cloud blog](https://cloud.google.com/blog/products/identity-security/cloud-ciso-perspectives-our-big-sleep-agent-makes-big-leap) · 2025 |
| OpenAI Aardvark — агент-исследователь безопасности | [OpenAI](https://openai.com/index/introducing-aardvark) · 2025-10 |
| Anthropic: Claude Opus 4.6 нашёл 500+ уязвимостей в open source; Claude Code Security; открытый GitHub Action `anthropics/claude-code-security-review` с фильтром ложных срабатываний | [Anthropic](https://www.anthropic.com/news/claude-code-security) · 2026-02; [GitHub](https://github.com/anthropics/claude-code-security-review) · на 2026-10-09 |
| DARPA AIxCC: победитель Team Atlanta; системы финалистов выложены в open source | [DARPA](https://www.darpa.mil/news/2025/aixcc-results) · 2025-08-08 |
| Claude Security вышел из закрытого превью | [The New Stack](https://thenewstack.io/anthropics-claude-security-beta/) · 2026 |
| Исследования 2026: OpenAnt (агентный поиск уязвимостей), AgenticSCR (агентное ревью), сравнение LLM-агентов для отсева ложных срабатываний (ISSTA 2026) | [arXiv 2606.19149](https://arxiv.org/html/2606.19149v2); [arXiv 2601.19138](https://arxiv.org/abs/2601.19138); [arXiv 2601.22952](https://arxiv.org/abs/2601.22952) · 2026 |
| LLM-судьи завышают оценки «своим» ответам (self-preference bias); у 20 моделей сильнее модель ≠ меньше предвзятость | [arXiv 2604.22891](https://arxiv.org/html/2604.22891v4) · 2026 |

## Инструменты (Rust + Android)

| Факт | Источник · дата |
|---|---|
| CodeQL поддерживает Rust (редакции 2021 и 2024, nightly-функции — нет); Rust — public preview с 2025-06-30 | [CodeQL docs](https://codeql.github.com/docs/codeql-overview/supported-languages-and-frameworks/) · на 2026-10-09; [GitHub changelog](https://github.blog/changelog/2025-06-30-codeql-support-for-rust-now-in-public-preview) · 2025-06-30 |
| Copilot Autofix для приватных репозиториев — только у организаций с лицензией GitHub Code Security; агентный autofix (CodeQL + сторонние сканеры) — public preview | [GitHub Docs](https://docs.github.com/en/code-security/concepts/code-scanning/autofix-for-code-scanning) · на 2026-10-09; [changelog](https://github.blog/changelog/2026-07-10-agentic-autofix-for-code-scanning-alerts-in-public-preview/) · 2026-07-10 |
| Semgrep Supply Chain: анализ достижимости уязвимых зависимостей для Rust | [Semgrep blog](https://semgrep.dev/blog/2026/semgrep-supply-chain-extends-reachability-coverage-to-rust) · 2026 |
| cargo-audit — проверка зависимостей по базе уязвимостей (unmaintained — только предупреждение без `--deny warnings`); cargo-vet — проверка, что каждая зависимость проверена по заданным критериям, аудиты хранятся в репозитории | [TuxCare](https://tuxcare.com/blog/cargo-audit-rust-security/) · 2026; [cargo-vet](https://mozilla.github.io/cargo-vet/how-it-works.html) · на 2026-10-09 |
| Атаки на цепочку поставок через crates.io продолжаются (крипто-стилер в npm/PyPI/crates) | [Socket](https://socket.dev/blog/trapdoor-crypto-stealer-npm-pypi-crates) · 2026 |
| OSV-Scanner v2 (Google): последняя версия v2.3.6 | [GitHub releases](https://github.com/google/osv-scanner/releases) · 2026-05-01 |
| Kani — model checker для Rust (статья ASE '26), релизы ежемесячно; ограничения — например, нет многопоточности | [Kani](https://github.com/model-checking/kani) · 2026; [arXiv 2607.01504](https://arxiv.org/abs/2607.01504) · 2026-07 |
| Фаззинг Rust: cargo-fuzz, интеграция с OSS-Fuzz | [OSS-Fuzz docs](https://google.github.io/oss-fuzz/getting-started/new-project-guide/rust-lang/) · на 2026-10-09 |
| MobSF — open source статический и динамический анализ Android-приложений | [MobSF](https://github.com/mobsf/mobile-security-framework-mobsf) · на 2026-10-09 |
| Воспроизводимые сборки Android — практика F-Droid; F-Droid 2.0 | [F-Droid](https://f-droid.org/en/docs/Reproducible_Builds) · на 2026-10-09; [F-Droid 2.0](https://f-droid.org/en/2026/09/24/f-droid-2.0-a-new-chapter-for-android-freedom.html) · 2026-09-24 |

## Выводы (наши, не факты)
- Лучшей модели нет: рейтинги расходятся и меняются каждый месяц. Берём ансамбль — 1–2 закрытые модели переднего края + 1 открытая (DeepSeek / GLM / Qwen), каждую по 3 прогона.
- Ревьюер — модель другого разработчика, чем модель-автор (защита от self-preference bias).
- Находка ИИ считается настоящей, только если есть падающий тест или вход фаззера; исправление — только с этим тестом.
- Слои: (1) rustfmt, clippy, cargo-deny, cargo-audit, cargo-vet, OSV-Scanner, Android Lint; (2) юнит- и property-тесты, cargo-fuzz для всех парсеров сетевых данных, Miri для `unsafe`; (3) CodeQL + Semgrep; (4) ИИ-ревью каждого PR; (5) раз в релиз — глубокий агентный аудит всего кода; (6) Kani — для инвариантов хранилища ключей и разбора форматов.
- Код криптографии не генерируется (ADR-1) — ИИ только вызывает libsignal и OpenMLS; ревью проверяет, что вызовы соответствуют документации.
- Доступ к закрытым моделям и оплата из РФ — отдельный риск; открытые модели можно запускать у любого провайдера.
