package org.jetlinks.pro.patrol.service;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolRole;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PatrolRosterWorkbookTest {
    static byte[] workbook(String user, String shift) throws Exception {
        var post = new PatrolRole(); post.setId("post"); post.setName("保安"); post.setUserIds(List.of(user));
        byte[] template = PatrolRosterWorkbook.template(YearMonth.of(2026, 11), List.of(post), Map.of(user, "张三"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(template)); var output = new ByteArrayOutputStream()) {
            var row = book.getSheet("班次").createRow(1);
            row.createCell(0).setCellValue("夜班"); row.createCell(1).setCellValue("22:00"); row.createCell(2).setCellValue("06:00");
            book.getSheet("排班表").getRow(5).createCell(4).setCellValue(shift);
            book.write(output); return output.toByteArray();
        }
    }

    @Test void roundTripRetainsIdsAndCrossDayOwnershipAndIgnoresExample() throws Exception {
        var rows = PatrolRosterWorkbook.read(workbook("alice", "夜班"), YearMonth.of(2026, 11));
        assertEquals(1, rows.size()); var row = rows.get(0);
        assertEquals("alice", row.getUserId()); assertEquals("post", row.getRoleId());
        assertEquals(LocalDate.of(2026, 11, 1), row.getBusinessDate());
        assertEquals(8 * 3600000L, row.getEndTime() - row.getStartTime());
        assertEquals(LocalDate.of(2026, 11, 2), Instant.ofEpochMilli(row.getEndTime()).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate());
    }
    @Test void restIsDisplayOnlyAndUnknownShiftsWrongMonthAndDuplicatesFail() throws Exception {
        var rows = PatrolRosterWorkbook.read(workbook("alice", "休息"), YearMonth.of(2026, 11));
        assertTrue(rows.get(0).isRest());
        assertThrows(BusinessException.class, () -> PatrolRosterWorkbook.read(workbook("alice", "不存在"), YearMonth.of(2026, 11)));
        assertThrows(BusinessException.class, () -> PatrolRosterWorkbook.read(workbook("alice", "夜班"), YearMonth.of(2026, 12)));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(workbook("alice", "夜班"))); var out = new ByteArrayOutputStream()) {
            var row = book.getSheet("排班表").createRow(6);
            row.createCell(0).setCellValue("post"); row.createCell(2).setCellValue("alice"); row.createCell(4).setCellValue("夜班");
            book.write(out);
            assertThrows(BusinessException.class, () -> PatrolRosterWorkbook.read(out.toByteArray(), YearMonth.of(2026, 11)));
        }
    }

    @Test void staffCanBeScheduledWithoutBeingAssignedToARole() throws Exception {
        var month = YearMonth.of(2026, 11);
        var bytes = PatrolRosterWorkbook.template(month, List.of(), Map.of("alice", "张三"));
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes)); var out = new ByteArrayOutputStream()) {
            var row = book.getSheet("排班表").getRow(5);
            assertEquals("alice", row.getCell(2).getStringCellValue());
            row.createCell(4).setCellValue("休息"); book.write(out);
            var imported = PatrolRosterWorkbook.read(out.toByteArray(), month);
            assertEquals(1, imported.size()); assertEquals("", imported.get(0).getRoleId());
            assertEquals("alice", imported.get(0).getUserId());
        }
    }
}
