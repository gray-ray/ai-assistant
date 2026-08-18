package org.grayray.aiassistant.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 文件本地存储目录，和 FileUploadController 保持一致 */
    private static final String UPLOAD_DIR = "./upload";
    /** 访问 URL 前缀，和 FileUploadController 保持一致 */
    private static final String URL_PREFIX = "/upload";

    /**
     * 把本地磁盘的上传目录映射为可 HTTP 访问的静态资源
     * 例如：file:./upload/2026/08/06/xxx.jpg -> http://host:port/ai/upload/2026/08/06/xxx.jpg
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = "file:" + new File(UPLOAD_DIR).getAbsolutePath() + File.separator;
        registry.addResourceHandler(URL_PREFIX + "/**")
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
