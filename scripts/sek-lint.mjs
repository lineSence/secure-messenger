// SEK lint — проверка структуры знаний. Запуск: node scripts/sek-lint.mjs
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, sep } from 'node:path';

const L0 = 'AGENTS.md', MAX_L0 = 6000, MAX_INBOX = 20, INBOX_TTL_DAYS = 30;
const errors = [], warns = [];
const read = (f) => readFileSync(f, 'utf8');
const norm = (p) => p.split(sep).join('/');
const walk = (d) => readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [norm(join(d, f))]));
const ID_RE = /^[A-Z][A-Z0-9]*(-[A-Z0-9]+)+$/;

// L0: размер
const l0 = read(L0);
if (l0.length > MAX_L0) errors.push(`${L0}: ${l0.length} символов > ${MAX_L0}`);

// ID: формат и уникальность в L0 и wiki/rules
const wiki = walk('wiki').filter((f) => f.endsWith('.md'));
const rules = wiki.filter((f) => f.includes('/rules/'));
const ids = new Map();
for (const f of [L0, ...rules]) {
  const re = f === L0 ? /^- \[([A-Z0-9-]+)\]/gm : /^## \[([^\]]+)\]/gm;
  for (const m of read(f).matchAll(re)) {
    if (!ID_RE.test(m[1])) errors.push(`${f}: ID [${m[1]}] не в формате [AREA-NAME]`);
    if (ids.has(m[1])) errors.push(`[${m[1]}] дублируется: ${ids.get(m[1])} и ${f}`);
    ids.set(m[1], f);
  }
}

// Правила: обязательные строки (правилом считается только заголовок "## [")
for (const f of rules) for (const b of read(f).split(/^## (?=\[)/m).slice(1)) {
  const title = b.split('\n')[0];
  if (!/^Правило:/m.test(b)) errors.push(`${f}: «${title}» без строки «Правило:»`);
  if (!/^Добавлено: \d{4}-\d{2}-\d{2}/m.test(b)) errors.push(`${f}: «${title}» без строки «Добавлено: ГГГГ-ММ-ДД»`);
}

// Отозванные ID не должны встречаться и не переиспользуются
if (existsSync('memory/revoked.md')) for (const m of read('memory/revoked.md').matchAll(/^- \S+ \[([^\]]+)\]/gm))
  if (ids.has(m[1])) errors.push(`[${m[1]}] отозван, но есть в ${ids.get(m[1])}`);

// Ссылки на файлы: _routing.md и триггеры L0
for (const [src, text] of [['wiki/_routing.md', read('wiki/_routing.md')], [L0, l0]])
  for (const m of text.matchAll(/(wiki\/[\w./-]+\.md)/g))
    if (!existsSync(m[1])) errors.push(`${src}: нет файла ${m[1]}`);

// Оглавление покрывает все файлы wiki
const index = read('wiki/_index.md');
for (const f of wiki) if (!f.endsWith('_index.md') && !index.includes(f)) errors.push(`_index.md: не упомянут ${f}`);

// Inbox: лимит и срок жизни
const entries = [...read('memory/inbox.md').matchAll(/^- (\d{4}-\d{2}-\d{2}) \[/gm)];
if (entries.length > MAX_INBOX) errors.push(`inbox: ${entries.length} > ${MAX_INBOX}`);
for (const [, d] of entries) if ((Date.now() - Date.parse(d)) / 864e5 > INBOX_TTL_DAYS) warns.push(`inbox: запись ${d} старше ${INBOX_TTL_DAYS} дней — удали, если нет повторов`);

warns.forEach((w) => console.log(`⚠️  ${w}`));
if (errors.length) { errors.forEach((e) => console.error(`❌ ${e}`)); process.exit(1); }
console.log(`✅ SEK lint: ${ids.size} правил, ${entries.length}/${MAX_INBOX} в inbox`);
