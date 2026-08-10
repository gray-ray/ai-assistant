package org.grayray.aiassistant.document.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.grayray.aiassistant.document.service.TextCleanService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文本清洗实现
 * 1. 去页眉/页脚（每页开头/结尾重复出现的行）
 * 2. 去页码
 * 3. 去多余空格、全角空格、Tab
 * 4. 去连续空行
 * 5. 去相邻重复标题行
 */
@Slf4j
@Service
public class TextCleanServiceImpl implements TextCleanService {

    /** 页码正则：纯数字、第N页/第N页/共N页、Page N/N of N 等 */
    private static final List<Pattern> PAGE_NUMBER_PATTERNS = Arrays.asList(
            Pattern.compile("^\\s*\\d+\\s*$"),
            Pattern.compile("^\\s*第\\s*\\d+\\s*页(\\s*共\\s*\\d+\\s*页)?\\s*$"),
            Pattern.compile("^\\s*第\\s*[\\d一二三四五六七八九十百千万]+\\s*页\\s*$"),
            Pattern.compile("^\\s*[-—–·•]*\\s*\\d+\\s*[-—–·•]*\\s*$"),
            Pattern.compile("^\\s*Page\\s+\\d+(\\s+of\\s+\\d+)?\\s*$", Pattern.CASE_INSENSITIVE)
    );

    /** 页眉页脚判定：行最大长度 */
    private static final int HEADER_FOOTER_MAX_LEN = 100;

    /** 页眉页脚判定：需要出现的最小页数比例 */
    private static final double HEADER_FOOTER_THRESHOLD = 0.5;

    @Override
    public String clean(String rawText) {
        if (rawText == null || rawText.isEmpty()) {
            return "";
        }

        // 按页分割（PDFTextStripper 默认用 \f 分页，也兼容没有 \f 的情况）
        String[] pages = rawText.split("\\f");
        if (pages.length == 0) {
            pages = new String[]{rawText};
        }

        // 1. 先按行拆分每页，识别页眉页脚
        List<List<String>> pagesLines = Arrays.stream(pages)
                .map(this::splitLines)
                .collect(Collectors.toList());

        Set<String> headerFooterLines = detectHeaderFooter(pagesLines);

        // 2. 逐页逐行清洗
        List<String> cleanedLines = new ArrayList<>();
        for (List<String> lines : pagesLines) {
            List<String> pageCleaned = new ArrayList<>();
            for (String line : lines) {
                String cleaned = cleanLine(line);
                if (cleaned.isEmpty()) {
                    continue;
                }
                if (isPageNumber(cleaned)) {
                    continue;
                }
                if (headerFooterLines.contains(cleaned)) {
                    continue;
                }
                pageCleaned.add(cleaned);
            }
            // 去掉页内首尾空行（已经是空串都过滤了）
            cleanedLines.addAll(pageCleaned);
        }

        // 3. 去相邻重复行（标题重复出现）
        cleanedLines = removeAdjacentDuplicates(cleanedLines);

        // 4. 合并：段落内换行合并（以中文/字母结尾且下一行以中文/字母开头，视为同一段落）
        String result = mergeLines(cleanedLines);

        // 5. 最终压缩：连续空行压到一个
        result = result.replaceAll("\\n{3,}", "\n\n").trim();

        return result;
    }

    /**
     * 拆分成行，去掉空白首尾
     */
    private List<String> splitLines(String text) {
        if (text == null) {
            return Collections.emptyList();
        }
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    /**
     * 识别页眉页脚：统计每页开头和结尾出现频率高的短行
     */
    private Set<String> detectHeaderFooter(List<List<String>> pagesLines) {
        Map<String, Integer> headerCount = new HashMap<>();
        Map<String, Integer> footerCount = new HashMap<>();

        int nonEmptyPages = 0;
        for (List<String> lines : pagesLines) {
            // 过滤掉纯空行后再取首尾
            List<String> nonEmpty = lines.stream()
                    .map(this::cleanLine)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
            if (nonEmpty.isEmpty()) {
                continue;
            }
            nonEmptyPages++;

            // 取前 2 行作为候选页眉
            for (int i = 0; i < Math.min(2, nonEmpty.size()); i++) {
                String line = nonEmpty.get(i);
                if (line.length() <= HEADER_FOOTER_MAX_LEN && !isPageNumber(line)) {
                    headerCount.merge(line, 1, Integer::sum);
                }
            }
            // 取后 2 行作为候选页脚
            for (int i = Math.max(0, nonEmpty.size() - 2); i < nonEmpty.size(); i++) {
                String line = nonEmpty.get(i);
                if (line.length() <= HEADER_FOOTER_MAX_LEN && !isPageNumber(line)) {
                    footerCount.merge(line, 1, Integer::sum);
                }
            }
        }

        if (nonEmptyPages == 0) {
            return Collections.emptySet();
        }

        Set<String> result = new HashSet<>();
        int finalNonEmptyPages = nonEmptyPages;
        headerCount.forEach((line, count) -> {
            if ((double) count / finalNonEmptyPages >= HEADER_FOOTER_THRESHOLD) {
                result.add(line);
            }
        });
        footerCount.forEach((line, count) -> {
            if ((double) count / finalNonEmptyPages >= HEADER_FOOTER_THRESHOLD) {
                result.add(line);
            }
        });

        if (!result.isEmpty()) {
            log.debug("检测到页眉/页脚行: {}", result);
        }
        return result;
    }

    /**
     * 单行清洗：去首尾空白、多余空格/Tab/全角空格
     */
    private String cleanLine(String line) {
        if (line == null) {
            return "";
        }
        // 全角空格、Tab、不间断空格 等统一替换为普通空格
        String cleaned = line.replaceAll("[\\t\\u3000\\u00A0]", " ");
        // 多个连续空格合并为一个
        cleaned = cleaned.replaceAll(" {2,}", " ");
        return cleaned.trim();
    }

    /**
     * 判断是否是页码行
     */
    private boolean isPageNumber(String line) {
        if (line.isEmpty()) {
            return false;
        }
        for (Pattern p : PAGE_NUMBER_PATTERNS) {
            if (p.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 去掉相邻的完全重复行（重复标题）
     */
    private List<String> removeAdjacentDuplicates(List<String> lines) {
        List<String> result = new ArrayList<>();
        String prev = null;
        for (String line : lines) {
            if (line.isEmpty()) {
                result.add(line);
                prev = null; // 空行打断重复判定
                continue;
            }
            if (line.equals(prev)) {
                continue;
            }
            result.add(line);
            prev = line;
        }
        return result;
    }

    /**
     * 段落内换行合并：
     * - 当前行以句号/问号/感叹号/分号/冒号/结尾标点结束 -> 段尾，换行保留
     * - 否则与下一行合并为同一段（用空格衔接），以保持英文单词不断
     */
    private String mergeLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isEmpty()) {
                sb.append("\n");
                continue;
            }
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append(" ");
            }
            sb.append(line);
            // 判断是否是段落结尾
            if (isParagraphEnd(line)) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 判断一行是否是段落结尾
     * 中文/英文句号、问号、感叹号、分号、冒号结尾视为段落结束
     */
    private boolean isParagraphEnd(String line) {
        if (line.isEmpty()) {
            return true;
        }
        char last = line.charAt(line.length() - 1);
        return last == '。' || last == '？' || last == '！'
                || last == '.' || last == '?' || last == '!'
                || last == ';' || last == '；'
                || last == ':' || last == '：'
                || last == '\n';
    }
}
