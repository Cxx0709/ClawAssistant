# 1. 修复 RolesPage 的 useRef import（顺序是 useEffect, useState）
with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'r', encoding='utf-8') as f:
    content = f.read()
content = content.replace(
    "import { useEffect, useState } from 'react';",
    "import { useEffect, useState, useRef } from 'react';"
)
with open(r'C:\Users\han\ClawAssistant\frontend\src\pages\RolesPage.tsx', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed useRef import in RolesPage')

# 2. 修复 types.ts 里的 AiRole 类型，加 voiceAudioUrl 字段（字段是 string | null）
with open(r'C:\Users\han\ClawAssistant\frontend\src\lib\types.ts', 'r', encoding='utf-8') as f:
    content = f.read()

old_airole = """  catchphrase?: string | null;
  createdAt: number;
  updatedAt: number;
}"""
new_airole = """  catchphrase?: string | null;
  voiceAudioUrl?: string | null;
  createdAt: number;
  updatedAt: number;
}"""
content = content.replace(old_airole, new_airole)

with open(r'C:\Users\han\ClawAssistant\frontend\src\lib\types.ts', 'w', encoding='utf-8') as f:
    f.write(content)
print('Added voiceAudioUrl to AiRole type')
