with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\web\SecurityConfig.java', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. 加 import
content = content.replace(
    'import org.springframework.security.config.annotation.web.builders.HttpSecurity;',
    'import org.springframework.security.config.annotation.web.builders.HttpSecurity;\nimport org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;'
)
print('Added WebSecurityCustomizer import')

# 2. 在 PasswordEncoder bean 后面加 WebSecurityCustomizer bean
old_bean = """    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    SecurityFilterChain securityFilterChain"""
new_bean = """    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    WebSecurityCustomizer webSecurityCustomizer() {
        // 完全忽略 /voices/** 路径，不做任何认证检查（供 DashScope 下载音频样本）
        return (web) -> web.ignoring().requestMatchers("/voices/**");
    }

    @Bean
    SecurityFilterChain securityFilterChain"""
content = content.replace(old_bean, new_bean)
print('Added WebSecurityCustomizer bean')

with open(r'C:\Users\han\ClawAssistant\src\main\java\com\youkeda\exercise\claw\web\SecurityConfig.java', 'w', encoding='utf-8') as f:
    f.write(content)
print('SecurityConfig.java fixed - WebSecurityCustomizer added')
