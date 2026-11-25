package com.metric.collect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.metric.shared.util.BasePathProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CollectionTaskDeleteTest {
    JdbcTemplate jdbc;
    Connection connection;
    PreparedStatement lock, release;
    ResultSet result;
    CollectionPipeline pipeline;
    @BeforeEach void setup() throws Exception {
        jdbc=mock(JdbcTemplate.class); connection=mock(Connection.class);
        lock=mock(PreparedStatement.class); release=mock(PreparedStatement.class); result=mock(ResultSet.class);
        when(connection.prepareStatement("SELECT GET_LOCK(?,0)")).thenReturn(lock);
        when(connection.prepareStatement("SELECT RELEASE_LOCK(?)")).thenReturn(release);
        when(lock.executeQuery()).thenReturn(result); when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
        when(jdbc.execute(any(ConnectionCallback.class))).thenAnswer(call -> call.<ConnectionCallback<?>>getArgument(0).doInConnection(connection));
        pipeline=new CollectionPipeline(jdbc,new ObjectMapper(),mock(BasePathProvider.class),
            mock(CollectionDirectoryGuard.class));
    }
    @Test void removesOnlyConfigurationAndReleasesTheScanLock() throws Exception {
        when(jdbc.update("DELETE FROM collection_source WHERE code=?","test")).thenReturn(1);
        pipeline.delete("test");
        verify(lock).setString(1,"collect:test");
        verify(jdbc,times(1)).update(anyString(),any(Object[].class));
        verify(jdbc).update("DELETE FROM collection_source WHERE code=?","test");
        verify(release).setString(1,"collect:test"); verify(release).execute();
    }
    @Test void runningCollectorBlocksDeletionWithoutDeletingAnything() throws Exception {
        when(result.getInt(1)).thenReturn(0);
        assertThrows(IllegalStateException.class,()->pipeline.delete("test"));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
        verify(release,never()).execute();
    }
    @Test void missingTaskReportsNotFoundAndStillReleasesLock() throws Exception {
        assertThrows(NoSuchElementException.class,()->pipeline.delete("missing"));
        verify(release).execute();
    }
    @Test void scannerDoesNotQueueConfigurationDeletedBeforeWorkerRequest() throws Exception {
        var workers=mock(CollectionWorkerService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(pipeline,"workers",workers);
        when(jdbc.queryForList("SELECT config_json FROM collection_source WHERE code=?","test")).thenReturn(List.of());
        assertThrows(IllegalArgumentException.class,()->pipeline.scan("test"));
        verify(jdbc).queryForList("SELECT config_json FROM collection_source WHERE code=?","test");
        verifyNoInteractions(workers);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
