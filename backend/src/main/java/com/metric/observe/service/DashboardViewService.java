package com.metric.observe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.observe.entity.DashboardViewConfig;
import com.metric.observe.repository.DashboardViewConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class DashboardViewService {
    private final DashboardViewConfigRepository repository;
    private final DashboardAccessService access;
    private final ObjectMapper json;
    public DashboardViewService(DashboardViewConfigRepository repository,DashboardAccessService access,ObjectMapper json) {
        this.repository=repository;this.access=access;this.json=json;
    }
    public List<DashboardViewConfig> getEnabledViews() {
        return repository.findByStatusOrderBySortOrderAsc(1).stream()
            .filter(v -> access.policy(v) != null)
            .filter(v->access.isAdmin() || access.canView(v,access.userId())).map(this::publicView).toList();
    }
    private DashboardViewConfig publicView(DashboardViewConfig v) {
        v.setVisibilitySummary(access.visibilitySummary(v));
        if(access.isAdmin()) return v;
        DashboardViewConfig copy=new DashboardViewConfig();
        copy.setId(v.getId());copy.setViewName(v.getViewName());copy.setViewCode(v.getViewCode());
        copy.setSortOrder(v.getSortOrder());copy.setStatus(v.getStatus());copy.setIsDefault(v.getIsDefault());
        copy.setVisibilitySummary(v.getVisibilitySummary());
        return copy;
    }
    public Optional<DashboardViewConfig> getViewByCode(String code) {return Optional.of(publicView(access.requireView(code,null)));}
    public Optional<DashboardViewConfig> getDefaultView() {
        List<DashboardViewConfig> views=getEnabledViews();
        return views.stream().filter(v->Integer.valueOf(1).equals(v.getIsDefault())).findFirst().or(()->views.stream().findFirst());
    }
    public Map<String,Object> getViewLayout(String code) {
        access.requireAdmin();var v=access.requireView(code,null);
        try {return json.readValue(v.getLayoutConfig(),Map.class);}catch(Exception e){return Map.of();}
    }
    public List<Map<String,Object>> getViewModules(String code) {
        access.requireView(code,null);
        // Legacy module JSON may contain embedded full datasets. Never disclose it to viewers.
        if(!access.isAdmin()) return List.of();
        try{return json.readValue(access.requireView(code,null).getModuleConfig(),List.class);}catch(Exception e){return List.of();}
    }
    @Transactional public DashboardViewConfig saveView(DashboardViewConfig v) {
        access.validate(v);
        if(v.getId()!=null) {
            var existing=byId(v.getId());
            if(!Objects.equals(existing.getViewCode(),v.getViewCode())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"视图代码不能修改");
            v.setCreateTime(existing.getCreateTime());
        }
        repository.findByViewCode(v.getViewCode()).ifPresent(existing->{
            if(!Objects.equals(existing.getId(),v.getId())) throw new ResponseStatusException(HttpStatus.CONFLICT,"视图代码已存在");
        });
        if(Integer.valueOf(1).equals(v.getIsDefault())) clearDefaults();
        v.setModuleConfig("[]");
        return repository.save(v);
    }
    private DashboardViewConfig byId(Long id){return repository.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));}
    private void clearDefaults(){for(var v:repository.findAll())if(Integer.valueOf(1).equals(v.getIsDefault())){v.setIsDefault(0);repository.save(v);}}
    @Transactional public void deleteView(Long id){access.requireAdmin();repository.delete(byId(id));}
    @Transactional public boolean toggleViewStatus(Long id){access.requireAdmin();var v=byId(id);v.setStatus(Integer.valueOf(1).equals(v.getStatus())?0:1);access.validate(v);repository.save(v);return v.getStatus()==1;}
    @Transactional public void setDefaultView(Long id){access.requireAdmin();var v=byId(id);access.validate(v);clearDefaults();v.setIsDefault(1);repository.save(v);}
    @Transactional public DashboardViewConfig updateLayoutConfig(Long id,String layout){access.requireAdmin();var v=byId(id);v.setLayoutConfig(layout);access.validate(v);return repository.save(v);}
    @Transactional public DashboardViewConfig updateModuleConfig(Long id,String modules){access.requireAdmin();throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请通过专屏配置保存展示模块");}
}
