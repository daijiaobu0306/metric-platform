package com.metric.metric.service;

import com.metric.metric.entity.MetricItem;
import com.metric.metric.repository.MetricItemRepository;
import com.metric.metric.repository.MetricRealtimeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MetricValueCalcServiceTest {
    @Test void scheduledJavaCalculationDoesNotRecomputePythonCollectedValues() throws Exception {
        var dataSource = mock(DataSource.class); var connection = mock(Connection.class);
        var statement = mock(PreparedStatement.class); var rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true); // MAX returns one row containing SQL NULL for an empty table.
        when(rows.getTimestamp("latest_time")).thenReturn(null);
        var metrics = mock(MetricItemRepository.class);
        when(metrics.findBySubCategoryNameAndStatus("办理类-单业务",1)).thenReturn(List.of(new MetricItem()));
        var realtime = mock(MetricRealtimeRepository.class);
        var service = new MetricValueCalcService();
        ReflectionTestUtils.setField(service, "dataSource", dataSource);
        ReflectionTestUtils.setField(service, "metricItemRepository", metrics);
        ReflectionTestUtils.setField(service, "metricRealtimeRepository", realtime);
        assertDoesNotThrow(service::calcAllMetricValues);
        verifyNoInteractions(dataSource,connection);
        verify(metrics,never()).findBySubCategoryNameAndStatus("办理类-单业务",1);
        verifyNoInteractions(realtime);
    }
}
