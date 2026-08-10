package org.grayray.aiassistant.document.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.grayray.aiassistant.document.vo.DocumentUploadResult;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

public interface DocumentUploadService extends IService<DocumentInfo> {

    /**
     * 上传文件并保存文件信息到数据库
     *
     * @param file 上传的文件
     * @return 上传结果（包含文件 ID、文件名、大小、访问 URL）
     */
    DocumentUploadResult upload(MultipartFile file) throws IOException;
}