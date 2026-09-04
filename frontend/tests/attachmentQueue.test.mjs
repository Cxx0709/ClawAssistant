import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import ts from 'typescript';

// Use the project's TypeScript compiler without adding a test framework.
const source = await readFile(new URL('../src/lib/attachmentQueue.ts', import.meta.url), 'utf8');
const { outputText } = ts.transpileModule(source, {
  compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
});
const { AttachmentQueue, MAX_ATTACHMENT_BYTES } = await import(
  `data:text/javascript;base64,${Buffer.from(outputText).toString('base64')}`
);

const file = (name, type = 'text/plain') => new File(['content'], name, { type, lastModified: 42 });
const result = (name) => ({ id: name, fileName: name, kind: 'FILE', mimeType: 'text/plain', size: 7, url: `/api/artifacts/${name}` });
const settle = () => new Promise((resolve) => setImmediate(resolve));
function controlledQueue(t) {
  const requests = [];
  const queue = new AttachmentQueue((input, signal) => new Promise((resolve, reject) => {
    requests.push({ input, signal, resolve, reject });
  }));
  t.after(() => queue.dispose());
  return { queue, requests };
}

test('consecutive batches reserve slots immediately and keep selection order', async (t) => {
  const { queue, requests } = controlledQueue(t);
  queue.add('a', [file('first.txt')]);
  queue.add('a', [file('second.txt')]);
  assert.equal(requests.length, 2);
  assert.equal(queue.ready('a'), null);
  requests[1].resolve(result('second.txt'));
  await settle();
  assert.equal(queue.ready('a'), null);
  requests[0].resolve(result('first.txt'));
  await settle();
  assert.deepEqual(queue.ready('a').map((entry) => entry.id), ['first.txt', 'second.txt']);
});

test('partial failure preserves successful files and retries only the failed file', async (t) => {
  const { queue, requests } = controlledQueue(t);
  queue.add('a', [file('good.txt'), file('retry.txt')]);
  requests[0].resolve(result('good.txt'));
  requests[1].reject(new Error('network unavailable'));
  await settle();
  assert.deepEqual(queue.snapshot().items.map((item) => item.status), ['ready', 'error']);
  assert.equal(queue.ready('a'), null);
  const failedId = queue.snapshot().items[1].id;
  queue.retry(failedId);
  queue.retry(failedId);
  assert.equal(requests.length, 3);
  requests[2].resolve(result('retry.txt'));
  await settle();
  assert.equal(queue.ready('a').length, 2);
});

test('removing an upload aborts its request and ignores late completion', async (t) => {
  const { queue, requests } = controlledQueue(t);
  queue.add('a', [file('old.txt')]);
  const oldId = queue.snapshot().items[0].id;
  queue.remove(oldId);
  assert.equal(requests[0].signal.aborted, true);
  queue.add('a', [file('new.txt')]);
  requests[0].resolve(result('old.txt'));
  await settle();
  assert.deepEqual(queue.snapshot().items.map((item) => item.file.name), ['new.txt']);
  assert.equal(queue.ready('a'), null);
});

test('conversation uploads and clearing are isolated, including late responses', async (t) => {
  const { queue, requests } = controlledQueue(t);
  queue.add('a', [file('a.txt')]);
  queue.add('b', [file('b.txt')]);
  requests[1].resolve(result('b.txt'));
  await settle();
  assert.equal(queue.ready('a'), null);
  assert.deepEqual(queue.ready('b').map((item) => item.id), ['b.txt']);
  queue.clear('a');
  requests[0].resolve(result('a.txt'));
  await settle();
  assert.deepEqual(queue.ready('a'), []);
  assert.equal(queue.ready('b').length, 1);
});

test('duplicates, empty files, oversized files and the ninth attachment are rejected', (t) => {
  const { queue, requests } = controlledQueue(t);
  const duplicate = file('same.txt');
  const oversized = new File([new Uint8Array(MAX_ATTACHMENT_BYTES + 1)], 'large.bin');
  queue.add('a', [new File([], 'empty.txt'), oversized, duplicate, duplicate]);
  assert.equal(requests.length, 1);
  assert.match(queue.snapshot().errors.a, /文件为空/);
  assert.match(queue.snapshot().errors.a, /25 MB/);
  assert.match(queue.snapshot().errors.a, /重复上传/);
  queue.add('a', Array.from({ length: 8 }, (_, i) => file(`${i}.txt`)));
  assert.equal(queue.snapshot().items.length, 8);
  assert.equal(requests.length, 8);
  assert.match(queue.snapshot().errors.a, /最多添加 8/);
  queue.add('b', [duplicate]);
  assert.equal(requests.length, 9);
});

test('image preview exists before upload completes and is released on removal', async (t) => {
  const { queue } = controlledQueue(t);
  queue.add('a', [file('image.png', 'image/png')]);
  const item = queue.snapshot().items[0];
  assert.match(item.preview, /^blob:/);
  assert.equal((await fetch(item.preview)).status, 200);
  queue.remove(item.id);
  await assert.rejects(fetch(item.preview));
});
