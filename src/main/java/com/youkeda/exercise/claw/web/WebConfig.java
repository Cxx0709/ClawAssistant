package com.youkeda.exercise.claw.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

/**
 * Web MVC 配置
 *
 * 将 /voices/** 映射到运行时的 data/voices 目录，
 * 使上传的声音样本可以通过 HTTP 访问，同时不依赖 src/main/resources/static（打包后不存在）。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // data/voices 目录的绝对路径，末尾加 File.separator 保证目录匹配
        String voicesPath = new File(System.getProperty("user.dir"), "data/voices").getAbsolutePath() + File.separator;
        registry.addResourceHandler("/voices/**")
                .addResourceLocations("file:" + voicesPath);
    }
}