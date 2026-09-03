# 分支合并操作流程

> 面向组员和 Claude 的标准化合并流程，**严格按顺序执行**。
>
> ⚠️ **第一条规则（覆盖以下所有内容）**：
> 所有代码差异——包括对方新增、修改、删除、配置变更——**Claude 必须先向人汇报，由人做出取舍决策**，之后才能动手执行。Claude 可以给出推荐方案并说明理由，但不能擅自决定。

---

## 一、建立基线

```bash
# 1. 切到目标分支
git checkout <your-branch>        # 你想合入的分支

# 2. 确保工作区干净
git status --short                # 应为空

# 3. 获取远端分支
git fetch origin <source-branch>  # 来源分支
```

---

## 二、全面比对（不要直接 merge）

先看全貌，再决定合并方案。

### 2.1 整体概览

```bash
# 新增/修改/删除了哪些文件
git diff --stat <your-branch>..origin/<source-branch>

# commit 历史
git log --oneline <your-branch>..origin/<source-branch>
```

### 2.2 逐文件审查（重点关注）

对以下类型的文件做逐行 diff：

```bash
# 所有 Java 文件
git diff <your-branch>..origin/<source-branch> -- src/main/java/

# 资源配置
git diff <your-branch>..origin/<source-branch> -- src/main/resources/

# 构建配置
git diff <your-branch>..origin/<source-branch> -- pom.xml

# git 忽略规则
git diff <your-branch>..origin/<source-branch> -- .gitignore
```

### 2.3 标注三类差异

| 类型 | 标注 | 举例 |
|------|------|------|
| **新增** | 🟢 完全新增的文件或配置 | 新模块、新工具类 |
| **修改** | 🟡 双方都改过的文件 | 同一方法的逻辑调整 |
| **删除** | 🔴 被删除的文件 | 废弃功能的清理 |

---

## 三、方案确认（Claude 不可擅自决策）

**所有差异——不管是新增、修改还是删除——Claude 都必须逐条向人汇报，由人来决定取舍方案。**

先将比对结果整理成表格，让人一目了然：

```
| 类型 | 文件 | 差异说明 | 候选方案 |
|------|------|---------|---------|
| 🟢 新增 | SearchService.java | 新的 Tavily 搜索服务 | 要/不要 |
| 🟡 修改 | LLMClient.java | timeout 30→60 | 改/不改 |
| 🔴 删除 | TimeFunction.java | 时间工具被删除 | 跟删/保留 |
```

### 3.1 新增型差异

> 对方加了新文件/新配置

Claude 需要说明：

- 这个新功能是什么？依赖了哪些外部服务？（是否需要新 API Key / 新 Maven 依赖）
- 接入成本：需要配哪些参数？
- **提问示例**："WebSearch 模块需要 Tavily API Key，是否要合并进来？"

### 3.2 冲突型差异

> 两边改了同一块代码

Claude 需要：
1. 分别展示两边代码的逻辑和意图
2. 给出推荐方案（含理由）
3. **让人拍板选哪边，还是两边手动拼接**

### 3.3 删除型差异

> 对方删了这个文件，我们要不要跟着删？

Claude 的必查清单：

- [ ] 文件在生产代码中还有引用吗？（`grep -r` 搜一下）
- [ ] 是 `@Deprecated` 标记的废弃代码吗？
- [ ] 删除后编译能过吗？
- [ ] 如果依赖的对象在另一个包里，不要重复了？

### 3.4 配置型差异

> `application.properties` / `.gitignore` / `pom.xml`

- 重复的配置段需去重——让人类决定保留哪段
- 版本号冲突、依赖增删——展示两边差异，让人决定
- `.gitignore` 增删——说明影响，让人决定

### 3.5 提问规范

每轮提问格式：

```
## 文件：xxx.java

**差异类型**：🟡 两边都修改

**我方代码**：（展示核心逻辑）
**对方代码**：（展示核心逻辑）

**冲突点**：xxx
**推荐方案**：xxx — 理由：xxx

要按推荐方案处理吗？
```

可以一次性把多个决策点汇总问人，**但每个差异点都要明确让人做选择**，不能跳过。

---

## 四、执行合并

> 只有在人完成 **所有决策** 后，才进入执行阶段。

### 4.1 执行 merge

```bash
git merge origin/<source-branch>
```

### 4.2 解决冲突（3 种情况）

#### 情况 A：两边都改 → 有冲突（UU 状态）

```bash
# 查看全部冲突文件
git status --short | grep '^UU'
```

对每个冲突文件：

1. **理解两边都在做什么改动**
2. **回想之前的人的决策**（第三章已确认的方案）
3. **编辑文件解决冲突段**：
   ```
   <<<<<<< HEAD
   （我方的代码）
   =======
   （对方的代码）
   >>>>>>> origin/<branch>
   ```
   → 删掉标记线，按人的决策留下正确代码
4. **标记已解决**：
   ```bash
   git add <file>
   ```

#### 情况 B：对方删除 / 我方没改 → 已有决策决定是否跟删

- 人在第三章已经决定"跟删" → 无需额外操作，git 自动标记 `D`
- 人在第三章已经决定"保留" → 需要执行：
  ```bash
  git checkout --ours <file>
  git add <file>
  ```

#### 情况 C：对方删除 / 我方改了 → 已有决策决定取舍

- 人在第三章已经决定"保留我方" → `git add <file>` 保留我方版本
- 人在第三章已经决定"跟删" → 接受删除

### 4.3 审查自动合并结果（M 状态）

有些文件 git 能自动合并成功（没有冲突标记），**但两边逻辑加在一起可能有重复或矛盾**，不能因为没冲突就跳过审查。

例如 `LLMClient.java` 的自动合并结果可能变成：

```java
// 外层也写 reasoning_content
node.put("reasoning_content", msg.reasoningContent());
// 内层 tool_call 又写一次
if (msg.isToolCall()) {
    node.put("reasoning_content", msg.reasoningContent());
}
```

→ 对照已确认的方案，手动调整。

### 4.4 执行额外约定

合并完成后，做提交前约定的清理：

- 删除僵尸接口/类（需在第三章已确认）
- 去重配置（需在第三章已确认）
- 调整包路径（需在第三章已确认）
- 更新注释（需在第三章已确认）

---

## 五、编译验证（必须通过）

```bash
mvn clean compile
# 如果有测试
mvn test
```

**编译失败时禁止提交。**

---

## 六、提交

```bash
git add -A
```

提交信息模板：

```
chore: merge <source-branch> — <主要功能概括>

新功能：
- xxx：xxx 模块（xxx技术栈）
- xxx：xxx 配置新增

保留（本地分支原有功能不受影响）：
- xxx 机制保持不变
- xxx 参数值保持不变

修复（合并冲突解决）：
- xxx: 这里改了啥逻辑，为什么
- xxx: 去重/合并了什么

清理：
- 删除 xxx（僵尸代码/冗余配置）
- 去重 xxx 配置段
```

---

## 七、两次检查清单

提交后验证：

- [ ] `git log --oneline -3` 确认 commit 在正确分支上
- [ ] `mvn clean compile` 通过（最终确认）
- [ ] `git diff HEAD~1 --stat` 确认修改范围合理（没有误改不该动的文件）
- [ ] 需要协调通知其他人吗？（比如删了他们引用的 API）

---

## CLI 速查卡

```bash
# 查看完整 diff
git diff <branch>..origin/<branch> -- <path>

# 看合并基础点
git merge-base HEAD origin/<branch>

# 看"从合并基础点到当前分支"的改动
git diff <merge-base>..HEAD -- <file>

# 看"从合并基础点到对方分支"的改动
git diff <merge-base>..origin/<branch> -- <file>
```

---

> **关键原则**：先分析，再决策，最后执行。
>
> **Claude 的铁律**：所有差异（新增/修改/删除/配置）都必须报告给人，等人拍板后再动手。Claude 可以给推荐方案，但不能替人决定。不要在合并过程中临时做决定，不要因为"看起来没冲突"就跳过人的决策。
