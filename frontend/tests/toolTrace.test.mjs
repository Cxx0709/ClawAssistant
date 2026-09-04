import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import ts from 'typescript';

const require = createRequire(import.meta.url);
function load(relativePath) {
  const source = readFileSync(new URL(relativePath, import.meta.url), 'utf8');
  const { outputText } = ts.transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
  });
  const exports = {};
  new Function('require', 'exports', outputText)((name) =>
    name === '../lib/format' ? load('../src/lib/format.ts') : require(name), exports);
  return exports;
}
const ToolTrace = load('../src/components/ToolTrace.tsx').default;
const render = (props) => renderToStaticMarkup(React.createElement(ToolTrace, {
  tools: [], skills: [], running: true, open: false, onToggle() {}, ...props,
}));

test('routing alone never displays a skill or invents an image recognition call', () => {
  for (const running of [true, false]) {
    assert.equal(render({ skills: ['campus'], running }), '');
    assert.equal(render({ skills: ['common'], running }), '');
  }
});

test('tool history determines badges independently of stale routing events', () => {
  const markup = render({
    skills: ['campus'],
    tools: [{ id: '1', name: 'weather_query', skill: 'weather', state: 'ok' }],
  });
  assert.ok(markup.includes('weather'));
  assert.ok(!markup.includes('campus'));
});

test('common tools do not display an unrelated professional skill', () => {
  const markup = render({
    skills: ['campus'],
    tools: [{ id: '1', name: 'memory_manage', skill: 'common', state: 'ok' }],
  });
  assert.ok(markup.includes('memory manage'));
  assert.ok(!markup.includes('campus'));
});
