package org.jetlinks.pro.property.finance.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;

import org.jetlinks.pro.property.finance.entity.ReceivableEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

class FinanceDocumentNumbersTest {
    @Test
    void concurrentLargeImportNumbersAreUniqueAndRecognizable() {
        Set<String> numbers = ConcurrentHashMap.newKeySet();
        var day =
                LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.BASIC_ISO_DATE);
        IntStream.range(0, 50000)
                .parallel()
                .forEach(
                        i -> {
                            var prefix = new String[] {"YS", "SS", "YJ", "YC", "LS", "TK"}[i % 6];
                            var number = FinanceDocumentNumbers.next(prefix);
                            assertTrue(number.startsWith(prefix + day));
                            assertTrue(number.substring(10).matches("[0-9]+"));
                            assertTrue(number.length() <= 40);
                            assertTrue(numbers.add(number), "duplicate business number");
                        });
        assertEquals(50000, numbers.size());
    }

    @Test
    void persistedDraftRoundTripKeepsNumberAndInternalIdentity() {
        var bill = new ReceivableEntity();
        bill.setId("internal-identity");
        FinanceDocumentNumbers.assign(bill);
        var saved = JSON.parseObject(JSON.toJSONString(bill), ReceivableEntity.class);
        assertEquals(bill.getBillNo(), saved.getBillNo());
        assertEquals("internal-identity", saved.getId());
        assertNull(new ReceivableEntity().getBillNo());
        FinanceDocumentNumbers.assign(saved);
        assertEquals(bill.getBillNo(), saved.getBillNo());
    }

    @Test
    void receivableExportKeepsBusinessNumberAndOmitsContractAndInternalId() throws Exception {
        var row = new org.jetlinks.pro.property.finance.receivable.ReceivableView();
        row.setId("internal-identity");
        row.setContractCode("legacy-contract");
        FinanceDocumentNumbers.assign(row);
        row.setCustomerNames(java.util.List.of("张某"));
        row.setActualReceivableAmount(new java.math.BigDecimal("100.25"));
        var bytes =
                org.jetlinks.pro.property.finance.receivable.ReceivableWorkbook.write(
                        java.util.List.of(row));
        try (var book =
                new org.apache.poi.xssf.usermodel.XSSFWorkbook(
                        new java.io.ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertEquals("应收单号", sheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals(row.getBillNo(), sheet.getRow(1).getCell(0).getStringCellValue());
            assertEquals("100.25", sheet.getRow(1).getCell(11).getStringCellValue());
            assertEquals(sheet.getRow(0).getLastCellNum(), sheet.getRow(1).getLastCellNum());
            for (var cells : sheet)
                for (var cell : cells) {
                    var value = cell.getStringCellValue();
                    assertNotEquals("合同编号", value);
                    assertNotEquals("legacy-contract", value);
                    assertNotEquals("internal-identity", value);
                }
        }
    }
}
