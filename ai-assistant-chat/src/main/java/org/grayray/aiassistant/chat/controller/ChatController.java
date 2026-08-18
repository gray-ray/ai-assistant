package org.grayray.aiassistant.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.grayray.aiassistant.common.result.Result;
import org.grayray.aiassistant.chat.dto.ChatSendRequestDTO;
import org.grayray.aiassistant.chat.dto.ChatSessionCreateDTO;
import org.grayray.aiassistant.chat.dto.ChatSessionDeleteDTO;
import org.grayray.aiassistant.chat.dto.ChatSessionRenameDTO;
import org.grayray.aiassistant.chat.model.ChatSendResult;
import org.grayray.aiassistant.chat.vo.ChatMessageVO;
import org.grayray.aiassistant.chat.vo.ChatSessionVO;
import org.grayray.aiassistant.chat.vo.CitationVO;
import org.grayray.aiassistant.chat.entity.ChatMessage;
import org.grayray.aiassistant.chat.entity.ChatMessageCitation;
import org.grayray.aiassistant.chat.entity.ChatSession;
import org.grayray.aiassistant.chat.service.ChatService;
import org.grayray.aiassistant.chat.service.ChatSessionService;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "聊天会话", description = "聊天会话管理与消息发送（同步 + SSE 流式）")
@RestController
@RequestMapping("/chat")
@Validated
public class ChatController {

    @Resource
    private ChatSessionService chatSessionService;

    @Resource
    private ChatService chatService;

    // ==================== 会话管理 ====================

    @Operation(summary = "创建会话")
    @PostMapping("/session/create")
    public Result<ChatSessionVO> createSession(@RequestBody @Valid ChatSessionCreateDTO dto) {
        ChatSession session = chatSessionService.create(
                dto.getUserId(), dto.getTitle(), dto.getSessionType(), dto.getKnowledgeId());
        return Result.success(toSessionVO(session));
    }

    @Operation(summary = "会话列表")
    @GetMapping("/session/list")
    public Result<List<ChatSessionVO>> listSessions(
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId) {
        List<ChatSession> list = chatSessionService.listByUserId(userId);
        return Result.success(list.stream().map(this::toSessionVO).collect(Collectors.toList()));
    }

    @Operation(summary = "会话详情")
    @GetMapping("/session/{sessionId}")
    public Result<ChatSessionVO> getSession(
            @Parameter(description = "业务会话ID") @PathVariable("sessionId") @NotBlank String sessionId) {
        ChatSession session = chatSessionService.getBySessionId(sessionId);
        if (session == null) {
            return Result.fail(org.grayray.aiassistant.common.result.ResultCode.NOT_FOUND, "会话不存在");
        }
        return Result.success(toSessionVO(session));
    }

    @Operation(summary = "重命名会话")
    @PostMapping("/session/rename")
    public Result<Void> renameSession(@RequestBody @Valid ChatSessionRenameDTO dto) {
        chatSessionService.rename(dto.getSessionId(), dto.getTitle());
        return Result.success();
    }

    @Operation(summary = "删除会话（逻辑删除）")
    @PostMapping("/session/delete")
    public Result<Void> deleteSession(@RequestBody @Valid ChatSessionDeleteDTO dto) {
        chatSessionService.delete(dto.getSessionId());
        return Result.success();
    }

    // ==================== 消息接口 ====================

    @Operation(summary = "发送消息（同步）")
    @PostMapping("/send")
    public Result<ChatMessageVO> send(@RequestBody @Valid ChatSendRequestDTO dto) {
        ChatSendResult result = chatService.send(dto);
        return Result.success(toMessageVO(result.getAiMessage(), dto.getSessionId(), result.getCitations()));
    }

    @Operation(summary = "发送消息（SSE 流式）")
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(
            @Parameter(description = "业务会话ID") @RequestParam("sessionId") @NotBlank String sessionId,
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId,
            @Parameter(description = "消息内容") @RequestParam("content") @NotBlank @Size(max = 10000, message = "消息内容不能超过10000字") String content,
            @Parameter(description = "系统提示词") @RequestParam(value = "systemPrompt", required = false) String systemPrompt) {
        return chatService.sendStream(sessionId, userId, content, systemPrompt);
    }

    @Operation(summary = "查询消息列表")
    @GetMapping("/messages")
    public Result<List<ChatMessageVO>> listMessages(
            @Parameter(description = "业务会话ID") @RequestParam("sessionId") @NotBlank String sessionId,
            @Parameter(description = "用户ID") @RequestParam("userId") @NotNull Long userId) {
        List<ChatMessage> list = chatService.listMessages(sessionId, userId);
        return Result.success(list.stream()
                .map(m -> toMessageVO(m, sessionId, toCitationVOs(m.getCitations())))
                .collect(Collectors.toList()));
    }

    // ==================== VO 转换 ====================

    private ChatSessionVO toSessionVO(ChatSession s) {
        ChatSessionVO vo = new ChatSessionVO();
        vo.setId(s.getId());
        vo.setSessionId(s.getSessionId());
        vo.setUserId(s.getUserId());
        vo.setTitle(s.getTitle());
        vo.setSessionType(s.getSessionType());
        vo.setModelName(s.getModelName());
        vo.setKnowledgeId(s.getKnowledgeId());
        vo.setCreateTime(s.getCreateTime());
        vo.setUpdateTime(s.getUpdateTime());
        return vo;
    }

    private ChatMessageVO toMessageVO(ChatMessage m, String businessSessionId, List<CitationVO> citations) {
        ChatMessageVO vo = new ChatMessageVO();
        vo.setMessageId(m.getId());
        vo.setSessionId(businessSessionId);
        vo.setRole(m.getRole());
        vo.setContent(m.getContent());
        vo.setModelName(m.getModelName());
        vo.setFinishReason(m.getFinishReason());
        vo.setMessageIndex(m.getMessageIndex());
        vo.setCreateTime(m.getCreateTime());
        vo.setCitations(citations);
        return vo;
    }

    /**
     * 将持久化引用实体列表转换为 CitationVO 列表。
     */
    private List<CitationVO> toCitationVOs(List<ChatMessageCitation> entities) {
        if (entities == null || entities.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        return entities.stream().map(c -> CitationVO.builder()
                .index(c.getIndex())
                .documentId(c.getDocumentId())
                .documentName(c.getDocumentName())
                .chapterTitle(c.getChapterTitle())
                .content(c.getContent())
                .score(c.getScore())
                .build()).collect(Collectors.toList());
    }
}
