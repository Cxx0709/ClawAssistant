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
    name === '../lib/format' || name === './format' ? load('../src/lib/format.ts')
      : name === '../lib/execution' ? load('../src/lib/execution.ts')
      : name === '../components/ToolTrace' ? load('../src/components/ToolTrace.tsx') : require(name), exports);
  return exports;
}
const ToolTrace = load('../src/components/ToolTrace.tsx').default;
const { executionRecords, executionLabel } = load('../src/lib/execution.ts');
const Radar = load('../src/pages/AgentRadarPage.tsx').default;
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
  assert.ok(markup.includes('管理记忆'));
  assert.ok(!markup.includes('campus'));
});

test('terminated runs do not leave unfinished tools spinning', () => {
  const markup = render({ running: false, open: true,
    tools: [{ id: '1', name: 'weather_query', skill: 'weather', state: 'running' }],
  });
  assert.ok(markup.includes('未完成'));
  assert.ok(!markup.includes('animate-spin'));
  assert.equal(executionLabel({ status: 'FAILED', streaming: true }), '执行失败');
  assert.equal(executionLabel({ status: 'CANCELLED', streaming: true }), '已取消');
  assert.equal(executionLabel({ status: 'COMPLETED', tools: [{ state: 'err' }] }), '已完成 · 有工具失败');
});

test('radar pairs requests with replies without inventing records or missing questions', () => {
  const messages = [
    { id: 'old', role: 'assistant', content: 'older reply' },
    { id: 'u1', role: 'user', content: '查看天气' },
    { id: 'a1', role: 'assistant', content: '天气结果', status: 'COMPLETED' },
    { id: 'u2', role: 'user', content: '待发送结果的请求' },
  ];
  const records = executionRecords(messages);
  assert.equal(records.length, 2);
  assert.equal(records[0].request, '查看天气');
  assert.match(records[1].request, /更早消息/);
  assert.deepEqual(executionRecords([]), []);
});

test('radar distinguishes empty, loading and failed requests', () => {
  const renderRadar = (props = {}) => renderToStaticMarkup(React.createElement(Radar, {
    messages: [], conversationTitle: '测试会话', loading: false, error: '', canRefresh: true,
    hasOlder: false, loadingOlder: false, onRefresh() {}, onLoadOlder() {}, onBack() {}, ...props,
  }));
  assert.match(renderRadar(), /还没有执行记录/);
  const failed = renderRadar({ error: '请求失败（HTTP 500）' });
  assert.match(failed, /HTTP 500/);
  assert.ok(!failed.includes('还没有执行记录'));
  const loading = renderRadar({ loading: true });
  assert.match(loading, /正在读取执行记录/);
  assert.ok(!loading.includes('还没有执行记录'));
  assert.ok(!renderRadar().includes('travel-planner'));
});
