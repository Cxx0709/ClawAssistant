with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

old = '.header("Content-Disposition", "inline; filename="tts.mp3"")'
new = '.header("Content-Disposition", "inline; filename=tts.mp3")'
content = content.replace(old, new)

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('Fixed quote escaping')
