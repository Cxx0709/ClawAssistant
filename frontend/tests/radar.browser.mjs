// Start Vite first. Set PLAYWRIGHT_MODULE to an installed Playwright package if needed.
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const browser = await chromium.launch({ headless: true, channel: process.env.BROWSER_CHANNEL || 'chrome' });
const page = await browser.newPage({ viewport: { width: 1366, height: 768 } });
const errors = [];
page.on('pageerror', error => errors.push(error.message));
const origin = process.env.TEST_ORIGIN || 'http://127.0.0.1:5176';
const now = Date.now();
const conversations = ['a', 'b'].map(id => ({ id, title: `会话 ${id}`, pinned: false, archived: false,
  lastMessagePreview: '', createdAt: now, updatedAt: now }));
const data = {
  a: Array.from({ length: 16 }, (_, i) => [
    { id: `u${i}`, role: 'user', content: `查询天气 ${i}`, status: 'COMPLETED', createdAt: now },
    { id: `a${i}`, runId: `r${i}`, role: 'assistant', content: '这是天气查询的执行结果。',
      status: i === 15 ? 'FAILED' : 'COMPLETED', createdAt: now, totalMs: 1500,
      errorText: i === 15 ? '天气服务暂时不可用' : '',
      tools: [{ id: 'tool', name: 'weather_query', skill: 'weather', state: i === 15 ? 'err' : 'ok',
        durationMs: 900, detail: i === 15 ? '天气服务暂时不可用' : '' }] },
  ]).flat(),
  b: [],
};
let failMessages = false;
let documents = 0;
page.on('request', request => { if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documents++; });
await page.route('**/api/**', async route => {
  const url = new URL(route.request().url());
  const path = url.pathname;
  let body = [];
  if (path === '/api/auth/setup-status') body = { setupRequired: false };
  else if (path === '/api/auth/me') body = { id: 'test', username: 'test', displayName: '测试用户' };
  else if (path === '/api/webchat/status') body = { appReady: true, activeGoalCount: 0 };
  else if (path === '/api/webchat/conversations') body = {
    items: url.searchParams.get('archived') === 'true' || url.searchParams.get('deleted') === 'true' ? [] : conversations,
  };
  else if (path.endsWith('/messages')) {
    if (failMessages) return route.fulfill({ status: 500, json: { error: 'test failure' } });
    body = { items: data[path.split('/').at(-2)] || [], nextCursor: null };
  } else if (path.startsWith('/api/webchat/runs/')) {
    body = data.a.find(message => message.runId === path.split('/').at(-1));
  }
  await route.fulfill({ json: body });
});
try {
  await page.goto(`${origin}/?conversation=a`);
  await page.locator('textarea').fill('还没发送的草稿');
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  const scroll = page.locator('main > div').first();
  await scroll.evaluate(el => { el.scrollTop = 100; });
  const scrollBefore = await scroll.evaluate(el => el.scrollTop);
  const docsBefore = documents;
  await page.getByRole('button', { name: '查看当前会话执行记录' }).click();
  const dialog = page.getByRole('dialog', { name: 'Agent 雷达' });
  await dialog.waitFor();
  await dialog.getByRole('button', { name: /查询天气 15/ }).click();
  assert.match(await dialog.textContent(), /天气服务暂时不可用/);
  await dialog.getByRole('button', { name: '返回聊天' }).click();
  await dialog.waitFor({ state: 'hidden' });
  await page.waitForURL(url => !url.searchParams.has('radar'));
  assert.equal(await page.locator('textarea').inputValue(), '还没发送的草稿');
  assert.equal(await scroll.evaluate(el => el.scrollTop), scrollBefore);
  assert.equal(documents, docsBefore);
  console.log('PASS radar navigation preserves draft, scroll, and document');

  await page.getByRole('button', { name: '查看当前会话执行记录' }).click();
  await page.goBack();
  await dialog.waitFor({ state: 'hidden' });
  await page.goForward();
  await dialog.waitFor();
  await page.keyboard.press('Escape');
  await dialog.waitFor({ state: 'hidden' });
  console.log('PASS browser back/forward and Escape');

  await page.getByRole('button', { name: '查看当前会话执行记录' }).click();
  failMessages = true;
  await dialog.getByRole('button', { name: '刷新记录' }).click();
  await dialog.getByRole('alert').waitFor();
  assert.match(await dialog.getByRole('alert').textContent(), /HTTP 500/);
  failMessages = false;
  await dialog.getByRole('button', { name: '刷新记录' }).click();
  await dialog.getByRole('alert').waitFor({ state: 'hidden' });
  await dialog.getByRole('button', { name: /查询天气 15/ }).waitFor();
  console.log('PASS request failure and refresh recovery');

  await page.setViewportSize({ width: 375, height: 812 });
  assert.equal(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth), true);
  await dialog.getByRole('button', { name: /查询天气 15/ }).click();
  assert.equal(await dialog.evaluate(el => el.scrollWidth <= el.clientWidth), true);
  console.log('PASS mobile radar width and expanded details');

  await page.goto(`${origin}/?conversation=b&radar`);
  await page.getByText('还没有执行记录', { exact: true }).waitFor();
  assert.ok(!(await dialog.textContent()).includes('查询天气 15'));
  await dialog.getByRole('button', { name: '返回聊天' }).click();
  await page.waitForURL(url => url.searchParams.get('conversation') === 'b' && !url.searchParams.has('radar'));
  console.log('PASS direct radar link, empty conversation, and return');

  data.a = [
    { id: 'live-user', role: 'user', content: '正在查询的真实任务', status: 'COMPLETED', createdAt: now },
    { id: 'live-reply', role: 'assistant', content: '', status: 'STREAMING', createdAt: now, runId: 'live-run',
      tools: [{ id: 'live-tool', name: 'weather_query', skill: 'weather', state: 'running' }] },
  ];
  await page.goto(`${origin}/?conversation=a&radar`);
  await dialog.getByText('正在查询天气 · 等待结果', { exact: true }).waitFor();
  await dialog.getByRole('button', { name: /正在查询的真实任务/ }).click();
  assert.equal(await dialog.getByRole('button', { name: '刷新记录' }).isDisabled(), true);
  data.a[1] = { ...data.a[1], status: 'COMPLETED', content: '已获取最新天气', totalMs: 1200,
    tools: [{ ...data.a[1].tools[0], state: 'ok', durationMs: 1000 }] };
  await dialog.getByText('已获取最新天气', { exact: true }).waitFor();
  assert.equal(await dialog.getByRole('button', { name: '刷新记录' }).isEnabled(), true);
  await dialog.getByRole('button', { name: '返回聊天' }).click();
  await page.getByText('已获取最新天气', { exact: true }).waitFor();
  console.log('PASS active execution completes in both radar and chat');
  assert.deepEqual(errors, []);
} finally {
  await browser.close();
}
