import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import ts from 'typescript';

const source = await readFile(new URL('../src/lib/documentPreview.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
});
const { normalizeDocumentPreview } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
);

test('removes extractor whitespace before and after document content', () => {
  const extracted = '\r\n\r\n\u00a0\n\u200b\nJava 基础入门指南\r\n正文\r\n\r\n';
  assert.equal(normalizeDocumentPreview(extracted), 'Java 基础入门指南\n正文');
});

test('keeps paragraph breaks but collapses excessive blank lines', () => {
  const extracted = '第一段\n\n\n\n第二段\n  缩进内容  ';
  assert.equal(normalizeDocumentPreview(extracted), '第一段\n\n第二段\n  缩进内容');
});

test('returns an empty string for whitespace-only extraction', () => {
  assert.equal(normalizeDocumentPreview('\n\t\u200b\u00a0\n'), '');
});
