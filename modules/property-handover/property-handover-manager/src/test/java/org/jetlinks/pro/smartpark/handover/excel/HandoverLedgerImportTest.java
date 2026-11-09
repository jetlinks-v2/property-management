package org.jetlinks.pro.smartpark.handover.excel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.authorization.Authentication;
import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.io.file.FileManager;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.smartpark.handover.entity.*;
import org.jetlinks.pro.smartpark.handover.service.HandoverContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.*;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.*;
import reactor.util.context.Context;
import java.io.*;
import java.util.*;

/** 原模板布局、每行独立项目匹配及零写入预检回归。 */
class HandoverLedgerImportTest {
    static final HandoverContext ACTOR = new HandoverContext("tenant", "app", "user", "龚超");

    HandoverImportCatalog.LedgerCatalog catalog() {
        var projects = new ArrayList<PropertyOrganizationInfo>();
        var catalogs = new HashMap<String, HandoverImportCatalog.Catalog>();
        for (String id : List.of("甲", "乙")) {
            var project = new PropertyOrganizationInfo(); project.setId(id); project.setName(id + "项目"); projects.add(project);
            var category = new HandoverCategoryEntity(); category.setId(id + "-category"); category.setName("消防"); category.setEnabled(true);
            var unit = new HandoverResponsibilityEntity(); unit.setOrganizationId(id + "-unit"); unit.setName("施工单位"); unit.setEnabled(true);
            catalogs.put(id, new HandoverImportCatalog.Catalog(List.of(category), List.of(unit), List.of(), List.of()));
        }
        return new HandoverImportCatalog.LedgerCatalog(projects, catalogs, ACTOR);
    }

    byte[] workbook(String... projects) throws Exception {
        var buffer = DataBufferUtils.join(HandoverWorkbook.template("ledger", Map.of("projectName", List.of("甲项目", "乙项目")))).block();
        byte[] bytes = new byte[buffer.readableByteCount()]; buffer.read(bytes); DataBufferUtils.release(buffer);
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            var sheet = book.getSheetAt(0);
            assertEquals(List.of("项目名称", "问题分类", "问题描述", "责任单位", "问题位置", "问题图片", "检查时间", "发起人"),
                    java.util.stream.IntStream.range(0, 8).mapToObj(c -> sheet.getRow(8).getCell(c).getStringCellValue()).toList());
            assertEquals(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex(), book.getFontAt(sheet.getRow(8).getCell(0).getCellStyle().getFontIndex()).getColor());
            for (int i = 0; i < projects.length; i++) {
                var row = sheet.createRow(9 + i);
                String[] values = {projects[i], "消防", "渗漏", "施工单位", "1栋大堂", "", "2026-11-09 09:30:00", ""};
                for (int c = 0; c < values.length; c++) row.createCell(c).setCellValue(values[c]);
            }
            var output = new ByteArrayOutputStream(); book.write(output); return output.toByteArray();
        }
    }

    @Test void referenceHeaderAtRowNineKeepsPhysicalRowsAndMatchesEachProjectIndependently() throws Exception {
        var rows = HandoverWorkbook.read(workbook("甲项目", "乙项目"), "ledger");
        assertEquals(List.of(10, 11), rows.stream().map(HandoverWorkbook.ImportRow::row).toList());
        for (var row : rows) {
            var entity = (HandoverLedgerEntity) HandoverImportMapper.ledger(row, catalog()).value();
            String project = row.row() == 10 ? "甲" : "乙";
            assertEquals(project, entity.getProjectId()); assertEquals(project + "-category", entity.getCategoryId());
            assertEquals(project + "-unit", entity.getOrganizationId()); assertNotNull(entity.getInspectionTime());
            assertNull(entity.getEntryUserId()); // 身份最终由保存服务的认证上下文生成。
        }
    }

    @Test void unknownAmbiguousProjectAndImpersonatedActorHavePhysicalRowErrors() throws Exception {
        var row = HandoverWorkbook.read(workbook("无权限项目"), "ledger").get(0);
        var error = assertThrows(HandoverImportException.class, () -> HandoverImportMapper.ledger(row, catalog()));
        assertEquals(10, error.getErrors().get(0).row()); assertEquals("项目名称", error.getErrors().get(0).column());
        var values = new HashMap<>(row.values()); values.put("projectName", "甲项目"); values.put("entryUserName", "其他人");
        var forged = new HandoverWorkbook.ImportRow(12, values, List.of());
        assertEquals("发起人", assertThrows(HandoverImportException.class, () -> HandoverImportMapper.ledger(forged, catalog())).getErrors().get(0).column());
        var options = catalog(); var duplicated = new ArrayList<>(options.projects()); duplicated.add(options.projects().get(0));
        values.put("entryUserName", "");
        assertThrows(HandoverImportException.class, () -> HandoverImportMapper.ledger(forged, new HandoverImportCatalog.LedgerCatalog(duplicated, options.catalogs(), ACTOR)));
    }

    @Test void laterRowBusinessFailurePreventsAllWritesAndFileUploads() throws Exception {
        var catalog = mock(HandoverImportCatalog.class); var writer = mock(HandoverImportWriter.class); var files = mock(FileManager.class);
        when(catalog.ledger(anyList())).thenReturn(Mono.just(catalog()));
        when(writer.validate(any())).thenAnswer(call -> "乙".equals(((HandoverLedgerEntity) call.getArgument(0)).getProjectId())
                ? Mono.error(new BusinessException("error.handover.category_invalid", 400)) : Mono.empty());
        var part = mock(FilePart.class); when(part.filename()).thenReturn("前介台账问题导入模板.xlsx");
        byte[] bytes = workbook("甲项目", "乙项目"); when(part.content()).thenReturn(Flux.defer(() -> Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(bytes))));
        var auth = mock(Authentication.class); when(auth.hasPermission("handover-config", "import")).thenReturn(true);
        var service = new HandoverExcelService(catalog, writer, files);
        var error = assertThrows(HandoverImportException.class, () -> service.importFile("ledger", null, null, null, part).contextWrite(Context.of(Authentication.class, auth)).block());
        assertEquals(11, error.getErrors().get(0).row()); verify(writer, times(2)).validate(any()); verify(writer, never()).write(any()); verifyNoInteractions(files);
    }
}
