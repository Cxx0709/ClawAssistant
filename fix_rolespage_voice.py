with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 synthesizeRoleVoice 到 import
old_import = "import { createRole, deleteRole, fetchRoles, updateRole, createConversation } from '../lib/api';"
new_import = "import { createRole, deleteRole, fetchRoles, updateRole, createConversation, synthesizeRoleVoice } from '../lib/api';"
content = content.replace(old_import, new_import)
print('Added synthesizeRoleVoice import')

# 2. 加 file input ref 和上传/试听函数（在 startChat 函数后面）
old_start_chat = """  async function startChat() {
    if (!selected) return;
    try {
      const conv = await createConversation(selected.id);
      window.location.href = `?conversation=${conv.id}`;
    } catch (reason) {
      setError((reason as Error).message || '创建对话失败');
    }
  }"""
new_start_chat = """  async function startChat() {
    if (!selected) return;
    try {
      const conv = await createConversation(selected.id);
      window.location.href = `?conversation=${conv.id}`;
    } catch (reason) {
      setError((reason as Error).message || '创建对话失败');
    }
  }

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploadingVoice, setUploadingVoice] = useState(false);

  function triggerVoiceUpload() {
    fileInputRef.current?.click();
  }

  async function handleVoiceUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file || !selected) return;
    setUploadingVoice(true);
    setError('');
    try {
      const formData = new FormData();
      formData.append('file', file);
      const res = await fetch(`/api/roles/${selected.id}/voice`, {
        method: 'POST',
        body: formData,
        credentials: 'include',
      });
      if (!res.ok) {
        const errText = await res.text().catch(() => '');
        throw new Error(errText || '声音上传失败');
      }
      // 刷新角色列表
      setRevision(r => r + 1);
      alert('声音上传成功！AI 现在会用这个声音说话了～');
    } catch (reason) {
      setError((reason as Error).message || '声音上传失败');
    } finally {
      setUploadingVoice(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  }

  async function previewVoice() {
    if (!selected) return;
    try {
      const blob = await synthesizeRoleVoice(selected.id, '你好呀，我是' + selected.name + '，很高兴见到你！');
      const url = URL.createObjectURL(blob);
      const audio = new Audio(url);
      audio.onended = () => URL.revokeObjectURL(url);
      audio.play();
    } catch (reason) {
      setError((reason as Error).message || '试听失败');
    }
  }"""
content = content.replace(old_start_chat, new_start_chat)
print('Added voice upload and preview functions')

# 3. 在口头禅字段后面加声音上传 UI
old_catchphrase = """                  <div>
                    <label className={label}>口头禅</label>
                    <input className={input} placeholder="比如：乖孙儿" value={catchphrase} onChange={e => setCatchphrase(e.target.value)} maxLength={50} />
                  </div>
                </div>"""
new_catchphrase = """                  <div>
                    <label className={label}>口头禅</label>
                    <input className={input} placeholder="比如：乖孙儿" value={catchphrase} onChange={e => setCatchphrase(e.target.value)} maxLength={50} />
                  </div>

                  {!creating && selected && (
                    <div className="rounded-lg border border-[#e0d8f0] bg-[#f8f5ff] p-4">
                      <div className="mb-2 flex items-center gap-2">
                        <span className="text-lg">🎙️</span>
                        <span className="text-sm font-medium text-[#6c5ce7]">声音定制</span>
                        {selected.voiceAudioUrl ? (
                          <span className="rounded-full bg-green-100 px-2 py-0.5 text-xs text-green-700">已绑定声音</span>
                        ) : (
                          <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-500">未绑定</span>
                        )}
                      </div>
                      <p className="mb-3 text-xs text-ink-faint">
                        上传 3-10 秒的声音样本（比如亲人的录音），AI 就会用这个声音说话，就像亲人在和你面对面聊天一样。
                      </p>
                      <div className="flex items-center gap-2">
                        <input
                          ref={fileInputRef}
                          type="file"
                          accept="audio/*"
                          className="hidden"
                          onChange={handleVoiceUpload}
                        />
                        <button
                          onClick={triggerVoiceUpload}
                          disabled={uploadingVoice}
                          className={`${primary} text-xs`}
                        >
                          {uploadingVoice ? '上传中…' : selected.voiceAudioUrl ? '重新上传声音' : '上传声音样本'}
                        </button>
                        {selected.voiceAudioUrl && (
                          <button onClick={previewVoice} className={`${button} text-xs`}>
                            🔊 试听声音
                          </button>
                        )}
                      </div>
                    </div>
                  )}
                </div>"""
content = content.replace(old_catchphrase, new_catchphrase)
print('Added voice upload UI to edit form')

with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('RolesPage.tsx fixed - voice upload UI added')
