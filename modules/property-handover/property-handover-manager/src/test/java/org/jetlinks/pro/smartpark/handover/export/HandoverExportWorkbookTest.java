package org.jetlinks.pro.smartpark.handover.export;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import javax.imageio.ImageIO;

class HandoverExportWorkbookTest {
    @TempDir Path temp;

    @Test
    void equipmentExportContainsChineseTypedColumnsAndRetainsExtensionData() throws Exception {
        Path output = temp.resolve("设备清单.xlsx");
        try (var writer = new HandoverExportWorkbook("equipment")) {
            writer.row(
                    "pump",
                    Map.of(
                            "name",
                            "消防水泵",
                            "categoryName",
                            "消防设备",
                            "quantity",
                            new java.math.BigDecimal("2.125"),
                            "supplier",
                            "重庆供应公司",
                            "productionDate",
                            java.time.Instant.parse("2025-03-01T00:00:00Z").toEpochMilli(),
                            "properties",
                            Map.of("ratedVoltage", "380V")));
            writer.finish();
            Files.copy(writer.workbook, output);
        }
        try (var book = new XSSFWorkbook(output.toFile())) {
            var sheet = book.getSheetAt(0);
            var header = sheet.getRow(0);
            var columns = new HashMap<String, Integer>();
            header.forEach(cell -> columns.put(cell.getStringCellValue(), cell.getColumnIndex()));
            var row = sheet.getRow(1);
            assertEquals("消防设备", row.getCell(columns.get("设备分类")).getStringCellValue());
            assertEquals("2.125", row.getCell(columns.get("数量")).getStringCellValue());
            assertEquals("重庆供应公司", row.getCell(columns.get("供货厂家/供应商")).getStringCellValue());
            assertTrue(
                    row.getCell(columns.get("生产日期")).getStringCellValue().startsWith("2025-03-01"));
            assertTrue(row.getCell(columns.get("全部扩展字段")).getStringCellValue().contains("380V"));
        }
    }

    @Test
    void safeTextAndPhotoTypesRemainInIndependentColumnsAndAllRelationsResolve() throws Exception {
        Path output = temp.resolve("safe.xlsx"), directory;
        try (var writer = new HandoverExportWorkbook("problem")) {
            directory = writer.directory;
            Path image = directory.resolve("sample.png");
            ImageIO.write(
                    new BufferedImage(4096, 2048, BufferedImage.TYPE_INT_RGB),
                    "png",
                    image.toFile());
            writer.row(
                    "first",
                    Map.of(
                            "description",
                            "=SUM(A1:A2)<&\"\n中文",
                            "properties",
                            Map.of("unknownLegacyField", "保留")));
            writer.picture(image, "problem");
            writer.picture(image, "rectification");
            writer.row("without-photo", Map.of("description", "无图记录"));
            writer.finish();
            Files.copy(writer.workbook, output);
        }
        assertFalse(Files.exists(directory));
        try (var book = new XSSFWorkbook(output.toFile())) {
            var sheet = book.getSheetAt(0);
            assertEquals("问题描述", sheet.getRow(0).getCell(0).getStringCellValue());
            var text = sheet.getRow(1).getCell(0);
            assertEquals(CellType.STRING, text.getCellType());
            assertEquals("=SUM(A1:A2)<&\"\n中文", text.getStringCellValue());
            assertEquals("无图记录", sheet.getRow(4).getCell(0).getStringCellValue());
            var pictures = sheet.getDrawingPatriarch().getShapes();
            assertEquals(2, pictures.size());
            XSSFPicture before = (XSSFPicture) pictures.get(0),
                    after = (XSSFPicture) pictures.get(1);
            assertEquals(9, before.getClientAnchor().getCol1());
            assertEquals(10, after.getClientAnchor().getCol1());
            assertEquals(2, before.getClientAnchor().getRow1());
            assertEquals(3, after.getClientAnchor().getRow1());
            assertNotNull(before.getPictureData().getData());
            assertNotNull(after.getPictureData().getData());
            try (var stream = new java.io.ByteArrayInputStream(before.getPictureData().getData())) {
                var decoded = ImageIO.read(stream);
                assertTrue(decoded.getWidth() <= 512);
                assertTrue(decoded.getHeight() <= 512);
            }
        }
    }

    @Test
    void invalidImageFailsAndTemporaryDirectoryIsRemoved() throws Exception {
        Path directory;
        try (var book = new HandoverExportWorkbook("problem")) {
            directory = book.directory;
            Path invalid = directory.resolve("broken");
            Files.writeString(invalid, "not an image");
            assertThrows(java.io.IOException.class, () -> book.picture(invalid, "problem"));
            assertEquals(0, book.imageCount());
        }
        assertFalse(Files.exists(directory));
    }

    @Test
    void all19457ImagesGenerateUnder96MiBHeapAndOpenWithPoi() throws Exception {
        Path output = temp.resolve("19457.xlsx");
        String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath =
                System.getProperty(
                        "surefire.test.class.path", System.getProperty("java.class.path"));
        Path log = temp.resolve("capacity.log");
        Path arguments = temp.resolve("java.args");
        Files.writeString(
                arguments,
                "-Xmx96m\n-Djava.awt.headless=true\n-cp\n\""
                        + classpath.replace('\\', '/')
                        + "\"\n"
                        + HandoverExportCapacityProbe.class.getName()
                        + "\n\""
                        + output.toString().replace('\\', '/')
                        + "\"\n19457\n");
        Process child =
                new ProcessBuilder(java, "@" + arguments)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        assertTimeoutPreemptively(
                Duration.ofMinutes(3),
                () -> assertEquals(0, child.waitFor(), Files.readString(log)));
        String report = Files.readString(log);
        System.out.println(report);
        assertTrue(report.contains("images=19457"));
        // This known, locally generated fixture intentionally has 19,457 separate media entries.
        // Raise only the reader's entry-count bound for this test, restoring it immediately
        // afterwards.
        long originalCount = org.apache.poi.openxml4j.util.ZipSecureFile.getMaxFileCount();
        org.apache.poi.openxml4j.util.ZipSecureFile.setMaxFileCount(19500);
        try (var book = new XSSFWorkbook(output.toFile())) {
            assertEquals(19457, book.getAllPictures().size());
            var sheet = book.getSheetAt(0);
            assertEquals(19459, sheet.getPhysicalNumberOfRows());
            assertEquals(19457, sheet.getDrawingPatriarch().getShapes().size());
            XSSFPicture last = (XSSFPicture) sheet.getDrawingPatriarch().getShapes().get(19456);
            assertEquals(9, last.getClientAnchor().getCol1());
            assertEquals(19458, last.getClientAnchor().getRow1());
            assertTrue(last.getPictureData().getData().length > 0);
        } finally {
            org.apache.poi.openxml4j.util.ZipSecureFile.setMaxFileCount(originalCount);
        }
    }

    @Test
    void poiRetainsPicturePartsWhenSxssfRowsAreFlushed() throws Exception {
        // Public API evidence for the disk-spooled writer's capacity decision.
        try (var book = new org.apache.poi.xssf.streaming.SXSSFWorkbook(1)) {
            byte[] image;
            try (var out = new java.io.ByteArrayOutputStream()) {
                ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", out);
                image = out.toByteArray();
            }
            var sheet = book.createSheet();
            for (int i = 0; i < 1001; i++) {
                sheet.createRow(i);
                book.addPicture(image, org.apache.poi.ss.usermodel.Workbook.PICTURE_TYPE_PNG);
            }
            assertNull(sheet.getRow(0));
            assertEquals(1001, book.getXSSFWorkbook().getAllPictures().size());
            assertArrayEquals(image, book.getXSSFWorkbook().getAllPictures().get(1000).getData());
            book.dispose();
        }
    }
}
