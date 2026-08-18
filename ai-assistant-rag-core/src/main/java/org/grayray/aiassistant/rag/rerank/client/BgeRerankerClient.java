package org.grayray.aiassistant.rag.rerank.client;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * bge-reranker-v2-m3 Python 服务 HTTP 客户端
 * <p>
 * 通过 Spring {@link RestClient} 调用本地部署的 FastAPI rerank 服务。
 * 请求接口：POST /rerank，请求体 {"query": "...", "passages": ["...", ...]}
 * 响应体：{"scores": [0.1, 0.9, ...]}
 */
@Slf4j
public class BgeRerankerClient {

    private final RestClient restClient;

    public BgeRerankerClient(RestClient restClient) {
        this.restClient = restClient;
    }

    /**
     * 批量计算 query-passage 相关性分数
     *
     * @param query    查询文本
     * @param passages 候选片段文本列表
     * @return 与 passages 一一对应的相关性分数列表
     * @throws RestClientException 当 HTTP 调用失败时抛出
     */
    public List<Double> computeScores(String query, List<String> passages) {
        RerankRequest req = RerankRequest.builder()
                .query(query)
                .passages(passages)
                .build();

        log.debug("[BgeRerankerClient] 调用 rerank 服务: query=\"{}\", passages={}",
                truncate(query, 60), passages.size());

        RerankResponse resp = restClient.post()
                .uri("/rerank")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(RerankResponse.class);

        if (resp == null || resp.getScores() == null) {
            throw new RestClientException("rerank 服务返回为空");
        }

        return resp.getScores();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class RerankRequest {
        private String query;
        private List<String> passages;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class RerankResponse {
        private List<Double> scores;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, max) + "...";
    }
}
