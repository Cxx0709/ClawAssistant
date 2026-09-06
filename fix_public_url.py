with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 @Value 注入公网基础 URL
old_import = "import org.springframework.web.multipart.MultipartFile;"
new_import = """import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;"""
content = content.replace(old_import, new_import)
print('Added @Value import')

# 2. 加字段和构造函数参数
old_fields = """    private final VoiceService voiceService;
    private final VoiceClient voiceClient;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context, VoiceService voiceService, VoiceClient voiceClient) {
        this.roles = roles;
        this.users = users;
        this.context = context;
        this.voiceService = voiceService;
        this.voiceClient = voiceClient;
    }"""
new_fields = """    private final VoiceService voiceService;
    private final VoiceClient voiceClient;

    @Value("${app.public-base-url:}")
    private String publicBaseUrl;

    public AiRoleController(AiRoleRepository roles, AuthenticatedUser users, UserExecutionContext context, VoiceService voiceService, VoiceClient voiceClient) {
        this.roles = roles;
        this.users = users;
        this.context = context;
        this.voiceService = voiceService;
        this.voiceClient = voiceClient;
    }"""
content = content.replace(old_fields, new_fields)
print('Added publicBaseUrl field')

# 3. 修改 TTS 方法里的 URL 拼接逻辑
old_tts_url = """                    String voiceUrl = roleOpt.get().voiceAudioUrl();
                    // 只有公网 URL（http/https 开头）才能用于声音克隆
                    // 本地相对路径（/voices/xxx）在本地开发时 DashScope 访问不到，部署后可配置公网域名
                    if (voiceUrl != null && (voiceUrl.startsWith("http://") || voiceUrl.startsWith("https://"))) {
                        promptAudioUrl = voiceUrl;
                    }"""
new_tts_url = """                    String voiceUrl = roleOpt.get().voiceAudioUrl();
                    if (voiceUrl != null && !voiceUrl.isBlank()) {
                        // 如果是相对路径（/voices/xxx），拼接公网基础 URL（ngrok 或服务器域名）
                        if (voiceUrl.startsWith("/") && publicBaseUrl != null && !publicBaseUrl.isBlank()) {
                            promptAudioUrl = publicBaseUrl + voiceUrl;
                        }
                        // 如果已经是公网 URL，直接用
                        else if (voiceUrl.startsWith("http://") || voiceUrl.startsWith("https://")) {
                            promptAudioUrl = voiceUrl;
                        }
                    }"""
content = content.replace(old_tts_url, new_tts_url)
print('Modified TTS URL logic to support public base URL')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\role\AiRoleController.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('AiRoleController.java fixed - public base URL support added')
