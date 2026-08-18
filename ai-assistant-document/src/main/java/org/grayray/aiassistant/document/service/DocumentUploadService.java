package org.grayray.aiassistant.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.grayray.aiassistant.document.vo.DocumentUploadResult;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface DocumentUploadService extends IService<DocumentInfo> {

    /**
     * 上传文件并保存文件信息到数据库
     * <p>
     * 已不推荐直接调用。上传文档需要明确用户或知识库归属，请使用
     * {@link #upload(MultipartFile, Long, Long)}。
     *
     * @param file 上传的文件
     * @return 上传结果（包含文件 ID、文件名、大小、访问 URL）
     */
    @Deprecated
    DocumentUploadResult upload(MultipartFile file) throws IOException;

    /**
     * 上传文件到指定知识库。
     *
     * @param file 上传的文件
     * @param userId 用户 ID
     * @param knowledgeId 知识库 ID
     * @return 上传结果
     */
    DocumentUploadResult upload(MultipartFile file, Long userId, Long knowledgeId) throws IOException;
}
