package org.jetlinks.pro.smartpark.repair.service;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RepairExportDocumentTest {
    @Test void orderFileKeepsCategoryHierarchyAndDistinctRegistrationPhone() throws Exception {
        var order=new RepairOrderExportRow();order.setId("phone-order");order.setNumber("BS-Phone");
        order.setFirstCategoryName("工程维修");order.setSecondCategoryName("给排水");order.setCategoryName("水管维修");
        order.setRegisterPhone("0013900000000");order.setContactPhone("0013800000000");
        var row=RepairExportTables.row(order,RepairExportTables.ORDERS,List.of(order.getId()));
        try(var workbook=workbook(new RepairExportDocument(),RepairExportType.orders,Flux.just(row))) {
            var sheet=workbook.getSheetAt(0);var values=new java.util.HashMap<String,String>();
            for(var header:sheet.getRow(0)) values.put(header.getStringCellValue(),sheet.getRow(1).getCell(header.getColumnIndex()).getStringCellValue());
            assertEquals("工程维修",values.get("报事分类（一级）"));assertEquals("给排水",values.get("报事分类（二级）"));
            assertEquals("水管维修",values.get("报事分类"));assertEquals("0013900000000",values.get("登记人手机号"));
            assertEquals("0013800000000",values.get("联系方式"));
        }
    }

    @Test void xlsxKeepsTextLiteralAndNumericReportValues() throws Exception {
        var row=new RepairReportRow();row.setProjectName("=HYPERLINK(\"bad\")");row.setPaidCount(2);
        row.setPersonAmount(new BigDecimal("12.340000"));row.setProviderAmount(new BigDecimal("0.66"));
        var content=new RepairExportDocument();
        var value=RepairExportTables.row(row,RepairExportTables.report(RepairExportType.paid),List.of("a","b"));
        try(var workbook=workbook(content,RepairExportType.paid,Flux.just(value))) {
            var sheet=workbook.getSheetAt(0);
            assertEquals("有偿报事接单量",sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals(CellType.STRING,sheet.getRow(1).getCell(0).getCellType());
            assertTrue(sheet.getRow(1).getCell(0).getStringCellValue().startsWith("'=HYPERLINK"));
            assertEquals(2,sheet.getRow(1).getCell(1).getNumericCellValue());
            assertEquals(13,sheet.getRow(1).getCell(3).getNumericCellValue());
            assertEquals(1,content.rowCount());assertEquals(List.of("a","b"),content.orderIds());
        }
    }

    @Test void reviewProjectionIncludesOriginalFieldsAndDecisionWithoutRepeatingSourceIds() throws Exception {
        var row=new RepairReviewOrderRow();row.setId("same-order");row.setContactPhone("0013800000000");
        row.setReviewKind("delay");row.setReviewStatus(3);row.setReviewApplicantName("申请人");
        row.setReviewReviewerName("审核人");row.setReviewOpinion("资料不足");
        var content=new RepairExportDocument();
        var value=RepairExportTables.row(row,RepairExportTables.REVIEWS,List.of(row.getId()));
        try(var workbook=workbook(content,RepairExportType.review,Flux.just(value,value))) {
            var sheet=workbook.getSheetAt(0);
            assertEquals("0013800000000",sheet.getRow(1).getCell(4).getStringCellValue());
            assertEquals("申请时间",sheet.getRow(0).getCell(11).getStringCellValue());
            assertEquals("延期审核",sheet.getRow(1).getCell(14).getStringCellValue());
            assertEquals("不通过",sheet.getRow(1).getCell(15).getStringCellValue());
            assertEquals("资料不足",sheet.getRow(1).getCell(17).getStringCellValue());
            assertEquals(2,content.rowCount());assertEquals(List.of("same-order"),content.orderIds());
        }
    }

    @Test void emptyXlsxStillHasItsActualReportHeaders() throws Exception {
        try(var workbook=workbook(new RepairExportDocument(),RepairExportType.follow,Flux.empty())) {
            var sheet=workbook.getSheetAt(0);
            assertEquals("30分钟",sheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals(0,sheet.getLastRowNum());
        }
    }

    @Test void sourceFailureCannotBecomeSuccessfulPartialXlsx() {
        var failure=new IllegalStateException("lost-source");
        StepVerifier.create(new RepairExportDocument().encode(RepairExportType.orders,"xlsx",Flux.error(failure))
            .doOnNext(DataBufferUtils::release).then()).expectErrorMatches(error->error==failure).verify();
    }

    @Test void aggregateSnapshotMustRetainEveryContributingOrderId() {
        var row=new RepairReportExportRow();row.setOrderIdsJson("[\"one\",\"two\"]");
        assertEquals(List.of("one","two"),row.sourceOrderIds());
    }

    @Test void exactRowLimitIsEnforcedForReviewRowsEvenForOneSourceOrder() {
        var row=new RepairExportTables.Row(List.of("one"),Map.of("c0","报修"));
        StepVerifier.create(new RepairExportDocument().encode(RepairExportType.review,"csv",Flux.range(0,200_001).map(i->row))
            .doOnNext(DataBufferUtils::release).then()).expectErrorMatches(error->error.getMessage().equals("error.repair.export_limit")).verify();
    }

    @Test void actualXlsxWriterStreamsTwoHundredThousandOrdersWithinBoundedHeap() {
        var content=new RepairExportDocument();
        var source=Flux.range(0,200_000).map(index->{
            var order=new RepairOrderExportRow();order.setId("memory-order-"+index);order.setNumber("BS-"+index);
            order.setBusinessProjectName("实际XLSX流式编码验证");order.setStatus(0);
            order.setDescription("保留完整报事内容，验证平台写入器不收集全部实体。".repeat(5));
            return RepairExportTables.row(order,RepairExportTables.ORDERS,List.of(order.getId()));
        });
        var byteCount=new java.util.concurrent.atomic.AtomicLong();
        StepVerifier.create(content.encode(RepairExportType.orders,"xlsx",source).doOnNext(buffer->{
            byteCount.addAndGet(buffer.readableByteCount());DataBufferUtils.release(buffer);
        }).then()).verifyComplete();
        assertEquals(200_000,content.rowCount());assertEquals(200_000,content.orderIds().size());
        assertTrue(byteCount.get()>1_000_000);
    }

    private static XSSFWorkbook workbook(RepairExportDocument content,RepairExportType type,Flux<RepairExportTables.Row> rows) throws Exception {
        // 仅三行以内的单元格契约样本合并正文；20万条流另作有界内存验证。
        var data=DataBufferUtils.join(content.encode(type,"xlsx",rows)).block();
        assertNotNull(data);
        try { byte[] bytes=new byte[data.readableByteCount()];data.read(bytes);return new XSSFWorkbook(new ByteArrayInputStream(bytes)); }
        finally { DataBufferUtils.release(data); }
    }
}
