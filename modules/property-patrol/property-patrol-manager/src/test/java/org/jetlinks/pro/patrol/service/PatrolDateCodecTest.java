package org.jetlinks.pro.patrol.service;

import org.hswebframework.ezorm.rdb.metadata.RDBTableMetadata;
import org.jetlinks.pro.patrol.configuration.PatrolJsonMetadataCustomizer;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class PatrolDateCodecTest {
    @Test void productionPropertyOperatorPreservesInspectionAnswers() {
        new PatrolJsonMetadataCustomizer(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerBusinessDateConverter();
        var previous = org.hswebframework.ezorm.core.GlobalConfig.getPropertyOperator();
        try {
            var properties = new org.jetlinks.pro.configure.crud.FastObjectPropertyOperator();
            var record = new org.jetlinks.pro.patrol.entity.PatrolExecutionEntity();
            var answers = java.util.List.of(
                    new org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.Answer("choice", "正常"),
                    new org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.Answer("number", "0.35"));
            properties.setProperty(record, "answers", answers);
            assertEquals(answers, record.getAnswers());

        } finally {
            org.hswebframework.ezorm.core.GlobalConfig.setPropertyOperator(previous);
        }
    }
    @Test void answerJsonContractRemainsCompatibleWithStoredHistory() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String json = "{\"itemId\":\"number\",\"value\":\"0.35\",\"attachmentId\":\"photo\"}";
        var answer = mapper.readValue(json, org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.Answer.class);
        assertEquals("0.35", answer.value());
        var encoded = mapper.readTree(mapper.writeValueAsString(answer));
        assertEquals(mapper.readTree(json), encoded);
        assertFalse(encoded.has("answerValue"));
    }

    @Test void productionPropertyOperatorPreservesImmutableBusinessDates() {
        new PatrolJsonMetadataCustomizer(new com.fasterxml.jackson.databind.ObjectMapper())
                .registerBusinessDateConverter();
        var previous = org.hswebframework.ezorm.core.GlobalConfig.getPropertyOperator();
        try {
            var properties = new org.jetlinks.pro.configure.crud.FastObjectPropertyOperator();
            var date = LocalDate.of(2026, 11, 3);
            var plan = new org.jetlinks.pro.patrol.entity.PatrolPlanEntity();
            properties.setProperty(plan, "effectiveFrom", date);
            properties.setProperty(plan, "effectiveTo", date);
            var roster = new PatrolRosterEntity();
            properties.setProperty(roster, "businessDate", date);
            assertEquals(date, plan.getEffectiveFrom());
            assertEquals(date, plan.getEffectiveTo());
            assertEquals(date, roster.getBusinessDate());
        } finally {
            org.hswebframework.ezorm.core.GlobalConfig.setPropertyOperator(previous);
        }
    }

    @Test void varcharBusinessDateUsesTextForBothWritesAndRangeConditions() {
        var table = new RDBTableMetadata(); table.setName("pt_roster");
        var database = new org.hswebframework.ezorm.rdb.metadata.RDBDatabaseMetadata(new org.hswebframework.ezorm.rdb.supports.h2.H2Dialect());
        var schema = new org.hswebframework.ezorm.rdb.metadata.RDBSchemaMetadata("PUBLIC");
        database.addSchema(schema); table.setSchema(schema);
        var column = table.newColumn(); column.setName("business_date"); column.setAlias("businessDate");
        column.setValueCodec(new org.hswebframework.ezorm.rdb.codec.DateTimeCodec("yyyy-MM-dd", LocalDate.class));
        table.addColumn(column);
        new PatrolJsonMetadataCustomizer(new com.fasterxml.jackson.databind.ObjectMapper())
                .customTable(PatrolRosterEntity.class, table);
        assertEquals("2026-11-01", column.getValueCodec().encode("2026-11-01"));
        assertEquals("2026-11-01", column.getValueCodec().encode(LocalDate.of(2026, 11, 1)));
        assertEquals(LocalDate.of(2026, 11, 1), column.getValueCodec().decode("2026-11-01"));
    }
}
