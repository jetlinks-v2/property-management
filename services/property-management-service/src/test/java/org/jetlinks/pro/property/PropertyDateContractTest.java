package org.jetlinks.pro.property;

import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetlinks.pro.patrol.entity.PatrolPlanEntity;
import org.jetlinks.pro.patrol.entity.PatrolRosterEntity;
import org.jetlinks.pro.patrol.service.PatrolStatisticsService;
import org.jetlinks.pro.patrol.service.statistics.PatrolOverview;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 巡更原来使用 ISO 日期；统一进程沿用其他模块的时间戳选项时也不能将日期变成数组。 */
class PropertyDateContractTest {
    @Test
    void patrolDatesKeepTheirPublishedStringFormat() {
        var mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToEnable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var day = LocalDate.of(2026, 11, 20);
        var plan = new PatrolPlanEntity();
        plan.setEffectiveFrom(day);
        plan.setEffectiveTo(day);
        var roster = new PatrolRosterEntity();
        roster.setBusinessDate(day);
        assertEquals("2026-11-20", mapper.valueToTree(plan).path("effectiveFrom").asText());
        assertEquals("2026-11-20", mapper.valueToTree(plan).path("effectiveTo").asText());
        assertEquals("2026-11-20", mapper.valueToTree(roster).path("businessDate").asText());
        assertEquals("2026-11-20", mapper.valueToTree(
                new PatrolStatisticsService.Report(day, day, List.of(), null)).path("from").asText());
        assertEquals("2026-11-20", mapper.valueToTree(
                new PatrolOverview.Report(day, day, 0, null, List.of(), List.of(), List.of()))
                .path("to").asText());
    }
}
