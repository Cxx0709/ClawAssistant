with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRole.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 加 voiceAudioUrl 字段
old_record = '''public record AiRole(
        String id,
        String userId,
        String name,
        String avatar,
        String personality,
        String background,
        String speakingStyle,
        String catchphrase,
        long createdAt,
        long updatedAt
) {'''
new_record = '''public record AiRole(
        String id,
        String userId,
        String name,
        String avatar,
        String personality,
        String background,
        String speakingStyle,
        String catchphrase,
        String voiceAudioUrl,
        long createdAt,
        long updatedAt
) {'''
content = content.replace(old_record, new_record)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRole.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRole.java fixed - added voiceAudioUrl field')
