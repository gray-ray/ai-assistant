package org.grayray.aiassistant.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.common.config.UploadProperties;
import org.grayray.aiassistant.common.exception.BusinessException;
import org.grayray.aiassistant.common.result.ResultCode;
import org.grayray.aiassistant.document.vo.DocumentUploadResult;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.grayray.aiassistant.document.mapper.DocumentInfoMapper;
import org.grayray.aiassistant.document.service.DocumentProcessService;
import org.grayray.aiassistant.document.service.DocumentUploadService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadServiceImpl extends ServiceImpl<DocumentInfoMapper, DocumentInfo>
        implements DocumentUploadService {

    /** 初始处理状态 */
    private static final String STATUS_PENDING = "pending";
    /** PDF 文件头 */
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UploadProperties uploadProperties;
    private final DocumentProcessService documentProcessService;

    @Deprecated
    @Override
    public DocumentUploadResult upload(MultipartFile file) throws IOException {
        throw new BusinessException(ResultCode.BAD_REQUEST, "上传文档缺少用户归属");
    }

    @Override
    public DocumentUploadResult upload(MultipartFile file, Long userId, Long knowledgeId) throws IOException {
        // 空文件校验
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件不能为空");
        }
        if (userId == null && knowledgeId == null) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文档缺少用户归属");
        }

        String originFileName = normalizeOriginFileName(file.getOriginalFilename());
        String extension = extractExtension(originFileName);
        validateFileType(file, extension);

        // 生成新文件名
        String newFilename = UUID.randomUUID() + extension;

        // 按日期创建子目录
        String datePath = LocalDate.now().format(DATE_FMT);
        Path uploadRoot = uploadProperties.getBasePath();
        Files.createDirectories(uploadRoot);
        Path realUploadRoot = uploadRoot.toRealPath();
        Path dir = realUploadRoot.resolve(datePath).normalize();
        if (!dir.startsWith(realUploadRoot)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "非法上传目录");
        }
        Files.createDirectories(dir);

        Path dest = dir.resolve(newFilename).normalize();
        if (!dest.startsWith(realUploadRoot)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "非法文件路径");
        }
        if (Files.exists(dest)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "上传文件名冲突，请重试");
        }

        // 保存到本地（必须用绝对路径，避免 Tomcat work 目录问题）
        file.transferTo(dest);
        boolean fileSaved = true;

        // 构建文件信息并入库
        DocumentInfo documentInfo = new DocumentInfo();
        documentInfo.setKnowledgeId(knowledgeId);
        documentInfo.setUserId(userId);
        documentInfo.setFileName(newFilename);
        documentInfo.setOriginFileName(originFileName);
        documentInfo.setFileType(extension);
        documentInfo.setFileSize(file.getSize());
        documentInfo.setFileUrl(buildFileUrl(datePath, newFilename));
        documentInfo.setStoragePath(dest.toString());
        documentInfo.setStorageType(uploadProperties.getStorageType());
        documentInfo.setProcessStatus(STATUS_PENDING);
        // createTime 由 MyMetaObjectHandler 自动填充

        try {
            if (!save(documentInfo)) {
                throw new BusinessException("保存上传文件信息失败");
            }
        } catch (RuntimeException e) {
            if (fileSaved) {
                deleteQuietly(dest);
            }
            throw e;
        }

        // 异步触发 PDF 解析 + 文本清洗
        Long documentId = documentInfo.getId();
        log.info("文件上传完成, documentId={}, 触发异步处理", documentId);
        try {
            documentProcessService.processDocument(documentId);
        } catch (RuntimeException e) {
            documentInfo.setProcessStatus("failed");
            documentInfo.setProcessError(truncateError(e.getMessage()));
            updateById(documentInfo);
            throw e;
        }

        // 组装返回结果
        DocumentUploadResult result = new DocumentUploadResult();
        result.setDocumentId(documentId);
        result.setFileName(newFilename);
        result.setFileSize(file.getSize());
        result.setFileUrl(documentInfo.getFileUrl());
        result.setProcessStatus(documentInfo.getProcessStatus());

        return result;
    }

    private void validateFileType(MultipartFile file, String extension) throws IOException {
        if (!containsIgnoreCase(uploadProperties.getAllowedExtensions(), extension)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的文件类型，仅支持 PDF 文件");
        }

        String contentType = file.getContentType();
        if (!StringUtils.hasText(contentType)
                || !containsIgnoreCase(uploadProperties.getAllowedContentTypes(), contentType)) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "不支持的文件类型，仅支持 PDF 文件");
        }

        try (InputStream inputStream = file.getInputStream()) {
            byte[] header = inputStream.readNBytes(PDF_MAGIC.length);
            if (header.length < PDF_MAGIC.length) {
                throw new BusinessException(ResultCode.BAD_REQUEST, "文件内容不是合法 PDF");
            }
            for (int i = 0; i < PDF_MAGIC.length; i++) {
                if (header[i] != PDF_MAGIC[i]) {
                    throw new BusinessException(ResultCode.BAD_REQUEST, "文件内容不是合法 PDF");
                }
            }
        }
    }

    private boolean containsIgnoreCase(Iterable<String> values, String target) {
        if (!StringUtils.hasText(target)) {
            return false;
        }
        for (String value : values) {
            if (StringUtils.hasText(value) && value.trim().equalsIgnoreCase(target.trim())) {
                return true;
            }
        }
        return false;
    }

    private String normalizeOriginFileName(String originalFilename) {
        String filename = StringUtils.hasText(originalFilename) ? originalFilename.trim() : "unnamed.pdf";
        filename = filename.replace('\\', '/');
        int slashIndex = filename.lastIndexOf('/');
        if (slashIndex >= 0) {
            filename = filename.substring(slashIndex + 1);
        }
        filename = filename.replaceAll("[\\p{Cntrl}]", "").trim();
        if (!StringUtils.hasText(filename)) {
            filename = "unnamed.pdf";
        }

        int maxLength = uploadProperties.getMaxOriginFileNameLength();
        if (maxLength > 0 && filename.length() > maxLength) {
            String extension = extractExtension(filename);
            int baseMaxLength = Math.max(1, maxLength - extension.length());
            filename = filename.substring(0, baseMaxLength) + extension;
        }
        return filename;
    }

    private String extractExtension(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "";
        }
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex <= 0 || dotIndex == filename.length() - 1) {
            return "";
        }
        return filename.substring(dotIndex).toLowerCase(Locale.ROOT);
    }

    private String buildFileUrl(String datePath, String newFilename) {
        return uploadProperties.normalizedUrlPrefix() + "/" + datePath + "/" + newFilename;
    }

    private void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("清理上传文件失败, path={}", path, e);
        }
    }

    private String truncateError(String errorMsg) {
        return errorMsg != null && errorMsg.length() > 990
                ? errorMsg.substring(0, 990) + "..."
                : errorMsg;
    }
}
