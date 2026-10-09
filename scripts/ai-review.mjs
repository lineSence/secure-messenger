#!/usr/bin/env node
// ИИ-ревью безопасности PR (ADR-20, ADR-26). Без зависимостей: Node 20+.
// Режимы: 1) чат — вердикт в .github/ai-review/manual/pr-<N>.json; 2) API — модели Dahl, секрет из cfg.api_key_env.
// Правила ревью — .github/ai-review/INSTRUCTIONS.md (один источник для обоих режимов).
// В CI: BASE_SHA, HEAD_SHA, PR. Локально: node scripts/ai-review.mjs --dry-run
import { execFileSync } from 'node:child_process';
import { readFileSync, existsSync, appendFileSync } from 'node:fs';

const dry = process.argv.includes('--dry-run');
const base = process.env.BASE_SHA || 'origin/main';
const head = process.env.HEAD_SHA || 'HEAD';
// Настройки и правила берутся из базовой ветки: PR не может ослабить ревью сам себе.
// Если в базе файла ещё нет (первое появление) — из ветки PR.
const fromBase = (p) => { try { return execFileSync('git', ['show', `${base}:${p}`], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }); } catch { return readFileSync(p, 'utf8'); } };
const cfg = JSON.parse(fromBase('.github/ai-review.json'));
const instructions = fromBase('.github/ai-review/INSTRUCTIONS.md');
const waivers = JSON.parse(fromBase('.github/ai-review-waivers.json')); // исключения — тоже из базы: PR не может простить сам себя
const pr = process.env.PR || '';
const apiKey = process.env[cfg.api_key_env] || '';
const MANUAL_DIR = '.github/ai-review/manual/';
const git = (...a) => execFileSync('git', a, { encoding: 'utf8', maxBuffer: 64 << 20 });
const summary = (s) => { console.log(s); if (process.env.GITHUB_STEP_SUMMARY) appendFileSync(process.env.GITHUB_STEP_SUMMARY, s + '\n'); };
const fail = (msg) => { summary(`\n**Ошибка ИИ-ревью:** ${msg}`); process.exit(1); };
const ignored = (f) => cfg.ignore_paths.some((p) => f === p || f.startsWith(p));

summary('## ИИ-ревью безопасности');
const files = git('diff', '--name-only', `${base}...${head}`).split('\n').filter(Boolean).filter((f) => !ignored(f));
if (!files.length) { summary('Изменений в коде нет — ревью не требуется.'); process.exit(0); }

// Общая оценка находок: блок по уровню, если нет исключения.
function judge(results) {
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
  const ok = results.filter(([, f]) => Array.isArray(f));
  if (ok.length < cfg.min_models_ok) fail(`моделей с ревью: ${ok.length}, нужно минимум ${cfg.min_models_ok} разных.`);
  if (blocking) fail(`блокирующих находок (${cfg.block_severities.join('/')}): ${blocking}. Исправьте с воспроизводящим тестом или, если это ложное срабатывание, добавьте исключение с обоснованием и тестом в .github/ai-review-waivers.json отдельным PR (исключения читаются из базовой ветки).`);
  summary('\nБлокирующих находок нет.');
  process.exit(0);
}

// Режим чата: вердикт принят, если после reviewed_head менялись только файлы вердиктов.
const manualPath = `${MANUAL_DIR}pr-${pr}.json`;
if (pr && existsSync(manualPath)) {
  summary(`Режим чата: ${manualPath}`);
  let v;
  try { v = JSON.parse(readFileSync(manualPath, 'utf8')); } catch (e) { fail(`${manualPath}: не JSON (${e.message}).`); }
  const rh = String(v.reviewed_head || '');
  if (!/^[0-9a-f]{40}$/.test(rh)) fail('reviewed_head должен быть полным SHA из 40 символов.');
  if (String(v.pr) !== pr) fail(`в вердикте pr=${v.pr}, а это PR #${pr}.`);
  try { git('merge-base', '--is-ancestor', rh, head); } catch { fail(`коммит ${rh.slice(0, 7)} не входит в ветку PR.`); }
  const after = git('diff', '--name-only', `${rh}..${head}`).split('\n').filter(Boolean).filter((f) => !f.startsWith(MANUAL_DIR));
  if (after.length) {
    if (!apiKey) fail(`код изменился после ревью ${rh.slice(0, 7)}: ${after.slice(0, 10).join(', ')}. Повторите ревью в чате по .github/ai-review/INSTRUCTIONS.md.`);
    summary(`Вердикт чата устарел (изменились: ${after.slice(0, 10).join(', ')}) — перехожу на API.`);
  } else {
    const seen = new Map();
    for (const r of Array.isArray(v.reviews) ? v.reviews : []) {
      const m = String(r.model || '').trim().toLowerCase();
      if (m && Array.isArray(r.findings) && !seen.has(m)) seen.set(m, r.findings);
    }
    judge([...seen]);
  }
}

// Режим API. cfg.mode: "auto" (чат, иначе API) или "manual" (только вердикт чата, API не вызывается).
if (cfg.mode === 'manual') fail(`режим "manual" (.github/ai-review.json): нужен вердикт чата в ${manualPath}. Попросите модель выполнить .github/ai-review/INSTRUCTIONS.md для PR #${pr}.`);
let diff = git('diff', '--unified=12', `${base}...${head}`, '--', ...files);
if (diff.length > cfg.max_diff_chars) fail(`diff ${diff.length} символов больше лимита ${cfg.max_diff_chars}. Разбейте PR на части.`);
// Большой diff делится на части по границам файлов: модели отвечают быстрее и не упираются в лимиты.
const parts = [];
for (const fileDiff of diff.split(/(?=^diff --git )/m)) {
  const last = parts.at(-1);
  if (last && last.text.length + fileDiff.length <= cfg.chunk_chars) last.text += fileDiff;
  else parts.push({ n: parts.length + 1, text: fileDiff });
}
const rules = instructions.split('## Правила ревью')[1]?.split('\n## ')[0]?.trim();
if (!rules) fail('в INSTRUCTIONS.md нет раздела «Правила ревью».');

// Ответ читается потоком (SSE): соединение не простаивает, прокси не рвёт его по тайм-ауту (HTTP 524).
async function readAnswer(res) {
  if (!(res.headers.get('content-type') || '').includes('text/event-stream')) {
    const j = await res.json();
    const msg = j.choices?.[0]?.message ?? {};
    return { text: msg.content ?? '', finish: j.choices?.[0]?.finish_reason ?? '', reasoning: (msg.reasoning_content ?? msg.reasoning ?? '').length };
  }
  const dec = new TextDecoder();
  let buf = '', text = '', finish = '', reasoning = 0;
  for await (const chunk of res.body) {
    buf += dec.decode(chunk, { stream: true });
    let i;
    while ((i = buf.indexOf('\n')) >= 0) {
      const line = buf.slice(0, i).trim();
      buf = buf.slice(i + 1);
      if (!line.startsWith('data:')) continue;
      const data = line.slice(5).trim();
      if (data === '[DONE]') continue;
      let j;
      try { j = JSON.parse(data); } catch { continue; }
      if (j.error) throw new Error(`ошибка в потоке: ${JSON.stringify(j.error).slice(0, 300)}`);
      const c = j.choices?.[0];
      text += c?.delta?.content ?? '';
      reasoning += (c?.delta?.reasoning_content ?? c?.delta?.reasoning ?? '').length;
      if (c?.finish_reason) finish = c.finish_reason;
    }
  }
  return { text, finish, reasoning };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// Первый сбалансированный JSON-объект с полем findings: модель может дописать мусор после ответа.
function extractFindings(text) {
  for (let start = text.indexOf('{'); start >= 0; start = text.indexOf('{', start + 1)) {
    let depth = 0, inStr = false, esc = false;
    for (let i = start; i < text.length; i++) {
      const ch = text[i];
      if (inStr) { if (esc) esc = false; else if (ch === '\\') esc = true; else if (ch === '"') inStr = false; continue; }
      if (ch === '"') inStr = true;
      else if (ch === '{') depth++;
      else if (ch === '}' && --depth === 0) {
        try { const j = JSON.parse(text.slice(start, i + 1)); if (Array.isArray(j.findings)) return j.findings; } catch { /* дальше */ }
        break;
      }
    }
  }
  return null;
}

// Параметры модели (например, отключить долгие рассуждения) — из cfg.model_options.
// Если провайдер их отверг (HTTP 400/422), повторяем запрос без них.
async function ask(model, part) {
  const { prompt_suffix: promptSuffix = '', ...opts } = cfg.model_options?.[model] ?? {};
  let extra = opts;
  const suffix = promptSuffix ? `\n\n${promptSuffix}` : '';
  for (let attempt = 1; attempt <= 5; attempt++) {
    try {
      const t0 = Date.now();
      const res = await fetch(cfg.endpoint, {
        method: 'POST',
        headers: { Authorization: `Bearer ${apiKey}`, 'Content-Type': 'application/json', Accept: 'text/event-stream' },
        // Номер попытки меняет запрос: провайдер кэширует одинаковые запросы и повторяет тот же ответ.
        body: JSON.stringify({ model, temperature: 0, stream: true, max_tokens: cfg.max_tokens, ...extra,
          messages: [{ role: 'system', content: rules },
            { role: 'user', content: `Diff PR (часть ${part.n} из ${parts.length}, попытка ${attempt}):\n\n${part.text}${suffix}` }] }),
        signal: AbortSignal.timeout(600_000),
      });
      if (!res.ok) {
        const body = (await res.text()).replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').slice(0, 200);
        if ((res.status === 400 || res.status === 422) && Object.keys(extra).length) {
          console.error(`${model}: параметры ${JSON.stringify(extra)} отвергнуты (HTTP ${res.status}: ${body}) — повтор без них`);
          extra = {};
          continue;
        }
        const e = new Error(`HTTP ${res.status}: ${body}`);
        e.status = res.status;
        throw e;
      }
      const { text, finish, reasoning } = await readAnswer(res);
      console.error(`${model} [часть ${part.n}]: ответ ${text.length} символов, рассуждение ${reasoning}, ${Math.round((Date.now() - t0) / 1000)} с, finish_reason=${finish || 'нет'}`);
      const findings = extractFindings(text);
      if (!findings) throw new Error(`в ответе нет JSON с findings (finish_reason=${finish || 'нет'}): ${text.slice(0, 200)}`);
      return findings;
    } catch (e) {
      console.error(`${model} [часть ${part.n}]: попытка ${attempt}: ${e.message}`);
      if (attempt === 5 || e.status === 401 || e.status === 403) return null;
      await sleep((e.status === 429 ? 30000 : 10000) * attempt); // 429 — модель занята, ждём дольше
    }
  }
  return null;
}

if (dry) { summary(`Пробный запуск: файлы ${files.join(', ')}; модели ${cfg.models.join(', ')}; diff ${diff.length} символов, частей ${parts.length}; правила ${rules.length} символов.`); process.exit(0); }
if (!apiKey) fail(`нет ни вердикта чата (${manualPath}), ни секрета ${cfg.api_key_env}. Варианты: 1) добавьте секрет в Settings → Secrets and variables → Actions; 2) попросите модель в чате выполнить .github/ai-review/INSTRUCTIONS.md для PR #${pr}.`);
// Модель засчитывается, только если ответила по всем частям.
judge(await Promise.all(cfg.models.map(async (m) => {
  const answers = [];
  for (const part of parts) answers.push(await ask(m, part));
  return [m, answers.some((a) => a === null) ? null : answers.flat()];
})));
