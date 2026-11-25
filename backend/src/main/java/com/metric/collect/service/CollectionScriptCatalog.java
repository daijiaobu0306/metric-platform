package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reads immutable release manifests only. The platform never executes Python. */
@Service
public class CollectionScriptCatalog {
    private final BasePathProvider paths;
    private final ObjectMapper mapper;
    public CollectionScriptCatalog(BasePathProvider paths,ObjectMapper mapper) { this.paths=paths; this.mapper=mapper; }
    private Path root() { return Path.of(paths.getBasePath()).toAbsolutePath().normalize().resolve("collection-scripts/releases"); }
    public static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
    public String manifestHash(String version) {
        if(version==null || !version.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new IllegalArgumentException("脚本版本无效");
        try {
            Path root=root().toRealPath(), release=root.resolve(version);
            if(Files.isSymbolicLink(release) || !release.toRealPath().startsWith(root)) throw new IllegalArgumentException("版本目录越界");
            Path file=release.resolve("manifest.json");
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>100000) throw new IllegalArgumentException("版本清单无效");
            byte[] bytes=Files.readAllBytes(file);
            var manifest=mapper.readTree(bytes);
            if(manifest.path("protocolVersion").asInt()!=1 || !version.equals(manifest.path("version").asText())
                || !manifest.path("files").isObject() || !manifest.path("files").has(manifest.path("entry").asText()))
                throw new IllegalArgumentException("版本清单协议不匹配");
            var files=manifest.path("files").fields();
            while(files.hasNext()) {
                var item=files.next(); Path target=release.resolve(item.getKey()).normalize();
                if(!target.startsWith(release) || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)
                        || !target.toRealPath().startsWith(release.toRealPath()) || !hash(Files.readAllBytes(target)).equals(item.getValue().asText()))
                    throw new IllegalArgumentException("脚本文件缺失或校验失败："+item.getKey());
            }
            return hash(bytes);
        } catch(java.io.IOException e) { throw new IllegalArgumentException("脚本版本未发布或不可读："+version,e); }
    }
    public Map<String,String> metadata(String version) {
        manifestHash(version);
        try {
            var node=mapper.readTree(root().resolve(version).resolve("manifest.json").toFile());
            String dataset=node.path("datasetCode").asText("");
            Path profile=root().resolve(version).resolve("profile.json");
            if(dataset.isBlank() && node.path("files").has("profile.json") && Files.isRegularFile(profile))
                dataset=mapper.readTree(profile.toFile()).path("sourceCode").asText("");
            return Map.of("version",version,"categoryCode",node.path("categoryCode").asText(),
                "displayName",node.path("displayName").asText(version),"datasetCode",dataset,
                "protocol",node.path("outputProtocol").asText("compliance_detail".equals(dataset)?"raw-rows":"metric-values"),"status","available");
        } catch(java.io.IOException e) { throw new IllegalArgumentException("读取脚本元数据失败",e); }
    }
    public List<Map<String,String>> catalog() {
        if(!Files.isDirectory(root())) return List.of();
        try(var entries=Files.list(root())) {
            return entries.filter(p -> Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS))
                .map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).map(version -> {
                    try { return metadata(version); }
                    catch (IllegalArgumentException e) { return Map.of("version",version,"categoryCode","","displayName",version,"datasetCode","","protocol","","status","invalid","error",e.getMessage()); }
                }).filter(item -> item.get("categoryCode").isEmpty() || CollectionCategoryScope.supports(item.get("categoryCode"))).toList();
        } catch(java.io.IOException e) { throw new IllegalArgumentException("读取脚本版本失败",e); }
    }
    public List<String> versions() { return catalog().stream().map(item -> item.get("version")).toList(); }

    private boolean isBusinessValueRelease(Path release) {
        if (release.getFileName().toString().matches("business_value-[a-zA-Z0-9_-]+")) return true;
        Path manifest = release.resolve("manifest.json");
        try {
            return Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS) && Files.size(manifest) <= 100000
                && "businessValue".equals(mapper.readTree(manifest.toFile()).path("categoryCode").asText());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private void removeBusinessValueReleases(Path releases) throws java.io.IOException {
        try (var entries = Files.list(releases)) {
            for (Path release : entries.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isBusinessValueRelease).toList()) {
                try (var tree = Files.walk(release)) {
                    for (Path path : tree.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }
    /** Packaged initial releases are installed once; an existing immutable release must match. */
    @jakarta.annotation.PostConstruct
    public void installBundled() throws java.io.IOException {
        String resources="collection-scripts/";
        var index=new org.springframework.core.io.ClassPathResource(resources+"catalog.json");
        if(!index.exists()) return;
        Path base=Path.of(paths.getBasePath()).toAbsolutePath().normalize();
        Files.createDirectories(base);
        Files.createDirectories(root());
        Path releases = root().toRealPath();
        if(!releases.startsWith(base.toRealPath()) || releases.equals(base.toRealPath()))
            throw new IllegalStateException("脚本发布目录越界");
        removeBusinessValueReleases(releases);
        try(var input=index.getInputStream()) {
            for(var entry:mapper.readTree(input)) {
                String version=entry.asText();
                if(!version.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")) throw new IllegalStateException("内置脚本版本无效");
                String prefix=resources+"releases/"+version+"/";
                byte[] manifestBytes;
                try(var stream=new org.springframework.core.io.ClassPathResource(prefix+"manifest.json").getInputStream()) { manifestBytes=stream.readAllBytes(); }
                var manifest=mapper.readTree(manifestBytes);
                Path destination=root().resolve(version);
                if(Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) {
                    if(Files.isSymbolicLink(destination) || !hash(Files.readAllBytes(destination.resolve("manifest.json"))).equals(hash(manifestBytes)))
                        throw new IllegalStateException("已有脚本版本与内置清单冲突："+version);
                    var names=manifest.path("files").fields();
                    while(names.hasNext()) {
                        var file=names.next();Path target=destination.resolve(file.getKey()).normalize();
                        if(!target.startsWith(destination) || Files.isSymbolicLink(target) || !Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)
                                || !hash(Files.readAllBytes(target)).equals(file.getValue().asText()))
                            throw new IllegalStateException("已有脚本版本校验失败："+version);
                    }
                    continue;
                }
                Path temporary=Files.createTempDirectory(root(),".install-");
                try {
                    var names=manifest.path("files").fields();
                    while(names.hasNext()) {
                        var file=names.next();Path target=temporary.resolve(file.getKey()).normalize();
                        if(!target.startsWith(temporary)) throw new IllegalStateException("内置脚本路径越界");
                        byte[] content;
                        try(var stream=new org.springframework.core.io.ClassPathResource(prefix+file.getKey()).getInputStream()) { content=stream.readAllBytes(); }
                        if(!hash(content).equals(file.getValue().asText())) throw new IllegalStateException("内置脚本摘要错误");
                        Files.createDirectories(target.getParent());Files.write(target,content,StandardOpenOption.CREATE_NEW);
                    }
                    Files.write(temporary.resolve("manifest.json"),manifestBytes,StandardOpenOption.CREATE_NEW);
                    Files.move(temporary,destination);
                } finally {
                    if(Files.exists(temporary)) try(var tree=Files.walk(temporary)) {
                        for(Path path:tree.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                    }
                }
            }
        }
    }
}
