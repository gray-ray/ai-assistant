package org.grayray.aiassistant.document.service.impl;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.document.model.TextChunk;
import org.grayray.aiassistant.document.service.TextChunkService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文本切片实现
 * <p>
 * 分层切分规则：
 * ③ 按章节切分（识别标题行）
 * ④ 按段落切分（空行分隔）
 * ⑤ 按 Token 长度切分（超过阈值的段落再切）
 * ⑥ Overlap 重叠（相邻 chunk 重叠部分 token）
 * ⑦ 添加 Metadata（documentId, chapterTitle, chunkIndex...）
 */
@Slf4j
@Service
public class TextChunkServiceImpl implements TextChunkService {

    /** 每个 chunk 的目标 token 数 */
    private static final int CHUNK_SIZE = 500;

    /** 相邻 chunk 的重叠 token 数 */
    private static final int CHUNK_OVERLAP = 50;

    /** 最小 chunk 字符数，过短的段落会被并入上一段或丢弃 */
    private static final int MIN_CHUNK_CHARS = 20;

    /** 章节标题正则  这个后续需要确定下面的规则是否正确*/
    private static final List<Pattern> CHAPTER_PATTERNS = List.of(
            // 第X章/节/篇/回：第一章、第3节、第十二篇
            Pattern.compile("^\\s*第\\s*[一二三四五六七八九十百千零〇0-9]+\\s*[章节篇回部卷][:：、.\\s]?.*$"),
            // Markdown 标题：# Title / ## Title
            Pattern.compile("^\\s*#{1,6}\\s+.*$"),
            // 数字编号标题：1. xxx / 1.2 xxx / 1、xxx
            Pattern.compile("^\\s*\\d+(\\.\\d+)*[\\.、\\s]\\S+.*$"),
            // 中文数字+顿号：一、xxx / 二、xxx
            Pattern.compile("^\\s*[一二三四五六七八九十]+[、.\\s]\\S+.*$")
    );

    /** 句末标点（用于 token 切分处尽量断在句末） */
    private static final String SENTENCE_END_CHARS = "。！？.!?；;";

    private final Encoding encoding;

    public TextChunkServiceImpl() {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    @Override
    public List<TextChunk> chunk(String cleanedText, Long documentId, String documentName) {
        if (cleanedText == null || cleanedText.isBlank()) {
            return List.of();
        }

        // ③ 按章节切分
        List<Chapter> chapters = splitByChapter(cleanedText);
        log.debug("章节切分完成, 章节数={}", chapters.size());

        List<TextChunk> allChunks = new ArrayList<>();
        int globalIndex = 0;

        for (Chapter chapter : chapters) {
            // ④ 按段落切分
            List<String> paragraphs = splitByParagraph(chapter.body);

            // ⑤ 按 token 长度切分长段落
            List<String> sizedChunks = new ArrayList<>();
            for (String paragraph : paragraphs) {
                List<String> subChunks = splitByTokenSize(paragraph);
                sizedChunks.addAll(subChunks);
            }

            // ⑥ 加 Overlap
            List<String> overlapped = addOverlap(sizedChunks);

            // ⑦ 添加 Metadata
            for (int i = 0; i < overlapped.size(); i++) {
                String chunkText = overlapped.get(i);
                int tokenCount = countTokens(chunkText);
                allChunks.add(TextChunk.builder()
                        .content(chunkText)
                        .chunkIndex(globalIndex++)
                        .chapterTitle(chapter.title)
                        .chapterIndex(chapter.index)
                        .documentId(documentId)
                        .documentName(documentName)
                        .tokenCount(tokenCount)
                        .build());
            }
        }

        // 回填 totalChunks
        int total = allChunks.size();
        for (TextChunk chunk : allChunks) {
            chunk.setTotalChunks(total);
        }

        log.info("文本切片完成, documentId={}, 总chunks={}", documentId, total);
        return allChunks;
    }

    // ========== ③ 章节切分 ==========

    /**
     * 章节内部结构
     */
    private static class Chapter {
        final int index;
        final String title;
        final String body;

        Chapter(int index, String title, String body) {
            this.index = index;
            this.title = title;
            this.body = body;
        }
    }

    /**
     * 按章节切分：识别标题行，正文归属到对应章节
     */
    private List<Chapter> splitByChapter(String text) {
        String[] lines = text.split("\\r?\\n");
        List<Chapter> chapters = new ArrayList<>();

        StringBuilder currentBody = new StringBuilder();
        String currentTitle = null;
        int chapterIdx = 0;
        boolean firstChapterFound = false;

        for (String line : lines) {
            if (isChapterTitle(line)) {
                // 结束上一章节
                if (firstChapterFound || currentBody.length() > 0) {
                    chapters.add(new Chapter(
                            chapterIdx,
                            currentTitle,
                            currentBody.toString().trim()
                    ));
                    chapterIdx++;
                }
                currentTitle = line.trim();
                currentBody.setLength(0);
                firstChapterFound = true;
            } else {
                currentBody.append(line).append("\n");
            }
        }

        // 最后一个章节
        if (firstChapterFound || currentBody.length() > 0) {
            chapters.add(new Chapter(
                    chapterIdx,
                    currentTitle,
                    currentBody.toString().trim()
            ));
        }

        return chapters;
    }

    /**
     * 判断一行是否是章节标题
     */
    private boolean isChapterTitle(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        String trimmed = line.trim();
        // 标题行一般不太长
        if (trimmed.length() > 80) {
            return false;
        }
        for (Pattern p : CHAPTER_PATTERNS) {
            if (p.matcher(trimmed).matches()) {
                return true;
            }
        }
        return false;
    }

    // ========== ④ 段落切分 ==========

    /**
     * 按空行切分成段落；过短的段落并入上一段
     */
    private List<String> splitByParagraph(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        // 按一个或多个空行切分
        String[] rawParagraphs = text.split("\\n\\s*\\n");
        List<String> paragraphs = new ArrayList<>();

        for (String raw : rawParagraphs) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // 过短的段落并入上一段
            if (trimmed.length() < MIN_CHUNK_CHARS && !paragraphs.isEmpty()) {
                String last = paragraphs.remove(paragraphs.size() - 1);
                paragraphs.add(last + "\n" + trimmed);
            } else {
                paragraphs.add(trimmed);
            }
        }
        return paragraphs;
    }

    // ========== ⑤ Token 长度切分 ==========

    /**
     * 将单个段落按 token 长度切分，优先在句末断开
     */
    private List<String> splitByTokenSize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= CHUNK_SIZE) {
            return List.of(text.trim());
        }

        List<String> chunks = new ArrayList<>();
        int position = 0;
        int total = tokens.size();

        while (position < total) {
            int end = Math.min(position + CHUNK_SIZE, total);

            // 如果不是最后一块，尝试在句末标点处断开
            if (end < total) {
                // 解码当前窗口，找最后一个句末标点
                IntArrayList windowTokens = new IntArrayList(end - position);
                for (int i = position; i < end; i++) {
                    windowTokens.add(tokens.get(i));
                }
                String windowText = encoding.decode(windowTokens);

                int lastPunct = -1;
                for (int i = windowText.length() - 1; i >= MIN_CHUNK_CHARS; i--) {
                    if (SENTENCE_END_CHARS.indexOf(windowText.charAt(i)) >= 0
                            || windowText.charAt(i) == '\n') {
                        lastPunct = i + 1; // 包含标点本身
                        break;
                    }
                }

                if (lastPunct > MIN_CHUNK_CHARS) {
                    // 按字符位置切割，重新编码找到对应的 token 结束位置
                    String cutText = windowText.substring(0, lastPunct).trim();
                    if (!cutText.isEmpty()) {
                        chunks.add(cutText);
                        // 计算切割后的 token 数，推进 position
                        int cutTokens = encoding.encode(cutText).size();
                        position += cutTokens;
                        continue;
                    }
                }
            }

            // 没找到合适的句末点（或是最后一块），直接按 token 切
            IntArrayList chunkTokens = new IntArrayList(end - position);
            for (int i = position; i < end; i++) {
                chunkTokens.add(tokens.get(i));
            }
            String chunkText = encoding.decode(chunkTokens).trim();
            if (chunkText.length() >= MIN_CHUNK_CHARS) {
                chunks.add(chunkText);
            }
            position = end;
        }

        return chunks;
    }

    // ========== ⑥ Overlap ==========

    /**
     * 给同一章节内的相邻 chunk 加 overlap
     * 后一个 chunk 的开头带上前一个 chunk 末尾的 CHUNK_OVERLAP 个 token
     */
    private List<String> addOverlap(List<String> chunks) {
        if (chunks == null || chunks.size() <= 1) {
            return chunks;
        }

        List<String> result = new ArrayList<>();
        result.add(chunks.get(0));

        for (int i = 1; i < chunks.size(); i++) {
            String prev = chunks.get(i - 1);
            String curr = chunks.get(i);

            // 取上一个 chunk 末尾的 overlap tokens
            IntArrayList prevTokens = encoding.encode(prev);
            int overlapTokensNum = Math.min(CHUNK_OVERLAP, prevTokens.size());
            if (overlapTokensNum <= 0) {
                result.add(curr);
                continue;
            }

            int start = prevTokens.size() - overlapTokensNum;
            IntArrayList overlapTok = new IntArrayList(overlapTokensNum);
            for (int j = start; j < prevTokens.size(); j++) {
                overlapTok.add(prevTokens.get(j));
            }
            String overlapText = encoding.decode(overlapTok);

            // 拼接到当前 chunk 开头
            String merged = (overlapText + "\n" + curr).trim();
            result.add(merged);
        }

        return result;
    }

    // ========== 工具方法 ==========

    private int countTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return encoding.encode(text).size();
    }
}
