with open(r'C:\Users\han\ClawAssistant\frontend\src\lib\api.ts', 'r', encoding='utf-8') as f:
    content = f.read()

old = '''export async function deleteRole(id: string): Promise<void> {
  const res = await apiFetch(`/api/roles/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error((await readError(res)) || '删除角色失败');
}'''

new = '''export async function deleteRole(id: string): Promise<void> {
  const res = await apiFetch(`/api/roles/${encodeURIComponent(id)}`, { method: 'DELETE' });
  if (!res.ok) throw new Error((await readError(res)) || '删除角色失败');
}

/** 用角色的声音合成语音（TTS），返回音频 blob */
export async function synthesizeRoleVoice(roleId: string, text: string): Promise<Blob> {
  const res = await apiFetch(`/api/roles/${encodeURIComponent(roleId)}/tts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ text }),
  });
  if (!res.ok) throw new Error((await readError(res)) || '语音合成失败');
  return await res.blob();
}'''

content = content.replace(old, new)

with open(r'C:\Users\han\ClawAssistant\frontend\src\lib\api.ts', 'w', encoding='utf-8') as f:
    f.write(content)
print('api.ts fixed - added synthesizeRoleVoice')
