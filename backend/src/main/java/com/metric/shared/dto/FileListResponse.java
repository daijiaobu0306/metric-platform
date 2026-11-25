package com.metric.shared.dto;

import lombok.Data;

@Data
public class FileListResponse {
    private String path;
    private java.util.List<FileItem> items;

    @Data
    public static class FileItem {
        private String name;
        private String type;
        private Long size;
        private String modifiedAt;
        private String path;
    }
}