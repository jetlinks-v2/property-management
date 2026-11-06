package org.jetlinks.pro.property.decoration.excel;

import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.w3c.dom.Element;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;

import static org.jetlinks.pro.property.decoration.excel.DecorationCheckWorkbook.problem;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.error;

/** Excel浮动图片及WPS DISPIMG图片只解析本工作簿内关系，不访问外部链接。 */
final class DecorationCheckImages {
    private static final String XML = "xl/cellimages.xml", RELS = "xl/_rels/cellimages.xml.rels";
    private static final String RELATIONSHIPS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final Pattern REFERENCE = Pattern.compile("(?i)^=?(?:_xlfn\\.)?DISPIMG\\s*\\(\\s*\"([^\"]+)\"\\s*(?:,\\s*\\d+)?\\s*\\)$");
    private DecorationCheckImages() {}
    static boolean isReference(String value) { return value != null && REFERENCE.matcher(value.trim()).matches(); }

    /** 上传文件在POI分配工作簿前预检展开体积；只保留图片关系与媒体，资源上限与上传限制对应。 */
    static Map<String, byte[]> archive(byte[] bytes) {
        var entries = new HashMap<String, byte[]>();
        long total = 0; int count = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            var buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > 5000) throw error("import_limit", 400);
                var name = entry.getName().replace('\\', '/');
                boolean keep = XML.equals(name) || RELS.equals(name) || name.startsWith("xl/media/");
                var output = new ByteArrayOutputStream();
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    total += read;
                    if (total > 64L * 1024 * 1024) throw error("import_limit", 400);
                    if (keep && output.size() <= 2 * 1024 * 1024) output.write(buffer, 0, read);
                }
                if (keep) entries.put(name, output.toByteArray());
            }
            return entries;
        } catch (IOException e) { throw error("import_file", 400); }
    }

    static Map<Integer, List<DecorationCheckWorkbook.Image>> read(Sheet sheet, Map<String, byte[]> archive, List<DecorationCheckWorkbook.Problem> errors) {
        var images = new LinkedHashMap<Integer, List<DecorationCheckWorkbook.Image>>();
        if (sheet instanceof XSSFSheet xssf && xssf.getDrawingPatriarch() != null) {
            for (var shape : xssf.getDrawingPatriarch().getShapes()) {
                if (!(shape instanceof XSSFPicture picture)) continue;
                var anchor = picture.getClientAnchor();
                int row = anchor.getRow1();
                if (anchor.getCol1() != 17 && !(anchor.getCol1() < 17 && anchor.getCol2() > 17)) {
                    problem(errors, row + 1, 17, "", "image_column"); continue;
                }
                var data = picture.getPictureData();
                images.computeIfAbsent(row, ignored -> new ArrayList<>()).add(new DecorationCheckWorkbook.Image(data.getData(), data.suggestFileExtension()));
            }
        }
        var references = new ArrayList<Reference>();
        for (var row : sheet) {
            for (var cell : row) {
                String value = cell.getCellType() == CellType.FORMULA ? cell.getCellFormula()
                    : cell.getCellType() == CellType.STRING ? cell.getStringCellValue() : "";
                var matcher = REFERENCE.matcher(value.trim());
                if (matcher.matches()) references.add(new Reference(row.getRowNum(), cell.getColumnIndex(), matcher.group(1)));
            }
        }
        if (references.isEmpty()) return images;
        try {
            var ids = new HashMap<String, String>();
            var cells = xml(archive.get(XML)).getElementsByTagNameNS("*", "cellImage");
            for (int index = 0; index < cells.getLength(); index++) {
                var cell = (Element) cells.item(index);
                var properties = cell.getElementsByTagNameNS("*", "cNvPr");
                var blips = cell.getElementsByTagNameNS("*", "blip");
                if (properties.getLength() > 0 && blips.getLength() > 0) {
                    ids.put(((Element) properties.item(0)).getAttribute("name"), ((Element) blips.item(0)).getAttributeNS(RELATIONSHIPS, "embed"));
                }
            }
            var targets = new HashMap<String, String>();
            var relationships = xml(archive.get(RELS)).getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < relationships.getLength(); index++) {
                var relation = (Element) relationships.item(index);
                if (!"External".equalsIgnoreCase(relation.getAttribute("TargetMode"))) targets.put(relation.getAttribute("Id"), relation.getAttribute("Target"));
            }
            for (var reference : references) {
                if (reference.column() != 17) { problem(errors, reference.row() + 1, 17, "", "image_column"); continue; }
                var target = targets.get(ids.get(reference.id()));
                if (target != null) target = target.replace('\\', '/');
                String name = target == null ? "" : (target.startsWith("/") ? Path.of(target.substring(1)) : Path.of("xl").resolve(target)).normalize().toString().replace('\\', '/');
                var content = name.startsWith("xl/media/") ? archive.get(name) : null;
                if (content == null) { problem(errors, reference.row() + 1, 17, "", "image_reference"); continue; }
                images.computeIfAbsent(reference.row(), ignored -> new ArrayList<>())
                    .add(new DecorationCheckWorkbook.Image(content, name.substring(name.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT)));
            }
        } catch (Exception e) {
            for (var reference : references) problem(errors, reference.row() + 1, 17, "", "image_reference");
        }
        return images;
    }
    private static org.w3c.dom.Document xml(byte[] bytes) throws Exception {
        if (bytes == null || bytes.length > 2 * 1024 * 1024) throw new IOException();
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true); factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }
    private record Reference(int row, int column, String id) {}
}
