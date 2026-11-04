package org.jetlinks.pro.smartpark.handover.export;

import org.jetlinks.pro.smartpark.handover.excel.HandoverExcelSchema;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Disk-spooled OOXML writer. POI XSSFWorkbook.addPicture creates an XSSFPictureData package part
 * for every image; SXSSFWorkbook delegates addPicture to that same XSSFWorkbook. Thus SXSSF's row
 * window alone cannot bound retained image bytes for 19,457 photographs. Standard public OOXML
 * parts avoid that accumulation without reflection or library patches. At most one sampled image
 * (<=512x512) is decoded at once; XML and media spool to disk.
 */
final class HandoverExportWorkbook implements AutoCloseable {
    static final int MAX_ROWS = 1_048_576;
    static final int MAX_IMAGE_BYTES = 32 * 1024 * 1024;
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    final Path directory, workbook, manifest;
    private final Path sheet, drawing, relations;
    private final BufferedWriter xml, anchors, rels, ids;
    private final ZipOutputStream zip;
    private final List<HandoverExcelSchema.Column> columns;
    private final int problemColumn, rectificationColumn;
    private int rowCount, imageCount;
    private boolean finished;

    HandoverExportWorkbook(String kind) throws IOException {
        directory = Files.createTempDirectory("handover-export-");
        workbook = directory.resolve("export.xlsx");
        manifest = directory.resolve("sources.txt");
        sheet = directory.resolve("sheet.xml");
        drawing = directory.resolve("drawing.xml");
        relations = directory.resolve("drawing.rels");
        xml = Files.newBufferedWriter(sheet);
        anchors = Files.newBufferedWriter(drawing);
        rels = Files.newBufferedWriter(relations);
        ids = Files.newBufferedWriter(manifest);
        zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(workbook)));
        columns = new ArrayList<>(HandoverExcelSchema.columns(kind));
        problemColumn = index("photos");
        if (problemColumn < 0) {
            columns.add(new HandoverExcelSchema.Column("photos", "问题图片", false, ""));
        }
        rectificationColumn = columns.size();
        columns.add(new HandoverExcelSchema.Column("rectificationPhotos", "整改图片", false, ""));
        columns.add(new HandoverExcelSchema.Column("status", "状态", false, ""));
        columns.add(new HandoverExcelSchema.Column("auditStage", "审核阶段", false, ""));
        columns.add(new HandoverExcelSchema.Column("properties", "全部扩展字段", false, ""));
        columns.add(new HandoverExcelSchema.Column("files", "附件引用", false, ""));
        columns.add(new HandoverExcelSchema.Column("history", "操作节点", false, ""));
        xml.write(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\""
                        + NS
                        + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheetData>");
        anchors.write(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><xdr:wsDr"
                    + " xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\""
                    + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\">");
        rels.write(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        writeRow(columns.stream().map(HandoverExcelSchema.Column::title).toList(), false);
    }

    private int index(String key) {
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).key().equals(key)) return i;
        return -1;
    }

    synchronized void row(String id, Map<String, Object> values) throws IOException {
        List<String> cells = new ArrayList<>();
        for (var column : columns) cells.add(value(values, column.key()));
        writeRow(cells, false);
        ids.write(id);
        ids.newLine();
    }

    private static String value(Map<String, Object> values, String key) {
        Object v = values;
        for (String part : key.split("\\.")) {
            if (!(v instanceof Map<?, ?> m)) return "";
            v = m.get(part);
        }
        return v == null ? "" : v.toString();
    }

    synchronized void node(String text) throws IOException {
        List<String> cells = new ArrayList<>(Collections.nCopies(columns.size(), ""));
        cells.set(columns.size() - 1, text);
        writeRow(cells, false);
    }

    synchronized void picture(Path source, String type) throws IOException {
        BufferedImage image = sample(source);
        Path normalized = directory.resolve("normalized.png");
        try {
            if (!ImageIO.write(image, "png", normalized.toFile()))
                throw new IOException("Image PNG encoder unavailable");
            int number = ++imageCount;
            entry("xl/media/image" + number + ".png", normalized);
            writeRow(List.of(), true);
            int column =
                    "rectification".equals(type)
                            ? rectificationColumn
                            : (problemColumn < 0 ? rectificationColumn - 1 : problemColumn);
            int zeroRow = rowCount - 1;
            anchors.write(
                    "<xdr:oneCellAnchor><xdr:from><xdr:col>"
                            + column
                            + "</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>"
                            + zeroRow
                            + "</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext"
                            + " cx=\"1828800\" cy=\"1143000\"/><xdr:pic><xdr:nvPicPr><xdr:cNvPr"
                            + " id=\""
                            + number
                            + "\" name=\"image"
                            + number
                            + "\"/><xdr:cNvPicPr/></xdr:nvPicPr><xdr:blipFill><a:blip"
                            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                            + " r:embed=\"rId"
                            + number
                            + "\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill><xdr:spPr><a:prstGeom"
                            + " prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>");
            rels.write(
                    "<Relationship Id=\"rId"
                            + number
                            + "\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\""
                            + " Target=\"../media/image"
                            + number
                            + ".png\"/>");
        } finally {
            image.flush();
            Files.deleteIfExists(normalized);
        }
    }

    private static BufferedImage sample(Path source) throws IOException {
        if (Files.size(source) > MAX_IMAGE_BYTES)
            throw new IOException("Photo exceeds 32 MiB per-file limit");
        try (ImageInputStream stream = ImageIO.createImageInputStream(source.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("Photo is not a supported image");
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                int subsample = Math.max(1, (int) Math.ceil(Math.max(width, height) / 512d));
                var param = reader.getDefaultReadParam();
                param.setSourceSubsampling(subsample, subsample, 0, 0);
                BufferedImage decoded = reader.read(0, param);
                if (decoded == null) throw new IOException("Photo cannot be decoded");
                return decoded;
            } finally {
                reader.dispose();
            }
        }
    }

    private void writeRow(List<String> cells, boolean image) throws IOException {
        if (rowCount >= MAX_ROWS)
            throw new IOException(
                    "XLSX row limit (1048576, including header and photo/history rows) exceeded");
        int current = ++rowCount;
        xml.write(
                "<row r=\""
                        + current
                        + "\""
                        + (image ? " ht=\"96\" customHeight=\"1\"" : "")
                        + ">");
        for (int c = 0; c < cells.size(); c++) {
            String value = cells.get(c);
            if (value.length() > 32767)
                throw new IOException("XLSX cell text limit (32767) exceeded");
            // Inline strings cannot execute formula-looking input, including = + - @ prefixes.
            xml.write(
                    "<c r=\""
                            + column(c)
                            + current
                            + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                            + escape(value)
                            + "</t></is></c>");
        }
        xml.write("</row>");
    }

    static String column(int c) {
        StringBuilder v = new StringBuilder();
        for (int n = c + 1; n > 0; n = (n - 1) / 26) v.append((char) ('A' + (n - 1) % 26));
        return v.reverse().toString();
    }

    static String escape(String text) {
        StringBuilder out = new StringBuilder();
        text.codePoints()
                .forEach(
                        c -> {
                            if (c == 9
                                    || c == 10
                                    || c == 13
                                    || c >= 32 && c <= 0xD7FF
                                    || c >= 0xE000 && c <= 0xFFFD
                                    || c >= 0x10000 && c <= 0x10FFFF) {
                                switch (c) {
                                    case '&' -> out.append("&amp;");
                                    case '<' -> out.append("&lt;");
                                    case '>' -> out.append("&gt;");
                                    case '"' -> out.append("&quot;");
                                    default -> out.appendCodePoint(c);
                                }
                            }
                        });
        return out.toString();
    }

    synchronized void finish() throws IOException {
        xml.write("</sheetData><drawing r:id=\"rId1\"/></worksheet>");
        xml.close();
        anchors.write("</xdr:wsDr>");
        anchors.close();
        rels.write("</Relationships>");
        rels.close();
        ids.close();
        entry("xl/worksheets/sheet1.xml", sheet);
        entry("xl/drawings/drawing1.xml", drawing);
        entry("xl/drawings/_rels/drawing1.xml.rels", relations);
        text(
                "[Content_Types].xml",
                "<Types"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default"
                    + " Extension=\"rels\""
                    + " ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default"
                    + " Extension=\"xml\" ContentType=\"application/xml\"/><Default"
                    + " Extension=\"png\" ContentType=\"image/png\"/><Override"
                    + " PartName=\"/xl/workbook.xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/><Override"
                    + " PartName=\"/xl/worksheets/sheet1.xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/><Override"
                    + " PartName=\"/xl/drawings/drawing1.xml\""
                    + " ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/></Types>");
        text(
                "_rels/.rels",
                "<Relationships"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                    + " Id=\"rId1\""
                    + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\""
                    + " Target=\"xl/workbook.xml\"/></Relationships>");
        text(
                "xl/workbook.xml",
                "<workbook xmlns=\""
                        + NS
                        + "\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets><sheet"
                        + " name=\"承接查验\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>");
        text(
                "xl/_rels/workbook.xml.rels",
                "<Relationships"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                    + " Id=\"rId1\""
                    + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\""
                    + " Target=\"worksheets/sheet1.xml\"/></Relationships>");
        text(
                "xl/worksheets/_rels/sheet1.xml.rels",
                "<Relationships"
                    + " xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship"
                    + " Id=\"rId1\""
                    + " Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\""
                    + " Target=\"../drawings/drawing1.xml\"/></Relationships>");
        zip.close();
        finished = true;
    }

    private void entry(String name, Path path) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        Files.copy(path, zip);
        zip.closeEntry();
    }

    private void text(String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    int imageCount() {
        return imageCount;
    }

    int rowCount() {
        return rowCount;
    }

    @Override
    public synchronized void close() throws IOException {
        IOException failure = null;
        for (Closeable resource : List.of(xml, anchors, rels, ids, zip))
            try {
                resource.close();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                try {
                    Files.deleteIfExists(path);
                } catch (IOException error) {
                    if (failure == null) failure = error;
                    else failure.addSuppressed(error);
                }
        }
        if (failure != null) throw failure;
    }
}
