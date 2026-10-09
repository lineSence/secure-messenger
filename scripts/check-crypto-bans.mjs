#!/usr/bin/env node
// Сверка запретов криптопримитивов (ADR-1): deny.toml [bans].deny и два правила .semgrep/rules.yml
// должны покрывать одни и те же крейты. Для каждого имени проверяются формы объявления
// зависимости в Cargo.toml и формы использования в Rust; обычные крейты не должны ловиться.
// Регулярки берутся из rules.yml как есть (PCRE-флаг (?m) → флаг m в JS).
// Запуск: node scripts/check-crypto-bans.mjs (задача semgrep в ci.yml).
import { readFileSync } from 'node:fs';

const deny = readFileSync('deny.toml', 'utf8');
const rules = readFileSync('.semgrep/rules.yml', 'utf8');

const names = [...deny.matchAll(/\{ name = "([^"]+)", reason = "ADR-1" \}/g)].map((m) => m[1]);

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
