with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 类型定义加 voiceId
old_type = '''  catchphrase?: string;
  voiceAudioUrl?: string | null;'''
new_type = '''  catchphrase?: string;
  voiceAudioUrl?: string | null;
  voiceId?: string | null;'''
content = content.replace(old_type, new_type)
print('Added voiceId to type definition')

# 2. 加 voiceId state
old_state = '''  const [catchphrase, setCatchphrase] = useState('');'''
new_state = '''  const [catchphrase, setCatchphrase] = useState('');
  const [voiceId, setVoiceId] = useState('');'''
content = content.replace(old_state, new_state)
print('Added voiceId state')

# 3. 编辑角色时初始化 voiceId
old_init = '''    setCatchphrase(role.catchphrase || '');'''
new_init = '''    setCatchphrase(role.catchphrase || '');
    setVoiceId(role.voiceId || '');'''
content = content.replace(old_init, new_init)
print('Added voiceId init')

# 4. 保存角色时提交 voiceId
old_save = '''        catchphrase: catchphrase.trim() || undefined,'''
new_save = '''        catchphrase: catchphrase.trim() || undefined,
        voiceId: voiceId.trim() || undefined,'''
content = content.replace(old_save, new_save)
print('Added voiceId to save')

# 5. 在声音定制区域加"自定义声音 ID"输入框
old_voice_section = '''                        <span className="text-sm font-medium text-[#6c5ce7]">声音定制</span>
                        {selected.voiceAudioUrl ? ('''
new_voice_section = '''                        <span className="text-sm font-medium text-[#6c5ce7]">声音定制</span>
                        {selected.voiceId ? (
                          <span className="rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-700">已绑定自定义声音</span>
                        ) : selected.voiceAudioUrl ? ('''
content = content.replace(old_voice_section, new_voice_section)
print('Modified voice section header')

# 在上传声音按钮后面加自定义声音 ID 输入框
old_upload_btn = '''                        {selected.voiceAudioUrl && (
                          <button
                            type="button"
                            onClick={handlePlayVoice}
                            className="rounded-lg border border-[#6c5ce7] px-4 py-2 text-sm font-medium text-[#6c5ce7] hover:bg-[#6c5ce7]/5"
                          >
                            🔊 试听声音
                          </button>
                        )}'''
new_upload_btn = '''                        {selected.voiceAudioUrl && (
                          <button
                            type="button"
                            onClick={handlePlayVoice}
                            className="rounded-lg border border-[#6c5ce7] px-4 py-2 text-sm font-medium text-[#6c5ce7] hover:bg-[#6c5ce7]/5"
                          >
                            🔊 试听声音
                          </button>
                        )}

                        {/* 手动输入自定义声音 ID（从百炼控制台创建后填入） */}
                        <div className="mt-4">
                          <label className="block text-sm font-medium text-gray-700 mb-1">
                            自定义声音 ID（可选）
                          </label>
                          <input
                            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-[#6c5ce7] focus:outline-none focus:ring-1 focus:ring-[#6c5ce7]"
                            placeholder="从阿里云百炼控制台创建声音后，把 voice_id 粘贴到这里"
                            value={voiceId}
                            onChange={e => setVoiceId(e.target.value)}
                          />
                          <p className="mt-1 text-xs text-gray-500">
                            提示：可以在阿里云百炼控制台上传音频创建自定义声音，获取 voice_id 后粘贴到这里，AI 就会用那个声音说话。
                          </p>
                        </div>'''
content = content.replace(old_upload_btn, new_upload_btn)
print('Added custom voice ID input')

with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('RolesPage.tsx fixed - custom voice ID input added')
