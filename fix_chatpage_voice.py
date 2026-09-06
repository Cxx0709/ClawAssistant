with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\ChatPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 在 import 部分加 synthesizeRoleVoice（找到 fetchRoles 那行的 import）
old_import = "import { fetchRoles, createRole, updateRole, deleteRole, createConversation, updateConversationRole } from '../lib/api';"
new_import = "import { fetchRoles, createRole, updateRole, deleteRole, createConversation, updateConversationRole, synthesizeRoleVoice } from '../lib/api';"
if old_import in content:
    content = content.replace(old_import, new_import)
    print('Added synthesizeRoleVoice import')
else:
    print('Import line not found, trying alternative...')
    # 尝试找其他 import 模式
    import re
    content = re.sub(
        r"import \{ ([^}]*fetchRoles[^}]*) \} from '\.\./lib/api';",
        lambda m: "import { " + m.group(1) + ", synthesizeRoleVoice } from '../lib/api';",
        content
    )
    print('Added synthesizeRoleVoice import via regex')

# 2. 在 AI 消息的 Markdown 下面加播放按钮
old_markdown = """            {m.streaming && !m.content && !m.errorText ? (
              <TypingDots action={executionLabel(m)} />
            ) : (
              m.content && <Markdown content={m.content} />
            )}"""
new_markdown = """            {m.streaming && !m.content && !m.errorText ? (
              <TypingDots action={executionLabel(m)} />
            ) : (
              m.content && (
                <div>
                  <Markdown content={m.content} />
                  <button
                    onClick={() => playVoice(m.content, currentConv?.roleId)}
                    className="mt-2 inline-flex items-center gap-1.5 rounded-full border border-[#e0d8f0] bg-[#f8f5ff] px-3 py-1.5 text-xs text-[#6c5ce7] hover:bg-[#efe9ff] transition-colors"
                    title="语音播放"
                  >
                    <svg viewBox="0 0 24 24" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <polygon points="11 5 6 9 2 9 2 15 6 15 11 19 11 5" />
                      <path d="M15.54 8.46a5 5 0 0 1 0 7.07" />
                      <path d="M19.07 4.93a10 10 0 0 1 0 14.14" />
                    </svg>
                    语音播放
                  </button>
                </div>
              )
            )}"""
content = content.replace(old_markdown, new_markdown)
print('Added voice play button to AI messages')

# 3. 在组件内部加 playVoice 函数（找到 handleChangeRole 函数前面）
old_handle_change = "  const handleChangeRole = async (roleId: string | null) => {"
new_handle_change = """  const playVoice = async (text: string, roleId?: string) => {
    if (!text || !text.trim()) return;
    try {
      // 如果有角色ID，用角色声音；否则用默认声音（传null给后端）
      const blob = await synthesizeRoleVoice(roleId || 'default', text);
      const url = URL.createObjectURL(blob);
      const audio = new Audio(url);
      audio.onended = () => URL.revokeObjectURL(url);
      audio.play();
    } catch (e) {
      console.error('语音播放失败', e);
      alert('语音播放失败，请稍后重试');
    }
  };

  const handleChangeRole = async (roleId: string | null) => {"""
content = content.replace(old_handle_change, new_handle_change)
print('Added playVoice function')

with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\ChatPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('ChatPage.tsx fixed - added voice play feature')
