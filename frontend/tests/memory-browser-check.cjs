/* Run with PLAYWRIGHT_MODULE pointing to an installed Playwright package.
   Uses mocked HTTP fixtures only; never edits the running application's data. */
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');

(async () => {
  const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || 'msedge' });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  const makeMemory = (id, content, category = 'PREFERENCE', disabled = false) => ({
    id, content, category, disabled, evidence: content, source: 'AUTO', sourceConversationId: 'source-chat',
    createdAt: '2026-08-28T08:00:00Z', updatedAt: new Date().toISOString(),
  });
  let items = [
    makeMemory('memory-1', '喜欢安静、游客较少的旅行地点'),
    makeMemory('memory-2', '正在学习 Java，希望解释时多举例', 'RULE'),
    makeMemory('memory-3', '每周计划跑步三次', 'GOAL'),
    makeMemory('memory-4', '通常晚上有空学习', 'FACT', true),
  ];
  let failWrite = false;
  let failRead = false;
  let receipts = [];
  let undoCount = 0;
  const conversations = [{ id: 'source-chat', title: '旅行安排', createdAt: new Date().toISOString() }];
  await page.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = url.pathname;
    const method = request.method();
    const reply = (json, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) });
    if (pathname === '/api/auth/setup-status') return reply({ setupRequired: false });
    if (pathname === '/api/auth/me') return reply({ id: 'test-user', username: 'test', displayName: '测试用户' });
    if (pathname === '/api/auth/csrf') return reply({ token: 'test-csrf', headerName: 'X-XSRF-TOKEN' });
    if (pathname === '/api/memories/changes') return reply(receipts);
    if (pathname.endsWith('/undo')) { undoCount++; receipts = []; return reply({ undone: true }); }
    if (pathname === '/api/memories' && method === 'GET') return reply(failRead ? {} : { items, enabled: true }, failRead ? 503 : 200);
    if (pathname === '/api/memories' && method === 'POST') {
      assert.equal(request.headers()['x-xsrf-token'], 'test-csrf');
      const body = request.postDataJSON();
      const created = { ...makeMemory('new-memory', body.content, body.category), source: 'MANUAL', sourceConversationId: null };
      items.unshift(created); return reply(created, 201);
    }
    if (pathname.startsWith('/api/memories/') && method === 'PUT') {
      if (failWrite) { failWrite = false; return reply({}, 503); }
      const id = pathname.split('/').pop();
      const index = items.findIndex(item => item.id === id);
      const body = request.postDataJSON();
      assert.equal(body.expectedUpdatedAt, items[index].updatedAt);
      items[index] = { ...items[index], ...body, updatedAt: new Date().toISOString() };
      return reply(items[index]);
    }
    if (pathname.startsWith('/api/memories/') && method === 'DELETE') {
      const id = pathname.split('/').pop();
      assert.equal(url.searchParams.get('expectedUpdatedAt'), items.find(item => item.id === id).updatedAt);
      items = items.filter(item => item.id !== id); return reply({ deleted: true });
    }
    if (pathname === '/api/webchat/conversations' && method === 'POST') {
      const created = { id: 'new-chat', title: '新对话', createdAt: new Date().toISOString() };
      conversations.unshift(created); return reply(created);
    }
    if (pathname === '/api/webchat/conversations') {
      return reply({ items: url.searchParams.get('archived') === 'true' || url.searchParams.get('deleted') === 'true' ? [] : conversations, nextCursor: null });
    }
    if (pathname.endsWith('/messages')) return reply({ items: [], nextCursor: null });
    if (pathname === '/api/webchat/status') return reply({ appReady: true });
    return reply([]);
  });
  const output = path.resolve(__dirname, '../../target/memory-qa');
  await fs.mkdir(output, { recursive: true });
  const list = page.getByRole('region', { name: '记忆列表' });
  const detail = page.getByRole('complementary', { name: '记忆详情' });
  await page.goto('http://127.0.0.1:5173/?memories');
  await page.getByRole('heading', { name: '助手记住的我' }).waitFor();
  await list.getByRole('button', { name: /喜欢安静/ }).click();
  await page.screenshot({ path: path.join(output, 'desktop.png'), fullPage: true });
  await page.getByLabel('搜索记忆', { exact: true }).fill('不存在的关键词');
  await page.getByRole('heading', { name: '没有找到相关记忆' }).waitFor();
  await page.getByLabel('搜索记忆', { exact: true }).fill('');
  await detail.getByRole('button', { name: '修改', exact: true }).click();
  await page.getByLabel('记忆内容', { exact: true }).fill('喜欢安静，且交通方便的旅行地点');
  await page.getByRole('button', { name: '保存记忆', exact: true }).click();
  await detail.getByRole('heading', { name: '喜欢安静，且交通方便的旅行地点' }).waitFor();
  assert.equal(items.find(item => item.id === 'memory-1').content, '喜欢安静，且交通方便的旅行地点');
  await detail.getByRole('button', { name: '暂时停用', exact: true }).click();
  await detail.getByRole('button', { name: '恢复使用', exact: true }).waitFor();
  assert.equal(await detail.getByRole('button', { name: /用这条记忆聊/ }).isDisabled(), true);
  await detail.getByRole('button', { name: '恢复使用', exact: true }).click();
  await detail.getByRole('button', { name: '暂时停用', exact: true }).waitFor();
  await page.getByRole('button', { name: '＋ 添加记忆', exact: true }).click();
  await page.getByLabel('记忆内容', { exact: true }).fill('咖啡只喝中杯');
  await page.getByRole('button', { name: '保存记忆', exact: true }).click();
  await detail.getByRole('heading', { name: '咖啡只喝中杯' }).waitFor();
  failWrite = true;
  await detail.getByRole('button', { name: '修改', exact: true }).click();
  await page.getByLabel('记忆内容', { exact: true }).fill('这次保存应当失败');
  await page.getByRole('button', { name: '保存记忆', exact: true }).click();
  await page.getByRole('alert').filter({ hasText: '记忆服务暂时不可用' }).waitFor();
  assert.equal(items.find(item => item.id === 'new-memory').content, '咖啡只喝中杯');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await detail.getByRole('button', { name: '删除', exact: true }).click();
  await detail.getByRole('button', { name: '确认删除', exact: true }).click();
  await list.getByRole('button', { name: /喜欢安静/ }).waitFor();
  assert.equal(items.length, 4);

  await page.setViewportSize({ width: 390, height: 844 });
  await list.getByRole('button', { name: /正在学习 Java/ }).click();
  await detail.getByRole('heading', { name: /正在学习 Java/ }).waitFor();
  await page.screenshot({ path: path.join(output, 'mobile.png'), fullPage: true });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
  await detail.getByRole('button', { name: '← 返回记忆列表' }).click();
  await list.getByRole('button', { name: /喜欢安静/ }).click();
  receipts = [{ id: 42, action: 'ADDED', memory: items[0] }];
  await detail.getByRole('button', { name: /用这条记忆聊/ }).click();
  await page.waitForURL(/conversation=new-chat/);
  await page.waitForFunction(() => document.querySelector('textarea')?.value.includes('结合这条关于我的信息'));
  await page.getByRole('button', { name: '撤销', exact: true }).click();
  await page.waitForFunction(() => !document.body.textContent.includes('已记住：'));
  assert.equal(undoCount, 1);

  failRead = true;
  await page.goto('http://127.0.0.1:5173/?memories');
  await page.getByRole('alert').waitFor();
  assert.equal(await page.getByRole('heading', { name: '从一件小事，开始了解你' }).count(), 0);
  failRead = false; items = [];
  await page.getByRole('button', { name: '重新读取' }).click();
  await page.getByRole('heading', { name: '从一件小事，开始了解你' }).waitFor();
  assert.deepEqual(errors, []);
  await browser.close();
  console.log('PASS: browse, search, edit, pause/resume, create, failed write, delete, mobile layout, chat draft, receipt undo, outage and empty state');
  console.log('Screenshots: ' + output);
})().catch(error => { console.error(error); process.exit(1); });
