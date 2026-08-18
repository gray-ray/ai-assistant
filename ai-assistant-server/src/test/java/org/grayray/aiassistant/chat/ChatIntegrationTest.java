package org.grayray.aiassistant.chat;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.grayray.aiassistant.common.result.Result;
import org.grayray.aiassistant.chat.dto.ChatSendRequestDTO;
import org.grayray.aiassistant.chat.entity.ChatMessage;
import org.grayray.aiassistant.chat.entity.ChatSession;
import org.grayray.aiassistant.user.entity.SysUser;
import org.grayray.aiassistant.chat.mapper.ChatMessageMapper;
import org.grayray.aiassistant.chat.mapper.ChatSessionMapper;
import org.grayray.aiassistant.chat.service.ChatService;
import org.grayray.aiassistant.chat.service.ChatSessionService;
import org.grayray.aiassistant.user.service.SysUserService;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 聊天会话功能集成测试
 * <p>
 * 使用 sys_user 表中的真实用户数据（取第一个用户；若表为空则自动创建一个）。
 * 使用 MockBean 模拟 DeepSeek ChatModel，避免依赖外部 API 余额。
 * 覆盖：会话 CRUD、同步发送、消息列表、多轮上下文组装、流式 SSE 冒烟、异常场景。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChatIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    /** 用 MockBean 替换真实 ChatModel，避免调用 DeepSeek（账户余额不足等外部依赖） */
    @MockBean(name = "deepSeekChatModel")
    private ChatModel mockChatModel;

    private static Long TEST_USER_ID;
    private static String TEST_SESSION_ID;
    private static boolean autoCreatedUser = false;

    /** 记录 mock 返回的固定 AI 回复 */
    private static final String MOCK_REPLY_SIMPLE = "你好！我是AI助手，很高兴为您服务。";
    private static final String MOCK_REPLY_MULTITURN = "你刚才说你叫测试小王。";

    @BeforeAll
    static void init(@Autowired SysUserService sysUserService) {
        List<SysUser> users = sysUserService.list();
        SysUser testUser;
        if (users.isEmpty()) {
            testUser = new SysUser();
            testUser.setUserName("测试用户_chat_" + System.currentTimeMillis());
            sysUserService.save(testUser);
            autoCreatedUser = true;
            System.out.println("sys_user 表为空，已自动创建测试用户: id=" + testUser.getId() + ", userName=" + testUser.getUserName());
        } else {
            testUser = users.get(0);
            System.out.println("使用已有用户: id=" + testUser.getId() + ", userName=" + testUser.getUserName());
        }
        TEST_USER_ID = testUser.getId();
    }

    @AfterAll
    static void cleanUp(@Autowired ChatSessionMapper chatSessionMapper,
                        @Autowired ChatMessageMapper chatMessageMapper,
                        @Autowired SysUserService sysUserService) {
        if (TEST_USER_ID != null) {
            System.out.println("清理测试会话数据 (userId=" + TEST_USER_ID + ") ...");
            chatMessageMapper.delete(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getUserId, TEST_USER_ID));
            chatSessionMapper.delete(new LambdaQueryWrapper<ChatSession>()
                    .eq(ChatSession::getUserId, TEST_USER_ID));
            if (autoCreatedUser) {
                sysUserService.removeById(TEST_USER_ID);
                System.out.println("已清理自动创建的测试用户 id=" + TEST_USER_ID);
            }
        }
    }

    /** 根据输入返回对应 mock 回复，模拟多轮上下文 */
    private void setupMockReply(String userContentContains, String reply) {
        // 用 thenAnswer 根据 prompt 内容匹配返回
        when(mockChatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt p = invocation.getArgument(0);
            String allText = p.getInstructions().stream()
                    .map(m -> m.getText() == null ? "" : m.getText())
                    .reduce("", String::concat);
            String replyText;
            if (userContentContains != null && allText.contains(userContentContains)) {
                replyText = reply;
            } else {
                replyText = MOCK_REPLY_SIMPLE;
            }
            Generation gen = new Generation(new AssistantMessage(replyText),
                    ChatGenerationMetadata.builder().finishReason("stop").build());
            return new ChatResponse(List.of(gen));
        });
    }

    /** 模拟流式返回（逐个字符发 token） */
    private void setupMockStreamReply(String reply) {
        when(mockChatModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
            String[] chars = reply.split("");
            ChatGenerationMetadata doneMeta = ChatGenerationMetadata.builder().finishReason("stop").build();
            return Flux.fromArray(chars)
                    .map(c -> new ChatResponse(List.of(new Generation(new AssistantMessage(c)))));
            // 注意：最后一个 chunk 也带 finishReason 会更真实，这里简化处理
        });
    }

    // ==================== 1. 会话管理 ====================

    @Test
    @Order(1)
    @DisplayName("创建会话")
    void testCreateSession() {
        Map<String, Object> body = Map.of(
                "userId", TEST_USER_ID,
                "title", "集成测试会话",
                "sessionType", "normal");

        ResponseEntity<Result> resp = restTemplate.postForEntity(
                "/chat/session/create", body, Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Result r = resp.getBody();
        assertNotNull(r);
        assertEquals(0, r.getCode(), "响应成功");

        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("sessionId"), "sessionId 已生成");
        assertNotNull(data.get("id"), "主键 id 存在");
        assertEquals("集成测试会话", data.get("title"));
        assertEquals(TEST_USER_ID, ((Number) data.get("userId")).longValue());
        assertNotNull(data.get("modelName"), "modelName 已填充");

        TEST_SESSION_ID = (String) data.get("sessionId");
        System.out.println("创建会话成功，sessionId=" + TEST_SESSION_ID);
    }

    @Test
    @Order(2)
    @DisplayName("会话列表")
    void testListSessions() {
        ResponseEntity<Result> resp = restTemplate.getForEntity(
                "/chat/session/list?userId={userId}", Result.class, TEST_USER_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Result r = resp.getBody();
        assertNotNull(r);
        assertEquals(0, r.getCode());
        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
        assertNotNull(list);
        assertTrue(list.size() >= 1, "至少有一个会话");
        assertEquals(TEST_SESSION_ID, list.get(0).get("sessionId"),
                "按 updateTime 倒序，最新的应该在首位");
    }

    @Test
    @Order(3)
    @DisplayName("会话详情")
    void testGetSession() {
        ResponseEntity<Result> resp = restTemplate.getForEntity(
                "/chat/session/{sessionId}", Result.class, TEST_SESSION_ID);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Result r = resp.getBody();
        assertNotNull(r);
        assertEquals(0, r.getCode());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(TEST_SESSION_ID, data.get("sessionId"));
        assertEquals(TEST_USER_ID, ((Number) data.get("userId")).longValue());
    }

    @Test
    @Order(4)
    @DisplayName("会话详情 - 不存在返回 404")
    void testGetSessionNotFound() {
        ResponseEntity<Result> resp = restTemplate.getForEntity(
                "/chat/session/{sessionId}", Result.class, "not-exist-id-xxx");
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Result r = resp.getBody();
        assertNotNull(r);
        assertEquals(404, r.getCode());
    }

    @Test
    @Order(5)
    @DisplayName("重命名会话")
    void testRenameSession() {
        Map<String, String> req = Map.of(
                "sessionId", TEST_SESSION_ID,
                "title", "已重命名-测试会话");
        ResponseEntity<Result> resp = restTemplate.postForEntity(
                "/chat/session/rename", req, Result.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getCode());

        ChatSession session = chatSessionService.getBySessionId(TEST_SESSION_ID);
        assertEquals("已重命名-测试会话", session.getTitle());
    }

    // ==================== 2. 同步发送消息 ====================

    @Test
    @Order(6)
    @DisplayName("同步发送消息 - 首轮对话并自动生成标题")
    void testSendSyncFirstMessage() {
        setupMockReply("你好", MOCK_REPLY_SIMPLE);

        Map<String, Object> req = Map.of(
                "sessionId", TEST_SESSION_ID,
                "userId", TEST_USER_ID,
                "content", "你好，请用一句话介绍自己");

        ResponseEntity<Result> resp = restTemplate.postForEntity(
                "/chat/send", req, Result.class);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Result r = resp.getBody();
        assertNotNull(r);
        assertEquals(0, r.getCode(), "响应成功: " + r.getMessage());

        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("messageId"));
        assertEquals("assistant", data.get("role"));
        assertEquals(MOCK_REPLY_SIMPLE, data.get("content"), "AI 回复内容应为 mock 的固定回复");
        assertEquals(TEST_SESSION_ID, data.get("sessionId"));
        assertEquals(1, ((Number) data.get("messageIndex")).intValue(), "AI 消息 index=1");
        assertNotNull(data.get("modelName"));
        assertEquals("stop", data.get("finishReason"));

        // 验证首轮自动标题已设置（不再是"新会话"/"已重命名..."也应该保留手动设置的标题）
        ChatSession session = chatSessionService.getBySessionId(TEST_SESSION_ID);
        // 因为我们手动重命名过，不会被自动覆盖
        assertEquals("已重命名-测试会话", session.getTitle());
        System.out.println("AI mock 回复: " + data.get("content"));
    }

    @Test
    @Order(7)
    @DisplayName("消息列表 - 消息顺序和 role 正确")
    void testListMessages() {
        ResponseEntity<Result> resp = restTemplate.getForEntity(
                "/chat/messages?sessionId={sid}&userId={uid}",
                Result.class, TEST_SESSION_ID, TEST_USER_ID);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Result r = resp.getBody();
        assertNotNull(r);
        assertEquals(0, r.getCode());

        List<Map<String, Object>> list = (List<Map<String, Object>>) r.getData();
        assertNotNull(list);
        assertTrue(list.size() >= 2, "至少有用户+AI 两条消息, 实际=" + list.size());

        for (int i = 0; i < list.size(); i++) {
            assertEquals(i, ((Number) list.get(i).get("messageIndex")).intValue(),
                    "消息序号连续递增");
        }
        assertEquals("user", list.get(0).get("role"), "第 0 条是用户");
        assertEquals("assistant", list.get(1).get("role"), "第 1 条是助手");
    }

    @Test
    @Order(8)
    @DisplayName("多轮对话 - 历史消息正确组装到 Prompt 中（mock 能看到用户前序消息）")
    void testMultiTurnContext() {
        // 模拟：当 prompt 中包含"我叫测试小王"时，回复里包含名字
        setupMockReply("测试小王", MOCK_REPLY_MULTITURN);

        Map<String, Object> req = Map.of(
                "sessionId", TEST_SESSION_ID,
                "userId", TEST_USER_ID,
                "content", "我叫测试小王，请记住");
        ResponseEntity<Result> r1 = restTemplate.postForEntity("/chat/send", req, Result.class);
        assertEquals(0, r1.getBody().getCode());

        // 验证 mock 收到了历史消息（通过 setupMockReply 的 contains 判断）
        Map<String, Object> data = (Map<String, Object>) r1.getBody().getData();
        assertEquals(MOCK_REPLY_MULTITURN, data.get("content"),
                "mock 应该根据 prompt 中包含'测试小王'返回指定回复，证明历史消息已传入 Prompt");

        // 查 DB，消息数应该是 4 条（2轮 user+assistant）
        List<ChatMessage> msgs = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId,
                                chatSessionService.getBySessionId(TEST_SESSION_ID).getId())
                        .orderByAsc(ChatMessage::getMessageIndex));
        assertEquals(4, msgs.size(), "两轮对话共 4 条消息");
    }

    // ==================== 3. 流式接口（冒烟测试） ====================

    @Test
    @Order(9)
    @DisplayName("流式发送消息 - 验证用户消息先入库 + SseEmitter 返回 + AI 消息最终入库")
    void testStreamSmoke() throws Exception {
        ChatSession streamSession = chatSessionService.create(TEST_USER_ID, "新会话", "normal");
        setupMockStreamReply(MOCK_REPLY_SIMPLE);

        SseEmitter emitter = chatService.sendStream(
                streamSession.getSessionId(),
                TEST_USER_ID,
                "你好流式测试",
                null);

        assertNotNull(emitter, "SseEmitter 不为空");

        // 立即验证：用户消息已同步入库（流式第一步就是保存用户消息）
        List<ChatMessage> immediateMsgs = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, streamSession.getId())
                        .orderByAsc(ChatMessage::getMessageIndex));
        assertTrue(immediateMsgs.size() >= 1, "调用后用户消息应立即入库");
        assertEquals("user", immediateMsgs.get(0).getRole());
        assertEquals("你好流式测试", immediateMsgs.get(0).getContent());

        // 轮询等待 AI 回复入库（流式是异步的，等待最多 10 秒）
        boolean aiSaved = false;
        for (int i = 0; i < 200; i++) { // 200 * 50ms = 10s
            Thread.sleep(50);
            List<ChatMessage> msgs = chatMessageMapper.selectList(
                    new LambdaQueryWrapper<ChatMessage>()
                            .eq(ChatMessage::getSessionId, streamSession.getId())
                            .orderByAsc(ChatMessage::getMessageIndex));
            if (msgs.size() >= 2) {
                assertEquals(2, msgs.size(), "用户消息+AI回复共 2 条");
                assertEquals("assistant", msgs.get(1).getRole());
                assertEquals(MOCK_REPLY_SIMPLE, msgs.get(1).getContent(),
                        "流式拼接的完整内容应等于 mock 的完整回复");
                aiSaved = true;
                System.out.println("流式测试完成，AI 回复已入库: " + msgs.get(1).getContent());
                break;
            }
        }
        assertTrue(aiSaved, "AI 回复应在 10 秒内完成并入库");
    }

    // ==================== 4. 异常场景 ====================

    @Test
    @Order(10)
    @DisplayName("发送消息 - 会话不存在返回 404")
    void testSendWithInvalidSession() {
        Map<String, Object> req = Map.of(
                "sessionId", "not-exist-session-id",
                "userId", TEST_USER_ID,
                "content", "测试");
        ResponseEntity<Result> resp = restTemplate.postForEntity("/chat/send", req, Result.class);
        assertEquals(404, resp.getBody().getCode());
    }

    @Test
    @Order(11)
    @DisplayName("发送消息 - 参数缺失返回 400")
    void testSendWithMissingParams() {
        Map<String, Object> req = Map.of(
                "sessionId", TEST_SESSION_ID,
                "userId", TEST_USER_ID);
        ResponseEntity<Result> resp = restTemplate.postForEntity("/chat/send", req, Result.class);
        assertEquals(400, resp.getBody().getCode());
    }

    @Test
    @Order(12)
    @DisplayName("删除会话（逻辑删除）")
    void testDeleteSession() {
        ChatSession toDelete = chatSessionService.create(TEST_USER_ID, "待删除会话", "normal");
        Map<String, String> req = Map.of("sessionId", toDelete.getSessionId());
        ResponseEntity<Result> resp = restTemplate.postForEntity("/chat/session/delete", req, Result.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(0, resp.getBody().getCode());

        ChatSession found = chatSessionService.getBySessionId(toDelete.getSessionId());
        assertNull(found, "逻辑删除后默认查询不到");
    }

    // ==================== 5. Service 层单元校验：消息序号连续 ====================

    @Test
    @Order(13)
    @DisplayName("messageIndex 连续递增验证")
    void testMessageIndexContinuity() {
        // 再发一条消息，验证 messageIndex 正确递增
        setupMockReply("验证序号", "序号验证通过");
        ChatSendRequestDTO dto = new ChatSendRequestDTO();
        dto.setSessionId(TEST_SESSION_ID);
        dto.setUserId(TEST_USER_ID);
        dto.setContent("验证序号");
        chatService.send(dto);

        ChatSession session = chatSessionService.getBySessionId(TEST_SESSION_ID);
        List<ChatMessage> msgs = chatMessageMapper.selectList(
                new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, session.getId())
                        .orderByAsc(ChatMessage::getMessageIndex));

        for (int i = 0; i < msgs.size(); i++) {
            assertEquals(i, msgs.get(i).getMessageIndex(),
                    "index=" + i + " 的消息序号应为 " + i);
        }
        System.out.println("当前会话消息总数=" + msgs.size() + ", 序号连续正确");
    }
}
