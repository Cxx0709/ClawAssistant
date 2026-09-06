import { useEffect, useState, useRef } from 'react';
import BrandMark from '../components/BrandMark';
import { apiFetch, createRole, deleteRole, fetchRoles, updateRole, createConversation, synthesizeRoleVoice } from '../lib/api';
import type { AiRole } from '../lib/types';

const button = 'rounded-lg border border-line px-3 py-2 text-sm transition-colors hover:bg-canvas-sub focus-visible:outline focus-visible:outline-2 focus-visible:outline-brand disabled:cursor-wait disabled:opacity-40';
const primary = `${button} border-transparent bg-brand text-white hover:bg-brand-deep`;
const input = 'w-full rounded-lg border border-line bg-white px-3 py-2.5 text-sm outline-none focus:border-brand focus:ring-2 focus:ring-brand/15';
const label = 'mb-1.5 block text-xs font-medium text-ink-soft';

type RoleFormData = {
  name: string;
  avatar?: string;
  personality?: string;
  background?: string;
  speakingStyle?: string;
  catchphrase?: string;
  voiceId?: string | null;
};

export default function RolesPage({ onBack }: { onBack: () => void }) {
  const [roles, setRoles] = useState<AiRole[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  const [name, setName] = useState('');
  const [personality, setPersonality] = useState('');
  const [background, setBackground] = useState('');
  const [speakingStyle, setSpeakingStyle] = useState('');
  const [catchphrase, setCatchphrase] = useState('');
  const [voiceId, setVoiceId] = useState('');
  const [avatar, setAvatar] = useState('');

  const selected = roles.find(r => r.id === selectedId) || null;
  const detailOpen = creating || !!selected;

  useEffect(() => {
    let alive = true;
    setLoading(true);
    setError('');
    fetchRoles()
      .then(data => {
        if (!alive) return;
        setRoles(data);
        setSelectedId(id => data.some((r: AiRole) => r.id === id) ? id : null);
      })
      .catch((reason: Error) => {
        if (alive) setError(reason.message || '角色读取失败');
      })
      .finally(() => { if (alive) setLoading(false); });
    return () => { alive = false; };
  }, [revision]);

  function startNew() {
    setSelectedId(null);
    setCreating(true);
    setConfirmDelete(false);
    setError('');
    setName('');
    setPersonality('');
    setBackground('');
    setSpeakingStyle('');
    setCatchphrase('');
    setAvatar('');
  }

  function select(role: AiRole) {
    setSelectedId(role.id);
    setCreating(false);
    setConfirmDelete(false);
    setError('');
    setName(role.name || '');
    setPersonality(role.personality || '');
    setBackground(role.background || '');
    setSpeakingStyle(role.speakingStyle || '');
    setCatchphrase(role.catchphrase || '');
    setVoiceId(role.voiceId || '');
    setAvatar(role.avatar || '');
  }

  function closeDetail() {
    setSelectedId(null);
    setCreating(false);
    setConfirmDelete(false);
    setError('');
  }

  function handleAvatarUpload(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    if (!file.type.startsWith('image/')) {
      alert('请选择图片文件');
      return;
    }
    if (file.size > 2 * 1024 * 1024) {
      alert('图片大小不能超过 2MB');
      return;
    }
    const reader = new FileReader();
    reader.onload = () => {
      setAvatar(reader.result as string);
    };
    reader.readAsDataURL(file);
  }

  async function handleSave() {
    if (!name.trim()) {
      setError('请填写角色名称');
      return;
    }
    if (busy) return;
    setBusy(true);
    setError('');
    try {
      const data: RoleFormData = {
        name: name.trim(),
        avatar: avatar.trim() || undefined,
        personality: personality.trim() || undefined,
        background: background.trim() || undefined,
        speakingStyle: speakingStyle.trim() || undefined,
        catchphrase: catchphrase.trim() || undefined,
        voiceId: voiceId.trim() || undefined,
      };
      if (creating) {
        const created = await createRole(data);
        setSelectedId(created.id);
        setCreating(false);
      } else if (selected) {
        await updateRole(selected.id, data);
      }
      setRevision(r => r + 1);
    } catch (reason) {
      setError((reason as Error).message || '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function handleDelete() {
    if (!selected || busy) return;
    setBusy(true);
    setError('');
    try {
      await deleteRole(selected.id);
      setSelectedId(null);
      setConfirmDelete(false);
      setRevision(r => r + 1);
    } catch (reason) {
      setError((reason as Error).message || '删除失败');
    } finally {
      setBusy(false);
    }
  }

  async function startChat() {
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
      const res = await apiFetch(`/api/roles/${selected.id}/voice`, {
        method: 'POST',
        body: formData,
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
  }

  return (
    <div className="min-h-dvh bg-canvas text-ink">
      <header className="sticky top-0 z-10 border-b border-line bg-canvas/80 backdrop-blur">
        <div className="mx-auto flex max-w-6xl items-center gap-3 px-4 py-3">
          <button onClick={onBack} className={button}>← 返回</button>
          <BrandMark size={28} />
          <div>
            <h1 className="text-base font-semibold">AI 角色</h1>
            <p className="text-xs text-ink-faint">创建专属角色，和他们对话</p>
          </div>
          <div className="flex-1" />
          <button onClick={startNew} className={primary}>+ 创建角色</button>
        </div>
      </header>

      <main className="mx-auto max-w-6xl px-4 py-6">
        {error && (
          <div className="mb-4 rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
            {error}
          </div>
        )}

        <div className="grid gap-6 md:grid-cols-[1fr_1.2fr]">
          <div>
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-medium text-ink-soft">共 {roles.length} 个角色</h2>
            </div>

            {loading ? (
              <div className="rounded-lg border border-line bg-white p-8 text-center text-sm text-ink-faint">加载中…</div>
            ) : roles.length === 0 ? (
              <div className="rounded-lg border border-dashed border-line bg-white p-12 text-center">
                <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-brand/10 text-2xl">🎭</div>
                <p className="text-sm font-medium text-ink">还没有角色</p>
                <p className="mt-1 text-xs text-ink-faint">创建你的第一个 AI 角色，比如奶奶、英语老师、偶像…</p>
                <button onClick={startNew} className={`${primary} mt-4`}>创建角色</button>
              </div>
            ) : (
              <div className="space-y-2">
                {roles.map(role => (
                  <button
                    key={role.id}
                    onClick={() => select(role)}
                    className={`w-full rounded-lg border p-4 text-left transition-colors ${
                      selectedId === role.id
                        ? 'border-brand bg-brand/5'
                        : 'border-line bg-white hover:border-brand/40 hover:bg-canvas-sub'
                    }`}
                  >
                    <div className="flex items-center gap-3">
                      <div className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-full bg-gradient-to-br from-brand/20 to-brand/5 text-lg">
                        {role.avatar?.startsWith('data:image') ? (
                          <img src={role.avatar} alt={role.name} className="h-full w-full object-cover" />
                        ) : (
                          <span>{role.avatar || role.name?.charAt(0) || '?'}</span>
                        )}
                      </div>
                      <div className="min-w-0 flex-1">
                        <div className="truncate text-sm font-medium">{role.name}</div>
                        <div className="truncate text-xs text-ink-faint">{role.personality || '暂无性格描述'}</div>
                      </div>
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>

          <div>
            {!detailOpen ? (
              <div className="rounded-lg border border-dashed border-line bg-white p-12 text-center">
                <div className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-full bg-brand/10 text-2xl">👈</div>
                <p className="text-sm font-medium text-ink">选择一个角色查看详情</p>
                <p className="mt-1 text-xs text-ink-faint">或者点击右上角创建新角色</p>
              </div>
            ) : (
              <div className="rounded-lg border border-line bg-white p-6">
                <div className="mb-5 flex items-center justify-between">
                  <h3 className="text-base font-semibold">{creating ? '创建新角色' : `编辑：${selected?.name}`}</h3>
                  <button onClick={closeDetail} className="text-sm text-ink-faint hover:text-ink">✕</button>
                </div>

                <div className="space-y-4">
                  <div>
                    <label className={label}>头像（上传图片或输入 emoji）</label>
                    <div className="flex items-center gap-3">
                      <div className="flex h-12 w-12 shrink-0 items-center justify-center overflow-hidden rounded-full bg-gradient-to-br from-brand/20 to-brand/5 text-2xl">
                        {avatar?.startsWith('data:image') ? (
                          <img src={avatar} alt="头像" className="h-full w-full object-cover" />
                        ) : (
                          <span>{avatar || name?.charAt(0) || '?'}</span>
                        )}
                      </div>
                      <div className="flex-1 space-y-2">
                        <input
                          type="file"
                          accept="image/*"
                          onChange={handleAvatarUpload}
                          className="block w-full text-xs text-ink-soft file:mr-3 file:rounded-md file:border-0 file:bg-brand/10 file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-brand-deep hover:file:bg-brand/20"
                        />
                        <input className={input} placeholder="或输入 emoji，比如 👵" value={avatar?.startsWith('data:image') ? '' : avatar} onChange={e => setAvatar(e.target.value)} maxLength={10} />
                      </div>
                    </div>
                  </div>
                  <div>
                    <label className={label}>角色名称 *</label>
                    <input className={input} placeholder="比如：奶奶、英语老师、宋威龙" value={name} onChange={e => setName(e.target.value)} maxLength={50} />
                  </div>
                  <div>
                    <label className={label}>性格</label>
                    <textarea className={`${input} min-h-[60px] resize-y`} placeholder="比如：慈祥、爱唠叨、总担心孙子吃不饱穿不暖" value={personality} onChange={e => setPersonality(e.target.value)} maxLength={500} />
                  </div>
                  <div>
                    <label className={label}>背景故事</label>
                    <textarea className={`${input} min-h-[80px] resize-y`} placeholder="比如：退休小学教师，从小带大孙子，现在孙子上大学了，很想念他" value={background} onChange={e => setBackground(e.target.value)} maxLength={2000} />
                  </div>
                  <div>
                    <label className={label}>说话风格</label>
                    <textarea className={`${input} min-h-[60px] resize-y`} placeholder="比如：语气温暖，喜欢用'乖孙儿'称呼对方，经常念叨吃饭、穿衣服、早点睡" value={speakingStyle} onChange={e => setSpeakingStyle(e.target.value)} maxLength={500} />
                  </div>
                  <div>
                    <label className={label}>口头禅</label>
                    <input className={input} placeholder="比如：乖孙儿" value={catchphrase} onChange={e => setCatchphrase(e.target.value)} maxLength={50} />
                  </div>
                  <div>
                    <label className={label}>选择预设音色</label>
                    <select className={input} value={voiceId} onChange={e => setVoiceId(e.target.value)}>
                      <option value="">不使用</option>
                      <option value="longanyang">阳光大男孩</option>
                      <option value="longanhuan_v3">欢脱元气女</option>
                      <option value="longhuhu_v3">天真烂漫女童</option>
                      <option value="longxian_v3">豪放可爱少女</option>
                      <option value="longling_v3">稚气呆板女童</option>
                      <option value="longniuniu_v3">阳光男童声</option>
                    </select>
                  </div>

                  {!creating && selected && (
                    <div className="rounded-lg border border-[#e0d8f0] bg-[#f8f5ff] p-4">
                      <div className="mb-2 flex items-center gap-2">
                        <span className="text-lg">🎙️</span>
                        <span className="text-sm font-medium text-[#6c5ce7]">声音定制</span>
                      </div>
                      <p className="mb-3 text-xs text-ink-faint">
                        上方选择预设音色后，AI 就会用该音色说话。也可以上传声音样本进行存档。
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
                        {(selected.voiceId || selected.voiceAudioUrl) && (
                          <button onClick={previewVoice} className={`${button} text-xs`}>
                            🔊 试听声音
                          </button>
                        )}
                      </div>
                    </div>
                  )}
                </div>

                <div className="mt-6 flex items-center gap-3">
                  <button onClick={handleSave} disabled={busy} className={primary}>
                    {busy ? '保存中…' : creating ? '创建角色' : '保存修改'}
                  </button>
                  {!creating && selected && (
                    <button onClick={startChat} className={button}>💬 和 TA 聊天</button>
                  )}
                  {!creating && selected && !confirmDelete && (
                    <button onClick={() => setConfirmDelete(true)} className={`${button} text-red-600 hover:bg-red-50`}>删除</button>
                  )}
                  {confirmDelete && (
                    <div className="flex items-center gap-2">
                      <span className="text-xs text-red-600">确认删除？</span>
                      <button onClick={handleDelete} disabled={busy} className={`${button} bg-red-600 text-white hover:bg-red-700`}>确认</button>
                      <button onClick={() => setConfirmDelete(false)} className={button}>取消</button>
                    </div>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  );
}
