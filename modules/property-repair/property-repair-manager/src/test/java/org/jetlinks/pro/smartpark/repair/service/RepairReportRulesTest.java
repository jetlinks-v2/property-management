package org.jetlinks.pro.smartpark.repair.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class RepairReportRulesTest {
 @Test void ordinaryAndPaidHaveDifferentReviewThresholds(){
  RepairReportRow row=new RepairReportRow();row.setTotal(10);row.setNoDistributed(2);row.setAccepted(4);
  row.setCompleted(5);row.setAbandoned(1);row.setOwnerEvaluated(3);row.setOwnerPositive(2);row.setPaidReviews(4);row.setPaidPositive(3);
  assertEquals(new BigDecimal("50.00"),row.getOrderRate());
  assertEquals(new BigDecimal("55.56"),row.getReportCompletionRate());
  assertEquals(new BigDecimal("66.67"),row.getPositiveReviewRate());
  assertEquals(new BigDecimal("75.00"),row.getPaidPositiveRate());
  assertEquals(BigDecimal.ZERO,RepairReportRow.rate(0,0));
 }
 @Test void overdueUsesLocalCalendarDaysAndPreservesLegacyGap(){
  LocalDate today=LocalDate.of(2026,11,3);ZoneId zone=ZoneId.of("Asia/Shanghai");
  Object[] actual=RepairReportService.overdueBoundaries(today);
  assertEquals(today.minusDays(59).atStartOfDay(zone).toInstant().toEpochMilli(),actual[0]);
  assertEquals(today.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli(),actual[1]);
  assertEquals(today.minusDays(464).atStartOfDay(zone).toInstant().toEpochMilli(),actual[8]);
 }
 @Test void configuredSqlIsAcceptedByFrameworkParser() throws Exception {
  String sql=new ClassPathResource("reports/repair-summary.sql").getContentAsString(StandardCharsets.UTF_8);
  assertNotNull(net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sql));
 }
 @Test void csvEscapesQuotesNewlinesAndSpreadsheetFormulas(){
  assertEquals("\"a,\"\"b\"\"\n第二行\"",RepairCsv.cell("a,\"b\"\n第二行"));
  assertEquals("\"'=1+1\"",RepairCsv.cell("=1+1"));
  assertEquals("\"-1.25\"",RepairCsv.cell(new BigDecimal("-1.25")));
  assertEquals("\"'@SUM(A1)\"",RepairCsv.cell("@SUM(A1)"));
 }
}
