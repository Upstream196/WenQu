package com.yizhaoqi.smartpai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class LiteParseService {

    @Value("${file.parsing.liteparse.command:lit}")
    private String litCommand;
    @Value("${file.parsing.liteparse.ocr-server-url}")
    private String ocrServerUrl;
    @Value("${file.parsing.liteparse.timeout-seconds:300}")
    private int timeoutSeconds;
    @Value("${file.parsing.liteparse.max-pages:1000}")
    private int maxPages;
    @Value("${file.parsing.liteparse.dpi:150}")
    private int dpi;
    @Value("${aliyun.ocr.callback-token}")
    private String ocrToken;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Data
    public static class ParsePage {
        private int pageNumber;
        private String text;
    }

    public List<ParsePage> parsePdf(File pdfFile) throws Exception {
        Path outputPath = Files.createTempFile("liteparse-out-", ".json");
        Path stdoutLog = Files.createTempFile("liteparse-stdout-", ".log" );
        Path stderrLog = Files.createTempFile("liteparse-stderr-", ".log");

        try {
            List<String> command = new ArrayList<>();
            command.add(litCommand);
            command.add("parse");
            command.add(pdfFile.getAbsolutePath());
            command.add("--format");
            command.add("json");
            command.add("--output");
            command.add(outputPath.toAbsolutePath().toString());
            command.add("--max-pages");
            command.add(String.valueOf(maxPages));
            command.add("--dpi");
            command.add(String.valueOf(dpi));
            command.add("--quiet");

            //若配置了 OCR服务，则启用外部 OCR
            if(ocrServerUrl != null && !ocrServerUrl.isBlank()) {
                command.add("--ocr-server-url");
                command.add(ocrServerUrl);
            }

            ProcessBuilder pb = new ProcessBuilder(command);
            //重定向 stdout.stderr 放在缓冲区写满卡死
            pb.redirectOutput(stdoutLog.toFile());
            pb.redirectError(stderrLog.toFile());

            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if(!finished) {
                process.destroyForcibly();
                throw new RuntimeException("LiteParse 解析超时");
            }

            if(process.exitValue() !=0 ) {
                String errMsg = Files.readString(stderrLog);
                log.error("LiteParse 执行失败，推出码：{}，错误日志：{}", process.exitValue(), errMsg);
                throw new RuntimeException("LiteParse 执行失败");
            }

            return parseJson(outputPath.toFile());
        } finally {
            //清理所有临时文件
            Files.deleteIfExists(outputPath);
            Files.deleteIfExists(stdoutLog);
            Files.deleteIfExists(stderrLog);
        }

    }
    private List<ParsePage> parseJson(File jsonFile) throws IOException {
        ArrayList<ParsePage> pages = new ArrayList<>();
        JsonNode root = objectMapper.readTree(jsonFile);

        // 根据LiteParse 输出结构解析，假设返回数组，每个元素有 text 和 page 字段
        if(root.isArray()) {
            for(JsonNode node : root) {
                ParsePage page = new ParsePage();
                page.setPageNumber(node.path("page").asInt(1));
                page.setText(node.path("text").asText(""));
                pages.add(page);
            }
        } else if(root.has("pages") && root.get("pages").isArray()) {
            for(JsonNode node : root.get("pages")) {
                ParsePage page = new ParsePage();
                page.setPageNumber(node.path("page").asInt(1));
                page.setText(node.path("text").asText(""));
                pages.add(page);
            }
        }
            return pages;
    }


}
