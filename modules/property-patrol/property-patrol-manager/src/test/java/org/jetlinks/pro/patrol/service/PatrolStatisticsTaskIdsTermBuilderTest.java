package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.core.param.Term;
import org.jetlinks.pro.patrol.service.terms.PatrolStatisticsTaskIdsTermBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class PatrolStatisticsTaskIdsTermBuilderTest {
    private final PatrolStatisticsTaskIdsTermBuilder builder = new PatrolStatisticsTaskIdsTermBuilder();

    @Test
    void largeAuthorizedSnapshotUsesOneBoundArrayWithoutInterpolatingIds() {
        var ids = IntStream.range(0, 20000).mapToObj(index -> "task-" + index).toList();
        var term = new Term();
        term.setValue(ids);
        var request = builder.createFragments("execution.task_id", null, term).toRequest();
        assertTrue(request.getSql().contains("any(cast((select ?) as varchar array))"));
        assertFalse(request.getSql().contains("task-"));
        assertEquals(1, request.getParameters().length);
        assertArrayEquals(ids.toArray(String[]::new), (String[]) request.getParameters()[0]);
    }

    @Test
    void emptySnapshotDeniesEveryExecutionAndDuplicateIdsDoNotRepeatFacts() {
        var term = new Term();
        term.setValue(List.of());
        assertEquals("1=0", builder.createFragments("execution.task_id", null, term).toRequest().getSql());
        term.setValue(List.of("task-a", "task-a", "task-b"));
        var request = builder.createFragments("execution.task_id", null, term).toRequest();
        assertArrayEquals(new String[]{"task-a", "task-b"}, (String[]) request.getParameters()[0]);
    }
}
