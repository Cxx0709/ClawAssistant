with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\ChatPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 synthesizeRoleVoice 到 import
old_import = """  fetchRoles,
  updateConversationRole,
} from '../lib/api';"""
new_import = """  fetchRoles,
  updateConversationRole,
  synthesizeRoleVoice,
} from '../lib/api';"""
content = content.replace(old_import, new_import)
print('Fixed import - added synthesizeRoleVoice')

# 2. 修复 playVoice 函数的注释
old_comment = """  // 切换当前对话的 AI 角色
  const playVoice = async (text: string, roleId?: string) => {"""
new_comment = """  // 语音播放：用角色声音合成并播放
  const playVoice = async (text: string, roleId?: string) => {"""
content = content.replace(old_comment, new_comment)
print('Fixed playVoice comment')

# 3. 修复播放按钮里的 currentConv 变量
old_button = """                    onClick={() => playVoice(m.content, currentConv?.roleId)}"""
new_button = """                    onClick={() => playVoice(m.content, conversations.find(item => item.id === conversationId)?.roleId)}"""
content = content.replace(old_button, new_button)
print('Fixed currentConv variable in play button')

with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\ChatPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('ChatPage.tsx final fix completed')
