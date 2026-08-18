package org.grayray.aiassistant.document.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.grayray.aiassistant.common.result.Result;
import org.grayray.aiassistant.document.dto.KnowledgeBaseCreateDTO;
import org.grayray.aiassistant.document.dto.KnowledgeBaseDeleteDTO;
import org.grayray.aiassistant.document.dto.KnowledgeBaseUpdateDTO;
import org.grayray.aiassistant.document.entity.DocumentChunk;
import org.grayray.aiassistant.document.entity.DocumentInfo;
import org.grayray.aiassistant.document.entity.KnowledgeBase;
import org.grayray.aiassistant.document.service.DocumentChunkService;
import org.grayray.aiassistant.document.service.DocumentUploadService;
import org.grayray.aiassistant.document.service.KnowledgeBaseService;
import org.grayray.aiassistant.document.vo.DocumentUploadResult;
import org.grayray.aiassistant.document.vo.KnowledgeBaseVO;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "知识库", description = "知识库管理与知识库文档上传")
@RestController
@RequestMapping("/knowledge")
@Validated
public class KnowledgeBaseController {

    @Resource
    private KnowledgeBaseService knowledgeBaseService;

    @Resource
    private DocumentUploadService documentUploadService;

    @Resource
    private DocumentChunkService documentChunkService;

    @Operation(summary = "创建知识库")
    @PostMapping("/create")
    public Result<KnowledgeBaseVO> create(@RequestBody @Valid KnowledgeBaseCreateDTO dto) {
        KnowledgeBase kb = knowledgeBaseService.create(dto.getUserId(), dto.getName(), dto.getDescription());
        return Result.success(toVO(kb));
    }

    @Operation(summary = "知识库列表")
    @GetMapping("/list")
    public Result<List<KnowledgeBaseVO>> list(
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId) {
        List<KnowledgeBase> list = knowledgeBaseService.listByUserId(userId);
        return Result.success(list.stream().map(this::toVO).collect(Collectors.toList()));
    }

    @Operation(summary = "知识库详情")
    @GetMapping("/{knowledgeId}")
    public Result<KnowledgeBaseVO> detail(
            @Parameter(description = "知识库ID") @PathVariable("knowledgeId") @NotNull Long knowledgeId,
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId) {
        return Result.success(toVO(knowledgeBaseService.getUserKnowledgeBase(userId, knowledgeId)));
    }

    @Operation(summary = "更新知识库")
    @PostMapping("/{knowledgeId}/update")
    public Result<KnowledgeBaseVO> update(
            @Parameter(description = "知识库ID") @PathVariable("knowledgeId") @NotNull Long knowledgeId,
            @RequestBody @Valid KnowledgeBaseUpdateDTO dto) {
        KnowledgeBase kb = knowledgeBaseService.updateInfo(
                dto.getUserId(), knowledgeId, dto.getName(), dto.getDescription(), dto.getStatus());
        return Result.success(toVO(kb));
    }

    @Operation(summary = "删除知识库")
    @PostMapping("/{knowledgeId}/delete")
    public Result<Void> delete(
            @Parameter(description = "知识库ID") @PathVariable("knowledgeId") @NotNull Long knowledgeId,
            @RequestBody @Valid KnowledgeBaseDeleteDTO dto) {
        knowledgeBaseService.deleteByUser(dto.getUserId(), knowledgeId);
        return Result.success();
    }

    @Operation(summary = "上传文档到知识库")
    @PostMapping("/{knowledgeId}/document/upload")
    public Result<DocumentUploadResult> uploadDocument(
            @Parameter(description = "知识库ID") @PathVariable("knowledgeId") @NotNull Long knowledgeId,
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId,
            @RequestParam("file") MultipartFile file) throws IOException {
        knowledgeBaseService.requireActiveUserKnowledgeBase(userId, knowledgeId);
        DocumentUploadResult result = documentUploadService.upload(file, userId, knowledgeId);
        return Result.success(result);
    }

    @Operation(summary = "知识库文档列表")
    @GetMapping("/{knowledgeId}/documents")
    public Result<List<DocumentInfo>> listDocuments(
            @Parameter(description = "知识库ID") @PathVariable("knowledgeId") @NotNull Long knowledgeId,
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId) {
        knowledgeBaseService.getUserKnowledgeBase(userId, knowledgeId);
        List<DocumentInfo> documents = documentUploadService.list(new LambdaQueryWrapper<DocumentInfo>()
                .eq(DocumentInfo::getKnowledgeId, knowledgeId)
                .eq(DocumentInfo::getUserId, userId)
                .orderByDesc(DocumentInfo::getCreateTime));
        return Result.success(documents);
    }

    @Operation(summary = "文档Chunk列表")
    @GetMapping("/document/{documentId}/chunks")
    public Result<List<DocumentChunk>> listChunks(
            @Parameter(description = "文档ID") @PathVariable("documentId") @NotNull Long documentId,
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId) {
        DocumentInfo document = documentUploadService.getById(documentId);
        if (document == null || document.getKnowledgeId() == null || !userId.equals(document.getUserId())) {
            return Result.fail(org.grayray.aiassistant.common.result.ResultCode.NOT_FOUND, "文档不存在或无权限");
        }
        knowledgeBaseService.getUserKnowledgeBase(userId, document.getKnowledgeId());
        return Result.success(documentChunkService.listByDocument(documentId));
    }

    private KnowledgeBaseVO toVO(KnowledgeBase kb) {
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(kb.getId());
        vo.setUserId(kb.getUserId());
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setVectorStoreType(kb.getVectorStoreType());
        vo.setVectorStorePath(kb.getVectorStorePath());
        vo.setVectorCollection(kb.getVectorCollection());
        vo.setStatus(kb.getStatus());
        vo.setCreateTime(kb.getCreateTime());
        vo.setUpdateTime(kb.getUpdateTime());
        return vo;
    }
}
