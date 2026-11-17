package com.yizhaoqi.smartpai.benchmark.searchTest;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yizhaoqi.smartpai.client.EmbeddingClient;
import com.yizhaoqi.smartpai.config.EsIndexInitializer;
import com.yizhaoqi.smartpai.entity.EsDocument;
import com.yizhaoqi.smartpai.entity.SearchResult;
import com.yizhaoqi.smartpai.service.HybridSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;


import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

import static co.elastic.clients.elasticsearch.core.bulk.BulkOperationBuilders.index;
import static org.apache.commons.collections4.CollectionUtils.size;
import static org.springframework.jdbc.core.JdbcOperationsExtensionsKt.query;

@SpringBootTest(properties = {
        "spring.elasticsearch.uris=http://localhost:9201",
        "elasticsearch.username=elastic",
        "elasticsearch.password=PaiSmart2025"
})
public class RetrievalEvaluationTest {
    @Autowired
    private HybridSearchService searchService;

    @Autowired(required = false)
    private ElasticsearchClient client;

    @MockitoBean
    private EsIndexInitializer esIndexInitializer;

    @Autowired
    private EmbeddingClient embeddingClient;

//    @BeforeEach
//    void setUp() {
//        //伪造2048维的假向量
//        float[] fakeVector = new float[2048];
//        java.util.Arrays.fill(fakeVector, 0.1f);
//        List<float[]> fakeVecs = List.of(fakeVector);
//
//        //当调用embed方法时，直接返回假向量，不发送网络请求
//        Mockito.when(embeddingClient.embed(ArgumentMatchers.anyList())).thenReturn(fakeVecs);
//    }

    @Test
    public void runEvaluation() throws Exception {
        System.out.println("当前注入的ES客户端：" + (client != null ? client.toString() : "null"));

        ObjectMapper mapper = new ObjectMapper();
        InputStream is = getClass().getResourceAsStream("/benchmark/dataset/qa_dataset_120.json");
        if(is == null){
            throw new RuntimeException("找不到测试集文件！");
        }
        List<QaPair> allData = mapper.readValue(is, new TypeReference<List<QaPair>>() {});

        // 为了控制变量，我们只抽取 20 条进行测试（每隔 6 条抽 1 条）
        List<QaPair> dataset = new java.util.ArrayList<>();
        for (int i = 0; i < allData.size(); i += 6) {
            dataset.add(allData.get(i));
        }
        System.out.println("本次实际评测条数: " + dataset.size());

        // TODO: 改成你数据库里真实有权限的 userId (比如 "1" 或 "admin")
        String testUserId = "admin";
        int topK = 5;
        // RRF 算法中的平滑常数，通常取60
        int rrfK = 60;

        int textHitCount = 0;
        int hybridHitCount = 0;
        int rrfHitCount = 0;
        double textPrecisionSum = 0;
        double hybridPrecisionSum = 0;
        double rrfPrecisionSum = 0;


        System.out.println("========== 开始评估 ==========");
        for (QaPair qa : dataset) {
            // 注意：你 JSON 里的 ID 是 Integer，如果 ES 里 chunkId 是 String，这里需要转成 String
            Set<String> golds = qa.relevant_chunk_ids.stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet());

            String query = qa.query;

            // 1. 跑纯文本召回
            List<SearchResult> textResults = searchService.textOnlySearchWithPermission(qa.query, testUserId, topK);

            // 2. 跑现线上混合召回
            List<SearchResult> hybridResults = searchService.searchWithPermission(qa.query, testUserId, topK);

            // 3. RRF 双路独立召回融合
            List<SearchResult> rrfResult = doRRFSearch(query, testUserId, rrfK, topK);
            // 计算纯文本检索指标
            textHitCount += calcHit(textResults, golds);
            textPrecisionSum += calcPrecision(textResults, golds);

            // 计算混合检索指标
            hybridHitCount += calcHit(hybridResults, golds);
            hybridPrecisionSum += calcPrecision(hybridResults, golds);

            // 计算RRF检索指标
            rrfHitCount += calcHit(rrfResult, golds);
            rrfPrecisionSum += calcPrecision(rrfResult, golds);

            // 打印单条失败案例（方便你写面试复盘）
            if (calcHit(hybridResults, golds) == 0) {
                System.out.println("【混合检索失败】Query: " + qa.query);
                System.out.println("    期望 Chunk: " + golds);
                System.out.println("    实际返回: " + hybridResults.stream().map(SearchResult::getChunkId).collect(Collectors.toList()));
            }
        }

        int total = dataset.size();
        System.out.println("---------------------------------");
        System.out.println("纯文本  - Hit@5: " + (textHitCount * 100.0 / total) + "%, Precision@5: " + (textPrecisionSum / total));
        System.out.println("混合检索 - Hit@5: " + (hybridHitCount * 100.0 / total) + "%, Precision@5: " + (hybridPrecisionSum / total));
        System.out.println("RRF 融合 - Hit@5: " + (rrfHitCount * 100.0 / total) + "%, Precision@5: " + (rrfPrecisionSum / total));
        System.out.println("========== 评估结束 ==========");
    }

    /**
     * 核心逻辑：双路独立召回 + RRF 融合
     */
    private List<SearchResult> doRRFSearch(String query, String userId, int rrfk, int topK)
            throws Exception {
        //生成向量
        List<Float> queryVector = new ArrayList<>();
        for (float v : embeddingClient.embed(List.of(query)).get(0)) {
            queryVector.add(v);
        }

        // 线路 1：纯 BM25 召回 Top 20
        SearchResponse<EsDocument> bm25Resp = client.search(s -> s
                .index("knowledge_base")
                .query(q -> q.match(m -> m.field("textContent").query(query)))
                .size(20), EsDocument.class);
        
        // 线路 2： 纯KNN召回 TOP 20
        SearchResponse<EsDocument> knnResp = client.search(s -> s
                .index("knowledge_base")
                .knn(k -> k.field("vector").queryVector(queryVector).k(20).numCandidates(50))
                .size(20), EsDocument.class);

        // 计数 RRF 分数
        HashMap<String, Double> rrfScores = new HashMap<>();
        HashMap<String, SearchResult> docMap = new HashMap<>();

        // 累计 BM25 的排名分
        List<EsDocument> bm25Docs = bm25Resp.hits().hits().stream().map(h -> h.source()).collect(Collectors.toList());
        for (int i = 0; i < bm25Docs.size(); i++) {
            EsDocument doc = bm25Docs.get(i);
            String chunkId = String.valueOf(doc.getChunkId());
            rrfScores.put(chunkId,rrfScores.getOrDefault(chunkId, 0.0) + 1.0 / (rrfk + i + 1));
            if(!docMap.containsKey(chunkId)) {

                docMap.put(chunkId, convertToSearchResult(doc));
            }
        }

        // 累加 KNN 的排名分
        List<EsDocument> knnDocs = knnResp.hits().hits().stream().map(h -> h.source()).collect(Collectors.toList());
        for (int i = 0; i < knnDocs.size(); i++) {
            EsDocument doc = knnDocs.get(i);
            String chunkId = String.valueOf(doc.getChunkId());
            rrfScores.put(chunkId, rrfScores.getOrDefault(chunkId, 0.0) + 1.0 / (rrfk + i + 1));
            if(!docMap.containsKey(chunkId)) {
                docMap.put(chunkId, convertToSearchResult(doc));
            }
        }

        //根据 RRF 分数降序排序， 取 TopK
        return  rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> docMap.get(e.getKey()))
                .collect(Collectors.toList());

    }

    /**
     * 辅助方法： EsDocument 转 SearchResult
     * @param doc
     * @return
     */
    private SearchResult convertToSearchResult(EsDocument doc) {
        // 根据 SearchResult 构造函数补全，通常需要chunkId，fileMd5， textContent等
        return new SearchResult(doc.getFileMd5(), doc.getChunkId(), doc.getTextContent(), 0.0, doc.getUserId(),
                doc.getOrgTag(), doc.isPublic());
    }


    private int calcHit(List<SearchResult> results, Set<String> golds) {
        for (SearchResult r : results) {
            // 修复：将r.getChunkId()强转成String，避免实体类与基础类型进行比对
            if (golds.contains(String.valueOf(r.getChunkId()))) {
                return 1;
            }
        }
        return 0;
    }

    private double calcPrecision(List<SearchResult> results, Set<String> golds) {
        if (results.isEmpty()) return 0;
        int matchCount = 0;
        for (SearchResult r : results) {
            if (golds.contains(String.valueOf(r.getChunkId()))) {
                matchCount++;
            }
        }
        return (double) matchCount / results.size();
    }

    public static class QaPair {
        public String query;
        public List<Integer> relevant_chunk_ids;
    }
}
