package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Persists authenticated Worker presence on shared storage, without a schema migration. */
@Service
public class CollectionWorkerPresence {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(CollectionWorkerPresence.class);
    private final BasePathProvider paths;
    private final ObjectMapper mapper;
    private final boolean configured;
    private volatile boolean storageError;
    public CollectionWorkerPresence(BasePathProvider paths,ObjectMapper mapper,@Value("${COLLECTION_WORKER_TOKEN:}") String token) {
        this.paths=paths;this.mapper=mapper;this.configured=token.length()>=32;
    }
    private Path directory() throws java.io.IOException {
        Path base=Path.of(paths.getBasePath()).toRealPath(), directory=base.resolve("collection-worker/presence");
        Path ancestor=directory;
        while(!Files.exists(ancestor,LinkOption.NOFOLLOW_LINKS)) ancestor=ancestor.getParent();
        if(!ancestor.toRealPath().startsWith(base)) throw new java.io.IOException("Heartbeat directory escapes storage root");
        return directory;
    }
    public synchronized void record(String workerId) {
        if(workerId==null || !workerId.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}")) return;
        Path temporary=null;
        try {
            Path directory=directory();Files.createDirectories(directory);
            temporary=Files.createTempFile(directory,"presence-",".part");
            mapper.writeValue(temporary.toFile(),Map.of("workerId",workerId,"lastSeenAt",Instant.now().toString()));
            try { Files.move(temporary,directory.resolve(workerId+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch(AtomicMoveNotSupportedException e) { Files.move(temporary,directory.resolve(workerId+".json"),StandardCopyOption.REPLACE_EXISTING); }
            storageError=false;
        } catch(Exception e) { storageError=true;LOG.warn("Unable to persist collection Worker presence",e); }
        finally { if(temporary!=null) try { Files.deleteIfExists(temporary); } catch(java.io.IOException ignored) {} }
    }
    public Map<String,Object> snapshot() {
        var workers=new ArrayList<Map<String,Object>>();Instant now=Instant.now();boolean error=storageError;
        try {
            Path directory=directory();
            if(Files.isDirectory(directory)) try(var entries=Files.list(directory)) {
                for(Path file:entries.filter(p->p.getFileName().toString().endsWith(".json")).limit(500).toList()) {
                    try {
                        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.size(file)>4096) { error=true;continue; }
                        var node=mapper.readTree(file.toFile());var seen=Instant.parse(node.path("lastSeenAt").asText());
                        String id=node.path("workerId").asText();
                        if(!id.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,99}") || !file.getFileName().toString().equals(id+".json")) { error=true;continue; }
                        long age=Duration.between(seen,now).getSeconds();
                        workers.add(Map.of("workerId",id,"lastSeenAt",seen.atZone(CollectionSchedule.ZONE).toLocalDateTime().toString(),"online",age>=-5 && age<=90));
                    } catch(Exception e) { error=true; }
                }
            }
        } catch(Exception e) { error=true; }
        workers.sort(Comparator.comparing(w->w.get("workerId").toString()));
        long online=workers.stream().filter(w->Boolean.TRUE.equals(w.get("online"))).count();
        String status=!configured?"unconfigured":error?"unknown":online>0?"online":workers.isEmpty()?"unseen":"offline";
        return Map.of("status",status,"onlineCount",online,"workers",workers,"timeoutSeconds",90,
            "storageError",error,"configured",configured);
    }
}
