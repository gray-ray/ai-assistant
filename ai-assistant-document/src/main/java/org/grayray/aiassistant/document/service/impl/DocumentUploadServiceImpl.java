package org.grayray.aiassistant.document.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.common.exception.BusinessException;
import org.grayray.aiassistant.document.vo.DocumentUploadResult;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.grayray.aiassistant.document.mapper.DocumentInfoMapper;
import org.grayray.aiassistant.document.service.DocumentProcessService;
import org.grayray.aiassistant.document.service.DocumentUploadService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentUploadServiceImpl extends ServiceImpl<DocumentInfoMapper, DocumentInfo>
        implements DocumentUploadService {

    /** 文件本地存储目录 */
    private static final String UPLOAD_DIR = "./upload";
    /** 访问 URL 前缀 */
    private static final String URL_PREFIX = "/upload";
    /** 存储类型 */
    private static final String STORAGE_TYPE_LOCAL = "local";
    /** 初始处理状态 */
    private static final String STATUS_PENDING = "pending";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final DocumentProcessService documentProcessService;

    @Override
    public DocumentUploadResult upload(MultipartFile file) throws IOException {
        return upload(file, null, null);
    }

    @Override
    public DocumentUploadResult upload(MultipartFile file, Long userId, Long knowledgeId) throws IOException {
        // 空文件校验
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }

        // 按日期创建子目录
        String datePath = LocalDate.now().format(DATE_FMT);
        File dir = new File(UPLOAD_DIR, datePath).getAbsoluteFile();
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BusinessException("创建上传目录失败");
        }

        // 提取原始文件名和扩展名
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (StringUtils.hasText(originalFilename)) {
            int dotIndex = originalFilename.lastIndexOf('.');
            if (dotIndex > 0) {
                extension = originalFilename.substring(dotIndex);
            }
        }

        // 生成新文件名
        String newFilename = System.currentTimeMillis() + extension;

        // 保存到本地（必须用绝对路径，避免 Tomcat work 目录问题）
        File dest = new File(dir, newFilename).getCanonicalFile();
        file.transferTo(dest);

        // 构建文件信息并入库
        DocumentInfo documentInfo = new DocumentInfo();
        documentInfo.setKnowledgeId(knowledgeId);
        documentInfo.setUserId(userId);
        documentInfo.setFileName(newFilename);
        documentInfo.setOriginFileName(originalFilename);
        documentInfo.setFileType(extension);
        documentInfo.setFileSize(file.getSize());
        documentInfo.setFileUrl(URL_PREFIX + "/" + datePath + "/" + newFilename);
        documentInfo.setStoragePath(dest.getAbsolutePath());
        documentInfo.setStorageType(STORAGE_TYPE_LOCAL);
        documentInfo.setProcessStatus(STATUS_PENDING);
        // createTime 由 MyMetaObjectHandler 自动填充

        save(documentInfo);

        // 异步触发 PDF 解析 + 文本清洗
        Long documentId = documentInfo.getId();
        log.info("文件上传完成, documentId={}, 触发异步处理", documentId);
        documentProcessService.processDocument(documentId);

        // 组装返回结果
        DocumentUploadResult result = new DocumentUploadResult();
        result.setDocumentId(documentId);
        result.setFileName(newFilename);
        result.setFileSize(file.getSize());
        result.setFileUrl(documentInfo.getFileUrl());
        result.setProcessStatus(documentInfo.getProcessStatus());

        return result;
    }
}
