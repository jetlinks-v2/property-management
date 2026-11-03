package org.jetlinks.pro.property.base.service;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 验证分页前筛选、SQL 条件优先级及导出范围，避免派生列表悄悄忽略请求条件。 */
class ArchiveQueryTest {
    private static final Set<String> FIELDS = Set.of("id", "area", "completed");
    private static final List<Row> ROWS =
            List.of(
                    new Row("a", new BigDecimal("100.00"), false),
                    new Row("b", new BigDecimal("80.00"), true),
                    new Row("c", new BigDecimal("20.00"), false));

    @Test
    void filterSortCountAndPageAreAppliedInOrder() {
        var query = new QueryParamEntity();
        query.and("area", "gte", 80);
        query.orderBy("area").asc();
        query.setPageSize(1);
        query.setPageIndex(1);
        var page = ArchiveQuery.page(ROWS, query, FIELDS);
        assertEquals(2, page.getTotal());
        assertEquals("a", page.getData().get(0).getId());
        assertEquals(
                List.of("b", "a"),
                ArchiveQuery.filter(ROWS, query, FIELDS).stream().map(Row::getId).toList());
    }

    @Test
    void andBindsMoreTightlyThanOrAndUnknownQueriesFail() {
        var query = new QueryParamEntity();
        query.and("id", "eq", "a").or("id", "eq", "b").and("completed", "eq", true);
        assertEquals(
                List.of("a", "b"),
                ArchiveQuery.filter(ROWS, query, FIELDS).stream().map(Row::getId).toList());
        query = new QueryParamEntity();
        query.and("privateSecret", "eq", "guess");
        var invalid = query;
        assertThrows(BusinessException.class, () -> ArchiveQuery.filter(ROWS, invalid, FIELDS));
        query = new QueryParamEntity();
        query.and("id", "custom", "a");
        var unsupported = query;
        assertThrows(BusinessException.class, () -> ArchiveQuery.filter(ROWS, unsupported, FIELDS));
    }

    @Getter
    @AllArgsConstructor
    public static class Row {
        private final String id;
        private final BigDecimal area;
        private final Boolean completed;
    }
}
