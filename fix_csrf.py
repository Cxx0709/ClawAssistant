with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 apiFetch 到 import
old_import = "import { createRole, deleteRole, fetchRoles, updateRole, createConversation, synthesizeRoleVoice } from '../lib/api';"
new_import = "import { apiFetch, createRole, deleteRole, fetchRoles, updateRole, createConversation, synthesizeRoleVoice } from '../lib/api';"
content = content.replace(old_import, new_import)
print('Added apiFetch import')

# 2. 把 handleVoiceUpload 里的原生 fetch 改成 apiFetch
old_fetch = """      const formData = new FormData();
      formData.append('file', file);
      const res = await fetch(`/api/roles/${selected.id}/voice`, {
        method: 'POST',
        body: formData,
        credentials: 'include',
      });"""
new_fetch = """      const formData = new FormData();
      formData.append('file', file);
      const res = await apiFetch(`/api/roles/${selected.id}/voice`, {
        method: 'POST',
        body: formData,
      });"""
content = content.replace(old_fetch, new_fetch)
print('Changed fetch to apiFetch in handleVoiceUpload')

with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('RolesPage.tsx fixed - CSRF issue resolved')
