package com.yizhaoqi.smartpai.controller;

import com.yizhaoqi.smartpai.model.LiteParseOcr;
import com.yizhaoqi.smartpai.service.AliyunOcrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
@RestController
@RequestMapping("/api/v1/internal/ocr")
public class InternalOcrController {

    @Autowired
    private AliyunOcrService aliyunOcrService;

    @Value("${aliyun.ocr.callback-token}")
    private String callbackToken;

    @PostMapping("/liteparse")
    public ResponseEntity<LiteParseOcr.LiteParseResponse> recognize(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam("file")MultipartFile file
            ) {

        // 简易 Token校验，放在接口对外暴露被刷
        if(!callbackToken.equals(token)) {
            log.warn("拦截到非法的 OCR 内部调用请求");
            return ResponseEntity.status(403).build();
        }

        try (InputStream is = file.getInputStream()){
            LiteParseOcr.LiteParseResponse response = aliyunOcrService.recognize(is);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("阿里云 OCR 调用失败", e);
            return ResponseEntity.status(500).build();
        }
    }
}
