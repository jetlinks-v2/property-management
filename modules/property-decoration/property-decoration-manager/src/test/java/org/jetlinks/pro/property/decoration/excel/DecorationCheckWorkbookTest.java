package org.jetlinks.pro.property.decoration.excel;

import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.hswebframework.web.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** 使用真实xlsx数据、浮动图片和WPS单元格图片验证原PC导入格式。 */
class DecorationCheckWorkbookTest {
    private static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jP1sAAAAASUVORK5CYII=");
    private static List<String> row(String input, String name) {
        return new ArrayList<>(List.of("日常巡检", "测试项目", "住宅", "首次装修", "施工安全", "1", name, input, "是", "是", "1", "", "", "", "", "", "", "", ""));
    }
    private static XSSFWorkbook workbook(List<List<String>> rows) {
        var book = new XSSFWorkbook(); var sheet = book.createSheet("检查配置");
        var header = sheet.createRow(0);
        for (int col = 0; col < DecorationCheckWorkbook.HEADERS.size(); col++) header.createCell(col).setCellValue(DecorationCheckWorkbook.HEADERS.get(col));
        for (int index = 0; index < rows.size(); index++) {
            var row = sheet.createRow(index + 1);
            for (int column = 0; column < rows.get(index).size(); column++) row.createCell(column).setCellValue(rows.get(index).get(column));
        }
        return book;
    }
    private static byte[] bytes(XSSFWorkbook book) throws Exception {
        try (book; var out = new ByteArrayOutputStream()) { book.write(out); return out.toByteArray(); }
    }

    @Test void nineteenColumnWorkbookPreservesStandardsNumericZeroOptionsAndPhysicalRows() throws Exception {
        var text = row("文本", "文本项目"); text.set(11, "应保持整洁");
        var number = row("数值", "数值项目"); number.set(12, "宽度"); number.set(13, "0"); number.set(14, "10.5"); number.set(9, "0");
        var option = row("选项", "选项项目"); option.set(15, "合格|不合格"); option.set(16, "合格");
        var image = row("图像", "图像项目"); image.set(18, "外立面完整");
        var parsed = DecorationCheckWorkbook.read(bytes(workbook(List.of(text, List.of(), number, option, image))));
        assertTrue(parsed.errors().isEmpty(), parsed.errors().toString()); assertEquals(4, parsed.validCount());
        assertEquals(List.of(2, 4, 5, 6), parsed.entries().stream().map(DecorationCheckWorkbook.Entry::rowNumber).toList());
        assertEquals("应保持整洁", parsed.entries().get(0).item().getExample());
        assertEquals(0, parsed.entries().get(1).item().getMin().signum()); assertFalse(parsed.entries().get(1).item().isEnabled());
        assertEquals(List.of("合格", "不合格"), parsed.entries().get(2).item().getOptions());
        assertEquals("合格", parsed.entries().get(2).item().getStandard()); assertEquals("外立面完整", parsed.entries().get(3).item().getStandard());
        assertEquals("daily", parsed.entries().get(0).scene()); assertEquals("first", parsed.entries().get(0).decorationType());
        assertEquals(1, parsed.entries().get(0).categorySort());
    }

    @Test void invalidRowsReportFieldAndPhysicalLineWithoutDiscardingOtherRows() throws Exception {
        var valid = row("文本", "有效项");
        var invalid = row("数值", "反向数值"); invalid.set(13, "20"); invalid.set(14, "10"); invalid.set(5, "1.5"); invalid.set(8, "maybe");
        var options = row("选项", "重复选项"); options.set(15, "A|A"); options.set(16, "B");
        var missing = row("文本", ""); missing.set(0, "未知场景");
        var parsed = DecorationCheckWorkbook.read(bytes(workbook(List.of(valid, invalid, options, missing))));
        assertEquals(1, parsed.validCount()); assertEquals(4, parsed.entries().size());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 3 && e.columnName().equals("最大标准值") && e.code().equals("range")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 3 && e.code().equals("sort")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 3 && e.code().equals("boolean")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 4 && e.code().equals("option_default")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 5 && e.code().equals("required")));
    }

    @Test void duplicateItemAndInconsistentCategoryOrderAreRejectedAcrossRows() throws Exception {
        var first = row("文本", "同名项"); var duplicate = row("文本", "同名项"); var other = row("文本", "另一个项");
        other.set(5, "2");
        var parsed = DecorationCheckWorkbook.read(bytes(workbook(List.of(first, duplicate, other))));
        assertEquals(1, parsed.validCount());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 3 && e.code().equals("duplicate_item")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.rowNumber() == 4 && e.code().equals("category_sort")));
    }

    @Test void floatingPngIsAssociatedWithItsOwnImageRow() throws Exception {
        var book = workbook(List.of(row("图像", "图片项")));
        var anchor = new XSSFClientAnchor(); anchor.setCol1(17); anchor.setCol2(18); anchor.setRow1(1); anchor.setRow2(2);
        book.getSheetAt(0).createDrawingPatriarch().createPicture(anchor, book.addPicture(PNG, Workbook.PICTURE_TYPE_PNG));
        var parsed = DecorationCheckWorkbook.read(bytes(book));
        assertTrue(parsed.errors().isEmpty(), parsed.errors().toString());
        assertArrayEquals(PNG, parsed.entries().get(0).image().bytes()); assertEquals("png", parsed.entries().get(0).image().extension());
    }

    @Test void misplacedMultipleOrNonImageTypePicturesProduceRowErrors() throws Exception {
        var book = workbook(List.of(row("文本", "文本项")));
        var drawing = book.getSheetAt(0).createDrawingPatriarch(); int picture = book.addPicture(PNG, Workbook.PICTURE_TYPE_PNG);
        for (int col : new int[]{16, 17, 17}) {
            var anchor = new XSSFClientAnchor(); anchor.setCol1(col); anchor.setCol2(col + 1); anchor.setRow1(1); anchor.setRow2(2);
            drawing.createPicture(anchor, picture);
        }
        var parsed = DecorationCheckWorkbook.read(bytes(book));
        assertEquals(0, parsed.validCount());
        assertTrue(parsed.errors().stream().anyMatch(e -> e.code().equals("image_column")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.code().equals("image_count")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.code().equals("image_type")));
    }

    @Test void wpsDispimgResolvesWorkbookMediaAndMissingOrExternalReferencesFail() throws Exception {
        var parsed = DecorationCheckWorkbook.read(wps("media/sample.png", "", false));
        assertTrue(parsed.errors().isEmpty(), parsed.errors().toString()); assertArrayEquals(PNG, parsed.entries().get(0).image().bytes());
        for (byte[] file : List.of(wps("https://example.invalid/image.png", "External", false), wps("media/missing.png", "", false), wps("media/sample.png", "", true))) {
            var invalid = DecorationCheckWorkbook.read(file);
            assertEquals(0, invalid.validCount()); assertTrue(invalid.errors().stream().anyMatch(e -> e.code().equals("image_reference")));
        }
    }

    private byte[] wps(String target, String mode, boolean malformedXml) throws Exception {
        var book = workbook(List.of(row("图像", "WPS图片项")));
        book.addPicture(PNG, Workbook.PICTURE_TYPE_PNG);
        book.getSheetAt(0).getRow(1).getCell(17).setCellFormula("_xlfn.DISPIMG(\"ID_1\",1)");
        var original = bytes(book);
        try (var out = new ByteArrayOutputStream(); var zip = new ZipOutputStream(out); var source = new ZipInputStream(new ByteArrayInputStream(original))) {
            ZipEntry entry;
            while ((entry = source.getNextEntry()) != null) { zip.putNextEntry(new ZipEntry(entry.getName())); source.transferTo(zip); zip.closeEntry(); }
            add(zip, "xl/cellimages.xml", (malformedXml ? "<!DOCTYPE evil [<!ENTITY x SYSTEM 'file:///nonexistent'>]><cellImages>&x;</cellImages>"
                : "<cellImages xmlns='urn:wps'><cellImage><cNvPr name='ID_1'/><blip xmlns:r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' r:embed='rId1'/></cellImage></cellImages>").getBytes(StandardCharsets.UTF_8));
            add(zip, "xl/_rels/cellimages.xml.rels", ("<Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Target='" + target + "' TargetMode='" + mode + "'/></Relationships>").getBytes(StandardCharsets.UTF_8));
            add(zip, "xl/media/sample.png", PNG);
            zip.finish(); return out.toByteArray();
        }
    }
    private static void add(ZipOutputStream zip, String path, byte[] bytes) throws Exception { zip.putNextEntry(new ZipEntry(path)); zip.write(bytes); zip.closeEntry(); }

    @Test void formulaeAndExternalTextCannotMasqueradeAsValuesOrPrivateImageReceipts() throws Exception {
        var book = workbook(List.of(row("数值", "公式项"), row("图像", "外链项")));
        book.getSheetAt(0).getRow(1).getCell(13).setCellFormula("1+1");
        book.getSheetAt(0).getRow(2).getCell(17).setCellValue("https://example.invalid/private.png");
        var parsed = DecorationCheckWorkbook.read(bytes(book));
        assertEquals(0, parsed.validCount()); assertTrue(parsed.errors().stream().anyMatch(e -> e.code().equals("formula")));
        assertTrue(parsed.errors().stream().anyMatch(e -> e.code().equals("image_reference")));
    }

    @Test void templateUsesSameHeadersHasNoAccidentalSampleAndRejectsEmptyOrOversizedData() throws Exception {
        var template = DecorationCheckWorkbook.template();
        var empty = assertThrows(BusinessException.class, () -> DecorationCheckWorkbook.read(template)); assertTrue(empty.getMessage().contains("import_empty"));
        var book = new XSSFWorkbook(new ByteArrayInputStream(template));
        assertEquals(19, book.getSheetAt(0).getRow(0).getLastCellNum()); assertEquals(1, book.getSheetAt(0).getPhysicalNumberOfRows());
        var line = book.getSheetAt(0).createRow(1); var values = row("文本", "模板项");
        for (int col = 0; col < values.size(); col++) line.createCell(col).setCellValue(values.get(col));
        assertTrue(DecorationCheckWorkbook.read(bytes(book)).errors().isEmpty());
        var huge = workbook(List.of(row("文本", "超量项"))); huge.getSheetAt(0).createRow(1002);
        var hugeBytes = bytes(huge); var limit = assertThrows(BusinessException.class, () -> DecorationCheckWorkbook.read(hugeBytes));
        assertTrue(limit.getMessage().contains("import_limit"));
    }
}
