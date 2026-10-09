#!/usr/bin/env node
// ИИ-ревью безопасности PR ансамблем моделей (ADR-20). Без зависимостей: Node 20+.
// Запуск в CI: BASE_SHA, HEAD_SHA, OPENROUTER_API_KEY. Локально: node scripts/ai-review.mjs --dry-run
import { execFileSync } from 'node:child_process';
import { readFileSync, appendFileSync } from 'node:fs';

const cfg = JSON.parse(readFileSync('.github/ai-review.json', 'utf8'));
const waivers = JSON.parse(readFileSync('.github/ai-review-waivers.json', 'utf8'));
const dry = process.argv.includes('--dry-run');
const base = process.env.BASE_SHA || 'origin/main';
const head = process.env.HEAD_SHA || 'HEAD';
const summary = (s) => { console.log(s); if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, s + '\n'); };
const fail = (msg) => { summary(`\n**Ошибка ИИ-ревью:** ${msg}`); process.exit(1); };

const files = execFileSync('git', ['diff', '--name-only', `${base}...${head}`], { encoding: 'utf8' })
  .split('\n').filter(Boolean).filter((f) => !cfg.ignore_paths.some((p) => f === p || f.startsWith(p)));
summary('## ИИ-ревью безопасности');
if (!files.length) { summary('Изменений в коде нет — ревью не требуется.'); process.exit(0); }
let diff = execFileSync('git', ['diff', '--unified=12', `${base}...${head}`, '--', ...files], { encoding: 'utf8', maxBuffer: 64 << 20 });
if (diff.length > cfg.max_diff_chars) fail(`diff ${diff.length} символов больше лимита ${cfg.max_diff_chars}. Разбейте PR на части.`);

const system = `Ты — независимый ревьюер безопасности кода личного мессенджера со сквозным шифрованием (Rust-ядро, Android, Windows).
Правила проекта: своя криптография запрещена (только libsignal и OpenMLS); все данные из сети и с диска враждебны;
разбор не должен паниковать, читать за границы, переполняться или выделять память по длине из заголовка;
ключи и открытый текст не попадают в логи; метаданные минимальны; зависимости — только проверенные.
Ищи только реальные проблемы безопасности и корректности в ИЗМЕНЁННОМ коде. Не придирайся к стилю.
Ответ — строго JSON без markdown: {"findings":[{"severity":"critical|high|medium|low","file":"путь","line":число,"title":"кратко","explanation":"почему это уязвимость и как воспроизвести","fix":"как исправить"}]}.
Если проблем нет — {"findings":[]}.`;

async function ask(model) {
  for (let attempt = 1; attempt <= 3; attempt++) {
    try {
      const res = await fetch(cfg.endpoint, {
        method: 'POST',
        headers: { Authorization: `Bearer ${process.env.OPENROUTER_API_KEY}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ model, temperature: 0, response_format: { type: 'json_object' },
          messages: [{ role: 'system', content: system }, { role: 'user', content: `Diff PR:\n\n${diff}` }] }),
        signal: AbortSignal.timeout(300_000),
      });
      if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 300)}`);
      const text = (await res.json()).choices?.[0]?.message?.content ?? '';
      const json = JSON.parse(text.slice(text.indexOf('{'), text.lastIndexOf('}') + 1));
      if (!Array.isArray(json.findings)) throw new Error('нет поля findings');
      return json.findings;
    } catch (e) {
      console.error(`${model}: попытка ${attempt}: ${e.message}`);
      if (attempt === 3) return null;
      await new Promise((r) => setTimeout(r, 5000 * attempt));
    }
  }
}

if (dry) { summary(`Пробный запуск: файлы ${files.join(', ')}; модели ${cfg.models.join(', ')}; diff ${diff.length} символов.`); process.exit(0); }
if (!process.env.OPENROUTER_API_KEY) fail('не задан секрет OPENROUTER_API_KEY (Settings → Secrets and variables → Actions). Без него PR не проходит.');

const results = await Promise.all(cfg.models.map(async (m) => [m, await ask(m)]));
const ok = results.filter(([, f]) => f !== null);
let blocking = 0;
for (const [m, f] of results) {
  if (f === null) { summary(`- **${m}**: нет ответа`); continue; }
  summary(`\n### ${m}: находок ${f.length}`);
  for (const x of f) {
    const sev = String(x.severity || '').toLowerCase();
    const waived = waivers.find((w) => w.file === x.file && String(x.title || '').includes(w.title_contains));
    const block = cfg.block_severities.includes(sev) && !waived;
    if (block) blocking++;
    summary(`- ${block ? 'БЛОК' : waived ? 'исключение' : 'инфо'} **${sev}** \`${x.file}:${x.line}\` — ${x.title}\n  ${x.explanation}\n  Исправление: ${x.fix}`);
  }
}
if (ok.length < cfg.min_models_ok) fail(`ответили ${ok.length} из ${cfg.models.length} моделей, нужно минимум ${cfg.min_models_ok}.`);
if (blocking) fail(`${blocking} находок уровня ${cfg.block_severities.join('/')}. Исправьте с воспроизводящим тестом или, если это ложное срабатывание, добавьте исключение с обоснованием и тестом в .github/ai-review-waivers.json.`);
summary('\nБлокирующих находок нет.');
