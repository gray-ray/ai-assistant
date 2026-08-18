package org.grayray.aiassistant.document.controller;

import jakarta.annotation.Resource;
import jakarta.validation.constraints.NotNull;
import org.grayray.aiassistant.common.exception.BusinessException;
import org.grayray.aiassistant.common.result.Result;
import org.grayray.aiassistant.document.vo.DocumentUploadResult;
import org.grayray.aiassistant.document.service.DocumentUploadService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/document")
@Validated
public class DocumentController {

    @Resource
    private DocumentUploadService documentUploadService;

    /**
     * 单文件上传
     */
    @PostMapping("/upload")
    public Result<DocumentUploadResult> upload(
            @RequestParam("userId") @NotNull Long userId,
            @RequestParam("file") MultipartFile file) throws IOException {
        DocumentUploadResult result = documentUploadService.upload(file, userId, null);
        return Result.success(result);
    }

    /**
     * 多文件上传
     */
    @PostMapping("/batchUpLoad")
    public Result<List<DocumentUploadResult>> uploadBatch(
            @RequestParam("userId") @NotNull Long userId,
            @RequestParam("files") MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) {
            throw new BusinessException("上传文件不能为空");
        }
        List<DocumentUploadResult> results = new ArrayList<>(files.length);
        for (MultipartFile file : files) {
            results.add(documentUploadService.upload(file, userId, null));
        }
        return Result.success(results);
    }
}
