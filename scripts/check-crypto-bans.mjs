#!/usr/bin/env node
// Сверка запретов криптопримитивов (ADR-1): deny.toml [bans].deny и два правила .semgrep/rules.yml
// должны покрывать одни и те же крейты. Для каждого имени проверяются формы объявления
// зависимости в Cargo.toml и формы использования в Rust; обычные крейты не должны ловиться.
// Регулярки берутся из rules.yml как есть (PCRE-флаг (?m) → флаг m в JS).
// Запуск: node scripts/check-crypto-bans.mjs (задача semgrep в ci.yml).
import { readFileSync } from 'node:fs';

const deny = readFileSync('deny.toml', 'utf8');
const rules = readFileSync('.semgrep/rules.yml', 'utf8');

// Имена из [bans].deny. Разбираем секцию и каждую запись отдельно: запись с другим порядком
// полей, переносом строки, лишним полем (wrappers) или иным текстом причины раньше молча
// выпадала из сверки, и CI писал «OK» (находка ревью PR #11). Неразобранная запись — ошибка.
function denyNames(src) {
  const lines = src.split('\n');
  const start = lines.indexOf('[bans]');
  if (start < 0) throw new Error('deny.toml: нет секции [bans]');
  let end = lines.findIndex((l, i) => i > start && l.startsWith('['));
  if (end < 0) end = lines.length;
  const section = lines.slice(start + 1, end).join('\n');
  const arr = section.match(/^deny\s*=\s*\[([\s\S]*?)^\]/m);
  if (!arr) throw new Error('deny.toml: нет массива [bans].deny');
  const body = arr[1].replace(/#[^\n]*/g, '');
  const found = [];
  let rest = body;
  for (const entry of body.match(/\{[^{}]*\}/g) ?? []) {
    const name = entry.match(/\bname\s*=\s*"([^"]+)"/);
    const reason = entry.match(/\breason\s*=\s*"([^"]*)"/);
    if (!name) throw new Error(`deny.toml: запись без name: ${entry.trim()}`);
    if (!reason?.[1].includes('ADR-1')) {
      throw new Error(`deny.toml: ${name[1]}: причина не ADR-1: ${entry.trim()}`);
    }
    found.push(name[1]);
    rest = rest.replace(entry, '');
  }
  const left = rest.replace(/[\s,]+/g, ' ').trim();
  if (left) throw new Error(`deny.toml: не разобрана часть [bans].deny: ${left.slice(0, 80)}`);
  return found;
}

const names = denyNames(deny);

function ruleRegex(id) {
  const block = rules.split(/\n  - id: /).find((b) => b.startsWith(id + '\n'));
  if (!block) throw new Error(`нет правила ${id}`);
  const m = block.match(/\n    pattern-regex: '((?:[^']|'')*)'\n/);
  if (!m) throw new Error(`нет pattern-regex в ${id}`);
  let src = m[1].replaceAll("''", "'");
  let flags = 'u';
  if (src.startsWith('(?m)')) { src = src.slice(4); flags += 'm'; }
  return new RegExp(src, flags);
}

const toml = ruleRegex('sm-no-crypto-dependency');
const rust = ruleRegex('sm-no-own-crypto');

const tomlForms = (n) => [
  `${n} = "1"`,
  `"${n}" = "1"`,
  `${n}.version = "1"`,
  `${n}."git" = "https://example.org/x"`,
  `${n}.workspace = true`,
  `[dependencies.${n}]`,
  `[target.'cfg(unix)'.dev-dependencies.${n}]`,
  `dependencies.${n} = "1"`,
  `dependencies = { ${n} = "1" }`,
  `dev-dependencies = { serde = "1", ${n} = { version = "1" } }`,
  `target.'cfg(unix)'.dependencies = { "${n}" = "1" }`,
  `workspace = { dependencies = { ${n} = "1" } }`,
  `x = { package = "${n}", version = "1" }`,
];
const rustForms = (n) => {
  const id = n.replaceAll('-', '_');
  return [`use ${id}::X;`, `use ::${id};`, `extern crate ${id};`, `let k = ${id}::new();`];
};
// Не криптография: похожие имена и обычные строки манифеста.
const tomlOk = [
  'ringbuf = "1"', 'serde = "1"', 'zeroize = { version = "1" }', 'description = "sha2 и ring не используем"',
  'dependencies = { serde = { version = "1", features = ["sha2", "ring"] } }', 'name = "sm-core"',
  'digestive = "1"', 'signatures = "1"',
];
const rustOk = ['use std::io::Read;', 'let ring_buf = 1;', 'let signature = sig;', 'fn digest() {}', 'use sm_core::wire;'];

const errors = [];
if (names.length < 50) errors.push(`deny.toml: подозрительно мало имён (${names.length})`);
for (const n of names) {
  for (const s of tomlForms(n)) if (!toml.test(s)) errors.push(`sm-no-crypto-dependency не ловит: ${s}`);
  for (const s of rustForms(n)) if (!rust.test(s)) errors.push(`sm-no-own-crypto не ловит: ${s}`);
}
for (const s of tomlOk) if (toml.test(s)) errors.push(`sm-no-crypto-dependency ложно ловит: ${s}`);
for (const s of rustOk) if (rust.test(s)) errors.push(`sm-no-own-crypto ложно ловит: ${s}`);

if (errors.length) {
  console.error(errors.slice(0, 40).join('\n'));
  console.error(`Ошибок: ${errors.length}`);
  process.exit(1);
}
console.log(`OK: ${names.length} крейтов, deny.toml и оба правила semgrep согласованы`);
