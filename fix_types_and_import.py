# 1. 修复 RolesPage 的 useRef import
with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()
content = content.replace(
    "import { useState, useEffect } from 'react';",
    "import { useState, useEffect, useRef } from 'react';"
)
with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed useRef import in RolesPage')

# 2. 修复 types.ts 里的 AiRole 类型，加 voiceAudioUrl 字段
with open(r'C:\Users\han\ClawAssistant\frontend\src\lib\types.ts', 'r', encoding='utf-8') as f:
    content = f.read()

# 找到 AiRole 接口，加 voiceAudioUrl 字段
old_airole = """export interface AiRole {
  id: string;
  userId: string;
  name: string;
  avatar?: string;
  personality?: string;
  background?: string;
  speakingStyle?: string;
  catchphrase?: string;
  createdAt: number;
  updatedAt: number;
}"""
new_airole = """export interface AiRole {
  id: string;
  userId: string;
  name: string;
  avatar?: string;
  personality?: string;
  background?: string;
  speakingStyle?: string;
  catchphrase?: string;
  voiceAudioUrl?: string;
  createdAt: number;
  updatedAt: number;
}"""
if old_airole in content:
    content = content.replace(old_airole, new_airole)
    print('Added voiceAudioUrl to AiRole type')
else:
    print('AiRole interface not found with exact format, trying regex...')
    import re
    content = re.sub(
        r'(export interface AiRole \{[^}]*?catchphrase\?: string;)',
        r'\1\n  voiceAudioUrl?: string;',
        content
    )
    print('Added voiceAudioUrl to AiRole type via regex')

with open(r'C:\Users\han\ClawAssistant\frontend\src\lib\types.ts', 'w', encoding='utf-8') as f:
    f.write(content)
print('types.ts fixed')
