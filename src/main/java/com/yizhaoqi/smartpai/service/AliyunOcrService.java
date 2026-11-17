package com.yizhaoqi.smartpai.service;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextResponse;
import com.aliyun.teaopenapi.models.Config;
import com.yizhaoqi.smartpai.model.LiteParseOcr;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class AliyunOcrService {

    @Value("${aliyun.ocr.endpoint}")
    private String endpoint;
    @Value("${aliyun.ocr.access-key-id}")
    private String accessKeyId;
    @Value("${aliyun.ocr.access-key-secret}")
    private String accessKeySecret;
    @Value("${aliyun.ocr.type:Advanced}")
    private String ocrType;

    public LiteParseOcr.LiteParseResponse recognize(InputStream imageStream) throws Exception {
        // 1. 初始化阿里云客户端
        Config config = new Config()
                .setAccessKeyId(accessKeyId)
                .setAccessKeySecret(accessKeySecret);
        config.endpoint = endpoint;
        Client client = new Client(config);

        //    构造请求， Type=Advanced 为高精版
       RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                .setBody(imageStream)
                .setType(ocrType)
                .setOutputCoordinate("points");

       // 3. 调用
        RecognizeAllTextResponse response = client.recognizeAllText(request);

        //4. 解析结果并转换为 LiteParse 格式
        return convertToLiteParse(response);
    }

    private LiteParseOcr.LiteParseResponse convertToLiteParse(RecognizeAllTextResponse response) {

        LiteParseOcr.LiteParseResponse result = new LiteParseOcr.LiteParseResponse();
        List<LiteParseOcr.LiteParseResult> results = new ArrayList<>();

        // 注意：这里的解析基于阿里云通用文字识别高精版的返回结构
        if (response.getBody() != null && response.getBody().getData() != null) {
            var data = response.getBody().getData();
            if (data.getSubImages() != null) {
                for (var subImage : data.getSubImages()) {
                    if (subImage.getBlockInfo() != null && subImage.getBlockInfo().getBlockDetails() != null) {
                        for (var block : subImage.getBlockInfo().getBlockDetails()) {
                            String text = block.getBlockContent();
                            Double confidence = block.getBlockConfidence() != null ? block.getBlockConfidence() / 100.0 : 0.99;

                            // 提取四点坐标转换为 [x0, y0, x1, y1]
                            List<Double> bbox = new ArrayList<>();
                            if (block.getBlockPoints() != null && block.getBlockPoints().size() >= 4) {
                                // 假设返回的是顺时针四个点 [[x,y], [x,y], [x,y], [x,y]]
                                var p0 = block.getBlockPoints().get(0); // 左上
                                var p2 = block.getBlockPoints().get(2); // 右下
                                // 转换时需注意 SDK 返回的 Point 对象的具体字段名，通常为 X, Y
                                bbox.add(Double.valueOf(p0.getX().toString()));
                                bbox.add(Double.valueOf(p0.getY().toString()));
                                bbox.add(Double.valueOf(p2.getX().toString()));
                                bbox.add(Double.valueOf(p2.getY().toString()));
                            } else {
                                bbox.add(0.0); bbox.add(0.0); bbox.add(0.0); bbox.add(0.0);
                            }

                            results.add(new LiteParseOcr.LiteParseResult(bbox, confidence, text));
                        }
                    }
                }
            }
        }
        result.setResults(results);
        return result;

    }
}
