package com.yizhaoqi.smartpai.model;

import lombok.Data;

import java.util.List;

public class LiteParseOcr {

    @Data
    public static class LiteParseResponse {
        private List<LiteParseResult> results;
    }

    @Data
    public static class LiteParseResult {
        private String text;
        private List<Double> bbox;
        private Double confidence;

        public LiteParseResult(List<Double> bbox, Double confidence, String text) {
            this.bbox = bbox;
            this.confidence = confidence;
            this.text = text;
        }
    }
}
