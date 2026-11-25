package com.metric.config.service;

import com.metric.config.entity.PlatformConfig;
import com.metric.config.repository.PlatformConfigRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

@Service
public class PlatformConfigService {

    @Autowired
    private PlatformConfigRepository platformConfigRepository;

    private static final String LOGO_DIR = "files/logos/";
    private static final int MAX_LOGO_HISTORY = 5;

    public Optional<PlatformConfig> getConfig(String key) {
        return platformConfigRepository.findByConfigKey(key);
    }

    public Map<String, String> getAllConfigs() {
        Map<String, String> configs = new LinkedHashMap<>();
        platformConfigRepository.findAll().forEach(config -> {
            configs.put(config.getConfigKey(), config.getConfigValue());
        });
        return configs;
    }

    public PlatformConfig saveConfig(PlatformConfig config) {
        Optional<PlatformConfig> existing = platformConfigRepository.findByConfigKey(config.getConfigKey());
        if (existing.isPresent()) {
            PlatformConfig old = existing.get();
            old.setConfigValue(config.getConfigValue());
            old.setDescription(config.getDescription());
            return platformConfigRepository.save(old);
        } else {
            return platformConfigRepository.save(config);
        }
    }

    public List<String> getLogoHistory() {
        List<PlatformConfig> logos = platformConfigRepository.findAll().stream()
                .filter(c -> "logo".equals(c.getConfigKey()) && c.getConfigValue() != null && !c.getConfigValue().isEmpty())
                .sorted((a, b) -> b.getUpdateTime().compareTo(a.getUpdateTime()))
                .limit(MAX_LOGO_HISTORY)
                .toList();

        List<String> history = new ArrayList<>();
        for (PlatformConfig logo : logos) {
            String value = logo.getConfigValue();
            // 如果是文件路径，添加到历史
            if (value != null && !value.isEmpty() && !history.contains(value)) {
                history.add(value);
            }
        }
        return history;
    }

    public String uploadLogo(MultipartFile file) throws IOException {
        // 创建上传目录
        Path uploadPath = Paths.get(LOGO_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        // 生成文件名
        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String newFilename = "logo_" + System.currentTimeMillis() + extension;

        // 保存文件
        Path filePath = uploadPath.resolve(newFilename);
        file.transferTo(filePath.toFile());

        // 返回相对路径
        return "/" + LOGO_DIR + newFilename;
    }

    public void deleteLogo(String logoPath) {
        if (logoPath != null && !logoPath.isEmpty()) {
            try {
                Path path = Paths.get(logoPath.substring(1)); // 去掉开头的 /
                Files.deleteIfExists(path);
            } catch (IOException e) {
                // 忽略删除失败
            }
        }
    }
}
