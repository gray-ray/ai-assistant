package org.grayray.aiassistant.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.grayray.aiassistant.chat.dto.ChatSendRequestDTO;
import org.grayray.aiassistant.chat.model.ChatSendResult;
import org.grayray.aiassistant.chat.vo.ChatStreamEvent;
import org.grayray.aiassistant.chat.vo.CitationVO;
import org.grayray.aiassistant.chat.entity.ChatMessage;
import org.grayray.aiassistant.chat.entity.ChatMessageCitation;
import org.grayray.aiassistant.chat.entity.ChatSession;
import org.grayray.aiassistant.chat.mapper.ChatMessageMapper;
import org.grayray.aiassistant.chat.service.ChatService;
import org.grayray.aiassistant.chat.service.ChatSessionService;
import org.grayray.aiassistant.common.exception.BusinessException;
import org.grayray.aiassistant.common.result.ResultCode;
import org.grayray.aiassistant.rag.context.AssembledContext;
import org.grayray.aiassistant.rag.context.Citation;
import org.grayray.aiassistant.rag.context.ContextAssemblyService;
import org.grayray.aiassistant.rag.groundedness.GroundednessCheckResult;
import org.grayray.aiassistant.rag.groundedness.GroundednessChecker;
import org.grayray.aiassistant.rag.groundedness.GroundednessProperties;
import org.grayray.aiassistant.rag.model.RoutedQuery;
import org.grayray.aiassistant.rag.prompt.RagPromptService;
import org.grayray.aiassistant.rag.rerank.RerankResult;
import org.grayray.aiassistant.rag.rerank.RerankService;
import org.grayray.aiassistant.rag.rerank.RerankedChunk;
import org.grayray.aiassistant.rag.retrieval.RetrievedChunk;
import org.grayray.aiassistant.rag.retrieval.VectorSearchRequest;
import org.grayray.aiassistant.rag.retrieval.VectorSearchResult;
import org.grayray.aiassistant.rag.retrieval.VectorSearchService;
import org.grayray.aiassistant.rag.router.QueryRouterService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatServiceImpl.class);

    private static final long SSE_TIMEOUT_MS = 120_000L;

    @Resource(name = "deepSeekChatModel")
    private ChatModel chatModel;

    @Resource(name = "deepSeekChatModel")
    private StreamingChatModel streamingChatModel;

    @Resource
    private ChatSessionService chatSessionService;

    @Resource
    private ChatMessageMapper chatMessageMapper;

    @Resource
    private QueryRouterService queryRouterService;

    @Resource
    private VectorSearchService vectorSearchService;

    @Resource
    private RerankService rerankService;

    @Resource
    private ContextAssemblyService contextAssemblyService;

    @Resource
    private RagPromptService ragPromptService;

    @Resource
    private GroundednessChecker groundednessChecker;

    @Resource
    private GroundednessProperties groundednessProperties;

    @Resource(name = "chatStreamExecutor")
    private ThreadPoolTaskExecutor chatStreamExecutor;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChatSendResult send(ChatSendRequestDTO dto) {
        // 1. 校验会话
        ChatSession session = validateSession(dto.getSessionId(), dto.getUserId());

        // 2. 查询当前最大 message_index
        Integer maxIdx = findMaxMessageIndex(session.getId());
        int userIdx = (maxIdx == null) ? 0 : maxIdx + 1;
        int aiIdx = userIdx + 1;

        // 3. 保存用户消息
        ChatMessage userMsg = buildUserMessage(session.getId(), dto.getUserId(), dto.getContent(), userIdx);
        chatMessageMapper.insert(userMsg);

        // 4. 首轮自动设置标题
        if (isDefaultTitle(session) && userIdx == 0) {
            autoSetTitle(session, dto.getContent());
        }

        // 用于累积"会导致回答不基于文档"的关键降级原因（Rerank 降级不影响可信度，不纳入）
        StringBuilder degradationReasons = new StringBuilder();

        // 5. Query Router：路由分类 + 预处理（重写/扩展）
        List<String> searchQueries = null;
        try {
            RoutedQuery routed = queryRouterService.route(session.getId(), dto.getUserId(), dto.getContent());
            log.info("[ChatService] 路由完成: type={}, queriesCount={}, reason={}",
                    routed.getType().getValue(), routed.getQueries().size(), routed.getRouteReason());
            searchQueries = routed.getQueries();
        } catch (Exception e) {
            // 路由异常：降级为使用原 query 继续检索（仅记录日志，不阻断流程）
            log.warn("[ChatService] QueryRouter 异常，使用原 query 继续检索: {}", e.getMessage());
            searchQueries = List.of(dto.getContent());
        }

        // 6. 向量检索 & TopK 召回（检索异常不阻断主流程，但会导致空上下文）
        VectorSearchResult searchResult = null;
        if (searchQueries != null && !searchQueries.isEmpty()) {
            try {
                searchResult = vectorSearchService.search(VectorSearchRequest.builder()
                        .queries(searchQueries)
                        .build());
                log.info("[ChatService] 向量检索完成: totalHits={}, returned={}, costMs={}",
                        searchResult.getTotalHitCount(),
                        searchResult.getChunks().size(),
                        searchResult.getCostMs());
            } catch (Exception e) {
                log.warn("[ChatService] 向量检索异常，降级为空上下文: {}", e.getMessage());
                degradationReasons.append("检索服务异常");
            }
        }

        // 6.5 Rerank 重排（对检索结果精细打分重排序；异常不阻断主流程，降级为原始顺序，不告知用户）
        if (searchResult != null && rerankService.isAvailable()) {
            try {
                RerankResult rerankResult = rerankService.rerank(dto.getContent(), searchResult.getChunks());
                searchResult = VectorSearchResult.builder()
                        .chunks(toRetrievedChunks(rerankResult.getChunks()))
                        .totalHitCount(searchResult.getTotalHitCount())
                        .queryCount(searchResult.getQueryCount())
                        .costMs(searchResult.getCostMs() + rerankResult.getCostMs())
                        .build();
                log.info("[ChatService] Rerank 完成: input={}, output={}, topScore={}, costMs={}",
                        rerankResult.getTotalInputCount(), rerankResult.getOutputCount(),
                        String.format("%.4f", rerankResult.getTopScore()), rerankResult.getCostMs());
            } catch (Exception e) {
                log.warn("[ChatService] Rerank 异常，使用原始检索结果: {}", e.getMessage());
            }
        }

        // 7. 上下文组装 + Prompt 构建
        List<RetrievedChunk> chunks = (searchResult != null && !searchResult.isEmpty())
                ? searchResult.getChunks() : Collections.emptyList();
        AssembledContext context;
        try {
            context = contextAssemblyService.assemble(chunks, dto.getContent());
        } catch (Exception e) {
            log.error("[ChatService] 上下文组装异常，降级为空上下文: {}", e.getMessage(), e);
            degradationReasons.append(degradationReasons.length() > 0 ? "、上下文组装异常" : "上下文组装异常");
            context = AssembledContext.empty(org.grayray.aiassistant.rag.context.ContextFormat.NUMBERED);
        }
        List<ChatMessage> history = loadHistoryMessages(session.getId());
        List<Message> promptMessages = ragPromptService.buildRagMessages(
                dto.getSystemPrompt(), context, dto.getContent(), history);
        List<CitationVO> citations = toCitationVOs(context);

        log.info("[ChatService] 上下文组装完成: chunks={}, tokens={}, costMs(含检索)={}",
                context.getChunkCount(), context.getTotalTokens(),
                searchResult != null ? searchResult.getCostMs() : 0);

        // 8. 调用同步 ChatModel
        ChatResponse response = chatModel.call(new Prompt(promptMessages));

        // 8. 解析 AI 回复
        String aiContent = "";
        String finishReason = null;
        String modelName = session.getModelName();
        Generation gen = response.getResult();
        if (gen != null) {
            AssistantMessage out = gen.getOutput();
            if (out != null && out.getText() != null) {
                aiContent = out.getText();
            }
            if (gen.getMetadata() != null) {
                finishReason = gen.getMetadata().getFinishReason();
            }
        }

        // 8.5 如果发生了关键降级（空上下文），在回答前告知用户
        if (degradationReasons.length() > 0 && (context == null || context.isEmpty())) {
            aiContent = "⚠️ " + degradationReasons + "，以下回答不基于文档。\n\n" + aiContent;
            log.warn("[ChatService] 回答带降级提示: reasons={}", degradationReasons);
        }

        // 8.6 Groundedness Check（仅当有 RAG 上下文、启用校验，且未发生降级导致空上下文时执行）
        if (context != null && !context.isEmpty()
                && groundednessProperties.isEnabled()
                && degradationReasons.length() == 0) {
            aiContent = runGroundednessCheck(context.getText(), aiContent);
        }

        // 9. 保存 AI 回复（携带引用来源一并落库）
        ChatMessage aiMsg = buildAiMessage(session.getId(), dto.getUserId(), aiContent, aiIdx, modelName, finishReason);
        aiMsg.setCitations(toEntityCitations(citations));
        chatMessageMapper.insert(aiMsg);

        return ChatSendResult.builder()
                .aiMessage(aiMsg)
                .citations(citations)
                .build();
    }

    @Override
    public SseEmitter sendStream(String sessionId, Long userId, String content, String systemPrompt) {
        // 1. 校验 & 准备（同步阶段，失败会走 GlobalExceptionHandler）
        ChatSession session = validateSession(sessionId, userId);

        // 2. 查询最新消息 index
        Integer maxIdx = findMaxMessageIndex(session.getId());
        int userIdx = (maxIdx == null) ? 0 : maxIdx + 1;
        int aiIdx = userIdx + 1;

        // 3. 立即保存用户消息
        ChatMessage userMsg = buildUserMessage(session.getId(), userId, content, userIdx);
        chatMessageMapper.insert(userMsg);

        // 4. 首轮自动标题
        if (isDefaultTitle(session) && userIdx == 0) {
            autoSetTitle(session, content);
        }

        // 5. Query Router：路由分类 + 预处理（重写/扩展），结果记日志；异常降级为原 query 继续
        List<String> searchQueries = null;
        try {
            RoutedQuery routed = queryRouterService.route(session.getId(), userId, content);
            log.info("[ChatService] 路由完成(stream): type={}, queriesCount={}, reason={}",
                    routed.getType().getValue(), routed.getQueries().size(), routed.getRouteReason());
            searchQueries = routed.getQueries();
        } catch (Exception e) {
            log.warn("[ChatService] QueryRouter 异常(stream)，使用原 query 继续检索: {}", e.getMessage());
            searchQueries = List.of(content);
        }

        // 6. 向量检索 & TopK 召回（检索异常不阻断主流程，但会导致空上下文）
        VectorSearchResult searchResult = null;
        StringBuilder degradationReasons = new StringBuilder();
        if (searchQueries != null && !searchQueries.isEmpty()) {
            try {
                searchResult = vectorSearchService.search(VectorSearchRequest.builder()
                        .queries(searchQueries)
                        .build());
                log.info("[ChatService] 向量检索完成(stream): totalHits={}, returned={}, costMs={}",
                        searchResult.getTotalHitCount(),
                        searchResult.getChunks().size(),
                        searchResult.getCostMs());
            } catch (Exception e) {
                log.warn("[ChatService] 向量检索异常(stream)，降级为空上下文: {}", e.getMessage());
                degradationReasons.append("检索服务异常");
            }
        }

        // 6.5 Rerank 重排（对检索结果精细打分重排序；异常不阻断主流程，降级为原始顺序，不告知用户）
        if (searchResult != null && rerankService.isAvailable()) {
            try {
                RerankResult rerankResult = rerankService.rerank(content, searchResult.getChunks());
                searchResult = VectorSearchResult.builder()
                        .chunks(toRetrievedChunks(rerankResult.getChunks()))
                        .totalHitCount(searchResult.getTotalHitCount())
                        .queryCount(searchResult.getQueryCount())
                        .costMs(searchResult.getCostMs() + rerankResult.getCostMs())
                        .build();
                log.info("[ChatService] Rerank 完成(stream): input={}, output={}, topScore={}, costMs={}",
                        rerankResult.getTotalInputCount(), rerankResult.getOutputCount(),
                        String.format("%.4f", rerankResult.getTopScore()), rerankResult.getCostMs());
            } catch (Exception e) {
                log.warn("[ChatService] Rerank 异常(stream)，使用原始检索结果: {}", e.getMessage());
            }
        }

        // 7. 上下文组装 + Prompt 构建
        List<RetrievedChunk> chunks = (searchResult != null && !searchResult.isEmpty())
                ? searchResult.getChunks() : Collections.emptyList();
        AssembledContext context;
        try {
            context = contextAssemblyService.assemble(chunks, content);
        } catch (Exception e) {
            log.error("[ChatService] 上下文组装异常(stream)，降级为空上下文: {}", e.getMessage(), e);
            degradationReasons.append(degradationReasons.length() > 0 ? "、上下文组装异常" : "上下文组装异常");
            context = AssembledContext.empty(org.grayray.aiassistant.rag.context.ContextFormat.NUMBERED);
        }
        List<ChatMessage> history = loadHistoryMessages(session.getId());
        List<Message> promptMessages = ragPromptService.buildRagMessages(
                systemPrompt, context, content, history);
        List<CitationVO> citations = toCitationVOs(context);

        log.info("[ChatService] 上下文组装完成(stream): chunks={}, tokens={}, costMs(含检索)={}",
                context.getChunkCount(), context.getTotalTokens(),
                searchResult != null ? searchResult.getCostMs() : 0);

        // 8. 创建 SseEmitter
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            log.warn("SSE 响应超时，sessionId={}", sessionId);
            try {
                emitter.send(SseEmitter.event().name("error")
                        .data(ChatStreamEvent.error(ResultCode.AI_STREAM_ERROR.getCode(), "响应超时")));
            } catch (IOException ignored) {
            }
            emitter.complete();
        });
        emitter.onError(e -> log.error("SSE 连接异常, sessionId={}", sessionId, e));

        // 7.5 关键降级提示（空上下文时告知用户"回答不基于文档"）
        String warningPrefix = null;
        if (degradationReasons.length() > 0 && (context == null || context.isEmpty())) {
            warningPrefix = "⚠️ " + degradationReasons + "，以下回答不基于文档。\n\n";
            log.warn("[ChatService] 回答(stream)带降级提示: reasons={}", degradationReasons);
        }
        final String streamWarningPrefix = warningPrefix; // 用于 lambda

        // 7.6 是否需要做 groundedness check（只有非空上下文且启用校验才做）
        final boolean shouldCheckGroundedness = context != null && !context.isEmpty()
                && groundednessProperties.isEnabled()
                && degradationReasons.length() == 0;
        final String contextTextForCheck = (context != null) ? context.getText() : null;

        // 8. 异步线程执行流式调用
        String modelName = session.getModelName();
        Long dbSessionId = session.getId();

        chatStreamExecutor.execute(() -> {
            StringBuilder fullContent = new StringBuilder();
            final int[] tokenIndex = {0};
            final String[] finishReason = {null};
            try {
                // 7.1 发送 start 事件
                safeSend(emitter, SseEmitter.event().name("start")
                        .data(ChatStreamEvent.start(sessionId, userMsg.getId(), userIdx)));

                // 7.1.5 若有降级提示，先作为首条 message 发出（流式场景下用户能立刻看到）
                if (streamWarningPrefix != null) {
                    fullContent.append(streamWarningPrefix);
                    safeSend(emitter, SseEmitter.event().name("message")
                            .data(ChatStreamEvent.message(streamWarningPrefix, tokenIndex[0]++)));
                }

                // 7.2 调用 StreamingChatModel
                Flux<ChatResponse> flux = streamingChatModel.stream(new Prompt(promptMessages));

                flux.subscribe(
                        chunk -> {
                            try {
                                if (chunk == null || chunk.getResult() == null) {
                                    return;
                                }
                                Generation gen = chunk.getResult();
                                // 逐 token 发送
                                if (gen.getOutput() != null && gen.getOutput().getText() != null) {
                                    String token = gen.getOutput().getText();
                                    fullContent.append(token);
                                    safeSend(emitter, SseEmitter.event().name("message")
                                            .data(ChatStreamEvent.message(token, tokenIndex[0]++)));
                                }
                                // 记录 finishReason
                                if (gen.getMetadata() != null && gen.getMetadata().getFinishReason() != null) {
                                    finishReason[0] = gen.getMetadata().getFinishReason();
                                }
                            } catch (Exception e) {
                                log.error("SSE 处理 token 时异常", e);
                            }
                        },
                        err -> {
                            log.error("DeepSeek 流式调用失败, sessionId={}", sessionId, err);
                            try {
                                emitter.send(SseEmitter.event().name("error")
                                        .data(ChatStreamEvent.error(ResultCode.AI_STREAM_ERROR.getCode(),
                                                "AI 调用失败: " + err.getMessage())));
                            } catch (IOException ignored) {
                            }
                            emitter.completeWithError(err);
                        },
                        () -> {
                            try {
                                // 7.3 Groundedness Check（流式：在完整回答拼装完成后、保存前执行）
                                String finalContent = fullContent.toString();
                                if (shouldCheckGroundedness) {
                                    finalContent = runGroundednessCheck(contextTextForCheck, finalContent);
                                    // 若校验后内容被追加了标记（⚠️/头部警示），以 finalContent 为准
                                }

                                // 7.4 保存 AI 回复（包含降级提示、校验后的内容以及引用来源）
                                ChatMessage aiMsg = buildAiMessage(dbSessionId, userId,
                                        finalContent, aiIdx, modelName, finishReason[0]);
                                aiMsg.setCitations(toEntityCitations(citations));
                                chatMessageMapper.insert(aiMsg);

                                // 7.5 发送 done 事件（携带引用信息；done.fullContent 为校验后的最终文本，供前端覆盖流式内容）
                                safeSend(emitter, SseEmitter.event().name("done")
                                        .data(ChatStreamEvent.done(sessionId, aiMsg.getId(),
                                                finalContent, finishReason[0], modelName, aiIdx,
                                                citations)));
                                emitter.complete();
                            } catch (Exception e) {
                                log.error("SSE 完成处理异常", e);
                                try {
                                    emitter.send(SseEmitter.event().name("error")
                                            .data(ChatStreamEvent.error(ResultCode.AI_STREAM_ERROR.getCode(),
                                                    "保存消息异常: " + e.getMessage())));
                                } catch (IOException ignored) {
                                }
                                emitter.complete();
                            }
                        }
                );
            } catch (Exception e) {
                log.error("SSE 流式处理异常, sessionId={}", sessionId, e);
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(ChatStreamEvent.error(ResultCode.AI_STREAM_ERROR.getCode(), e.getMessage())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    @Override
    public List<ChatMessage> listMessages(String sessionId, Long userId) {
        ChatSession session = validateSession(sessionId, userId);
        return loadHistoryMessages(session.getId());
    }

    // ==================== 公共辅助方法 ====================

    /** 校验会话存在且归属正确 */
    private ChatSession validateSession(String sessionId, Long userId) {
        ChatSession session = chatSessionService.getBySessionId(sessionId);
        if (session == null) {
            throw new BusinessException(ResultCode.NOT_FOUND, "会话不存在");
        }
        if (userId != null && !session.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN, "无权访问该会话");
        }
        return session;
    }

    /** 查询当前会话最大 message_index */
    private Integer findMaxMessageIndex(Long dbSessionId) {
        ChatMessage last = chatMessageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, dbSessionId)
                .orderByDesc(ChatMessage::getMessageIndex)
                .last("LIMIT 1"));
        return last == null ? null : last.getMessageIndex();
    }

    /** 加载历史消息（按 message_index 升序） */
    private List<ChatMessage> loadHistoryMessages(Long dbSessionId) {
        return chatMessageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                .eq(ChatMessage::getSessionId, dbSessionId)
                .orderByAsc(ChatMessage::getMessageIndex));
    }

    /** 构建用户消息 */
    private ChatMessage buildUserMessage(Long dbSessionId, Long userId, String content, int index) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(dbSessionId);
        msg.setUserId(userId);
        msg.setRole("user");
        msg.setContent(content);
        msg.setMessageIndex(index);
        return msg;
    }

    /** 构建 AI 回复消息 */
    private ChatMessage buildAiMessage(Long dbSessionId, Long userId, String content, int index,
                                       String modelName, String finishReason) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(dbSessionId);
        msg.setUserId(userId);
        msg.setRole("assistant");
        msg.setContent(content);
        msg.setMessageIndex(index);
        msg.setModelName(modelName);
        msg.setFinishReason(finishReason);
        return msg;
    }

    private boolean isDefaultTitle(ChatSession session) {
        return session.getTitle() == null || "新会话".equals(session.getTitle());
    }

    private void autoSetTitle(ChatSession session, String content) {
        String title = content.length() > 20 ? content.substring(0, 20) + "..." : content;
        session.setTitle(title);
        chatSessionService.updateById(session);
    }

    /** 安全发送 SSE 事件，吞掉 IO 异常 */
    private void safeSend(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException e) {
            log.warn("SSE 发送失败，客户端可能已断开: {}", e.getMessage());
        }
    }

    /**
     * 将 RerankedChunk 列表转换回 RetrievedChunk 列表
     * <p>
     * Rerank 后需要将重排结果转回 VectorSearchResult 中的 RetrievedChunk，
     * 分数使用 rerankScore（融合后或纯 rerank 分数）覆盖 score 字段，
     * 以保证后续流程看到的是重排后的排序分数。
     */
    private List<RetrievedChunk> toRetrievedChunks(List<RerankedChunk> reranked) {
        List<RetrievedChunk> result = new ArrayList<>(reranked.size());
        for (RerankedChunk c : reranked) {
            result.add(RetrievedChunk.builder()
                    .chunkId(c.getChunkId())
                    .documentId(c.getDocumentId())
                    .documentName(c.getDocumentName())
                    .chunkIndex(c.getChunkIndex())
                    .totalChunks(c.getTotalChunks())
                    .chapterIndex(c.getChapterIndex())
                    .chapterTitle(c.getChapterTitle())
                    .content(c.getContent())
                    .tokenCount(c.getTokenCount())
                    .score(c.getRerankScore())
                    .matchedQuery(c.getMatchedQuery())
                    .hitCount(c.getHitCount())
                    .build());
        }
        return result;
    }

    /**
     * 将 AssembledContext 中的 Citation 列表转换为 CitationVO 列表（供前端展示）
     */
    private List<CitationVO> toCitationVOs(AssembledContext context) {
        if (context == null || context.isEmpty() || context.getCitations() == null) {
            return Collections.emptyList();
        }
        List<CitationVO> result = new ArrayList<>(context.getCitations().size());
        for (Citation c : context.getCitations()) {
            result.add(CitationVO.builder()
                    .index(c.getIndex())
                    .documentId(c.getDocumentId())
                    .documentName(c.getDocumentName())
                    .chapterTitle(c.getChapterTitle())
                    .content(c.getContent())
                    .score(c.getScore())
                    .build());
        }
        return result;
    }

    /**
     * 将 CitationVO 列表转换为持久化实体列表（保存到 citations_json 列）
     */
    private List<ChatMessageCitation> toEntityCitations(List<CitationVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return Collections.emptyList();
        }
        List<ChatMessageCitation> result = new ArrayList<>(vos.size());
        for (CitationVO v : vos) {
            ChatMessageCitation c = new ChatMessageCitation();
            c.setIndex(v.getIndex());
            c.setDocumentId(v.getDocumentId());
            c.setDocumentName(v.getDocumentName());
            c.setChapterTitle(v.getChapterTitle());
            c.setContent(v.getContent());
            c.setScore(v.getScore());
            result.add(c);
        }
        return result;
    }

    /**
     * 将持久化引用实体列表转换为 CitationVO 列表（历史消息返回前端时使用）
     */
    private List<CitationVO> toCitationVOsFromEntity(List<ChatMessageCitation> entities) {
        if (entities == null || entities.isEmpty()) {
            return Collections.emptyList();
        }
        List<CitationVO> result = new ArrayList<>(entities.size());
        for (ChatMessageCitation c : entities) {
            result.add(CitationVO.builder()
                    .index(c.getIndex())
                    .documentId(c.getDocumentId())
                    .documentName(c.getDocumentName())
                    .chapterTitle(c.getChapterTitle())
                    .content(c.getContent())
                    .score(c.getScore())
                    .build());
        }
        return result;
    }

    /**
     * 运行 Groundedness Check 并根据结果对 AI 回答做后处理。
     * <p>
     * - 校验通过（score ≥ threshold 且无 unsupported）→ 返回原内容
     * - 有未支撑句子且 markUnsupported=true → 在每个未支撑句子后追加 ⚠️
     * - 整体支撑度过低（score < threshold）→ 在回答头部追加"未找到依据"警示
     * - 存在引用错误 → 在回答末尾追加引用问题提示
     * - 校验异常 → failOpen：true 放行原回答（记日志），false 返回"校验失败"提示
     *
     * @param contextText 参考文档文本（AssembledContext.getText()）
     * @param aiContent   AI 原始回答
     * @return 处理后的回答文本
     */
    private String runGroundednessCheck(String contextText, String aiContent) {
        GroundednessCheckResult check;
        try {
            check = groundednessChecker.check(contextText, aiContent);
        } catch (Exception e) {
            log.warn("[ChatService] Groundedness 校验异常: {}", e.getMessage());
            if (groundednessProperties.isFailOpen()) {
                return aiContent;
            }
            return "⚠️ 回答事实校验失败，无法保证内容基于文档。\n\n" + aiContent;
        }

        if (check == null) {
            return aiContent;
        }

        boolean fullySupported = Boolean.TRUE.equals(check.getSupported())
                && (check.getScore() == null || check.getScore() >= groundednessProperties.getThreshold())
                && (check.getUnsupportedSentences() == null || check.getUnsupportedSentences().isEmpty())
                && !Boolean.TRUE.equals(check.getHasCitationErrors());

        if (fullySupported) {
            log.debug("[ChatService] Groundedness 校验通过: score={}", check.getScore());
            return aiContent;
        }

        String processed = aiContent;

        // 1) 标记未支撑的句子
        if (groundednessProperties.isMarkUnsupported()
                && check.getUnsupportedSentences() != null
                && !check.getUnsupportedSentences().isEmpty()) {
            for (GroundednessCheckResult.UnsupportedSentence us : check.getUnsupportedSentences()) {
                String sent = us.getSentence();
                if (sent == null || sent.isBlank()) continue;
                // 简单子串替换：在原句末尾追加 ⚠️（避免重复追加）
                String marker = " ⚠️";
                String target = sent.trim();
                if (!target.endsWith("⚠️") && processed.contains(target)) {
                    // 优先替换带中文标点结尾的版本
                    boolean replaced = false;
                    for (String punct : new String[]{"。", "！", "？", "；", ".", "!", "?", ";"}) {
                        String withPunct = target.endsWith(punct) ? target : target + punct;
                        if (processed.contains(withPunct) && !processed.contains(withPunct + marker)) {
                            processed = processed.replace(withPunct, withPunct + marker);
                            replaced = true;
                            break;
                        }
                    }
                    if (!replaced && !processed.contains(target + marker)) {
                        processed = processed.replace(target, target + marker);
                    }
                }
            }
        }

        // 2) 整体支撑度过低 → 头部警示
        double score = check.getScore() == null ? 0.0 : check.getScore();
        if (score < groundednessProperties.getThreshold()) {
            String header = String.format("⚠️ 经事实校验，本次回答的内容支撑度仅 %.0f%%，部分内容可能未基于文档，请谨慎参考。\n\n",
                    score * 100);
            if (!processed.startsWith("⚠️")) {
                processed = header + processed;
            }
            log.warn("[ChatService] Groundedness 支撑度过低: score={}", score);
        }

        // 3) 引用错误 → 末尾提示
        if (Boolean.TRUE.equals(check.getHasCitationErrors())
                && check.getCitationIssues() != null
                && !check.getCitationIssues().isEmpty()) {
            processed = processed + "\n\n（注：部分引用标注可能存在问题："
                    + String.join("；", check.getCitationIssues()) + "）";
        }

        log.info("[ChatService] Groundedness 校验完成: supported={}, score={}, unsupportedCount={}, citeErrs={}",
                check.getSupported(),
                String.format("%.2f", score),
                check.getUnsupportedSentences() == null ? 0 : check.getUnsupportedSentences().size(),
                Boolean.TRUE.equals(check.getHasCitationErrors()));

        return processed;
    }
}
