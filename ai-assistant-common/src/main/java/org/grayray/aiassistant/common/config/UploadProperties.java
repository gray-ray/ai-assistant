package org.grayray.aiassistant.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "ai.upload")
public class UploadProperties {

    /** 文件本地存储目录 */
    private String baseDir = "./upload";

    /** 本地文件访问 URL 前缀 */
    private String urlPrefix = "/upload";

    /** 当前存储类型，后续可扩展为 oss/s3/minio 等 */
    private String storageType = "local";

    /** 是否注册 /upload 静态资源映射 */
    private boolean publicStaticEnabled = true;

    /** 允许上传的文件扩展名 */
    private List<String> allowedExtensions = new ArrayList<>(List.of(".pdf"));

    /** 允许上传的 Content-Type */
    private List<String> allowedContentTypes = new ArrayList<>(List.of("application/pdf"));

    /** 数据库 origin_file_name 字段长度 */
    private int maxOriginFileNameLength = 255;

    public Path getBasePath() {
        return Paths.get(baseDir).toAbsolutePath().normalize();
    }

    public String normalizedUrlPrefix() {
        if (urlPrefix == null || urlPrefix.isBlank()) {
            return "/upload";
        }
        String normalized = urlPrefix.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
