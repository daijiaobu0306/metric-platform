package com.metric.collect.controller;

import com.metric.collect.service.CollectionPipeline;
import com.metric.security.entity.SysUser;
import com.metric.security.service.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CollectionTaskDeleteControllerTest {
    MockMvc mvc; CollectionPipeline pipeline; CurrentUserProvider users;
    @BeforeEach void setup() {
        pipeline=mock(CollectionPipeline.class); users=mock(CurrentUserProvider.class);
        var admin=new SysUser(); admin.setIsAdmin(1); when(users.currentUser()).thenReturn(Optional.of(admin));
        mvc=MockMvcBuilders.standaloneSetup(new CollectionPipelineController(pipeline,users)).build();
    }
    @Test void administratorCanDeleteConfiguration() throws Exception {
        mvc.perform(delete("/api/collection/tasks/test")).andExpect(status().isOk()).andExpect(jsonPath("$.data.deleted").value(true));
        verify(pipeline).delete("test");
    }
    @Test void regularUserCannotDelete() throws Exception {
        var user=new SysUser(); user.setIsAdmin(0); when(users.currentUser()).thenReturn(Optional.of(user));
        mvc.perform(delete("/api/collection/tasks/test")).andExpect(status().isForbidden()); verifyNoInteractions(pipeline);
    }
    @Test void missingTaskReturnsNotFound() throws Exception {
        doThrow(new NoSuchElementException("采集任务不存在或已删除")).when(pipeline).delete("missing");
        mvc.perform(delete("/api/collection/tasks/missing")).andExpect(status().isNotFound());
    }
    @Test void collectingTaskReturnsConflict() throws Exception {
        doThrow(new IllegalStateException("任务正在采集中，请稍后删除")).when(pipeline).delete("test");
        mvc.perform(delete("/api/collection/tasks/test")).andExpect(status().isConflict());
    }
}
