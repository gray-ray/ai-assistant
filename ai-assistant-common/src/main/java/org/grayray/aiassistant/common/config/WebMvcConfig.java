package org.grayray.aiassistant.common.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final UploadProperties uploadProperties;

    /**
     * 把本地磁盘的上传目录映射为可 HTTP 访问的静态资源
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        if (!uploadProperties.isPublicStaticEnabled()) {
            return;
        }
        String location = uploadProperties.getBasePath().toUri().toString();
        registry.addResourceHandler(uploadProperties.normalizedUrlPrefix() + "/**")
                .addResourceLocations(location);
    }

    /**
     * 跨域配置（前后端分离开发时用）
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
