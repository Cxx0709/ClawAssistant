with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\ChatPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 删掉 ChatPage 里错误位置的 playVoice 函数
old_playvoice = """  // 语音播放：用角色声音合成并播放
  const playVoice = async (text: string, roleId?: string) => {
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

  const handleChangeRole"""
new_playvoice = """  const handleChangeRole"""
content = content.replace(old_playvoice, new_playvoice)
print('Removed playVoice from ChatPage')

# 2. 给 MessageList 调用加 roleId prop
old_call = """              <MessageList
                messages={messages}
                onToggleTrace={toggleTrace}
              />"""
new_call = """              <MessageList
                messages={messages}
                onToggleTrace={toggleTrace}
                roleId={conversations.find(item => item.id === conversationId)?.roleId}
              />"""
content = content.replace(old_call, new_call)
print('Added roleId prop to MessageList call')

# 3. 修改 MessageList 组件定义，加 roleId prop 和 playVoice 函数
old_def = """function MessageList({
  messages,
  onToggleTrace,
}: {
  messages: ChatMsg[];
  onToggleTrace: (id: string) => void;
}) {
  return ("""
new_def = """function MessageList({
  messages,
  onToggleTrace,
  roleId,
}: {
  messages: ChatMsg[];
  onToggleTrace: (id: string) => void;
  roleId?: string;
}) {
  const playVoice = async (text: string) => {
    if (!text || !text.trim()) return;
    try {
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

  return ("""
content = content.replace(old_def, new_def)
print('Added roleId prop and playVoice to MessageList')

# 4. 修复播放按钮里的 playVoice 调用（去掉第二个参数，因为 MessageList 里已经有 roleId 了）
old_button = """                    onClick={() => playVoice(m.content, conversations.find(item => item.id === conversationId)?.roleId)}"""
new_button = """                    onClick={() => playVoice(m.content)}"""
content = content.replace(old_button, new_button)
print('Fixed playVoice call in button')

with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\ChatPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('MessageList fix completed')
