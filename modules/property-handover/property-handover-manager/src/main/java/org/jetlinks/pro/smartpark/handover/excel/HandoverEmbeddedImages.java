package org.jetlinks.pro.smartpark.handover.excel;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.*;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

/** 读取 OOXML 图片引用，不执行 Excel 公式或请求远程地址。 富数据按 vm→metadata→rich value→relationship 精确定位，禁止按图片出现顺序猜行。 */
final class HandoverEmbeddedImages {
    private static final Pattern DISPIMG =
            Pattern.compile("DISPIMG\\s*\\(\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final long MAX_EXPANDED_BYTES = 128L * 1024 * 1024;

    private HandoverEmbeddedImages() {}

    static Map<Integer, List<HandoverWorkbook.EmbeddedImage>> read(
            byte[] bytes,
            Workbook book,
            Sheet sheet,
            Map<Integer, HandoverExcelSchema.Column> columns)
            throws Exception {
        Map<Integer, List<HandoverWorkbook.EmbeddedImage>> result = new LinkedHashMap<>();
        if (!(book instanceof XSSFWorkbook)) return result;
        var drawing = ((XSSFSheet) sheet).getDrawingPatriarch();
        if (drawing != null) {
            for (var shape : drawing.getShapes()) {
                if (!(shape instanceof XSSFPicture picture)) continue;
                var anchor = picture.getClientAnchor();
                if (anchor == null || anchor.getRow1() < 1) continue;
                var col = columns.get((int) anchor.getCol1());
                if (col == null || !"photos".equals(col.key()))
                    throw HandoverWorkbook.invalid(anchor.getRow1() + 1, "图片", "图片必须放在图片列中");
                var data = picture.getPictureData();
                add(
                        result,
                        anchor.getRow1(),
                        new HandoverWorkbook.EmbeddedImage(
                                data.getData(), data.suggestFileExtension(), col.key()));
            }
        }
        Map<String, byte[]> entries = unzip(bytes);
        Map<String, String> cellRels = relationships(entries, "xl/_rels/cellimages.xml.rels", "xl");
        Map<String, String> cellImages = new HashMap<>();
        if (entries.containsKey("xl/cellimages.xml")) {
            var pics = xml(entries.get("xl/cellimages.xml")).getElementsByTagNameNS("*", "pic");
            for (int i = 0; i < pics.getLength(); i++) {
                Element pic = (Element) pics.item(i);
                String name = firstAttribute(pic, "cNvPr", "name");
                String embed = firstAttribute(pic, "blip", "embed");
                if (!name.isBlank() && cellRels.containsKey(embed))
                    cellImages.put(name, cellRels.get(embed));
            }
        }
        for (Row row : sheet)
            for (Cell cell : row) {
                if (cell.getCellType() != CellType.FORMULA) continue;
                var matcher = DISPIMG.matcher(cell.getCellFormula());
                if (!matcher.find()) continue;
                var column = columns.get(cell.getColumnIndex());
                if (column == null || !column.key().equals("photos"))
                    throw HandoverWorkbook.invalid(row.getRowNum() + 1, "图片", "单元格图片必须放在图片列中");
                addPart(result, row.getRowNum(), cellImages.get(matcher.group(1)), entries);
            }
        readRichData(entries, result, columns);
        return result;
    }

    private static void readRichData(
            Map<String, byte[]> entries,
            Map<Integer, List<HandoverWorkbook.EmbeddedImage>> result,
            Map<Integer, HandoverExcelSchema.Column> columns)
            throws Exception {
        byte[] sheet = entries.get("xl/worksheets/sheet1.xml");
        if (sheet == null) return;
        var cells = xml(sheet).getElementsByTagNameNS("*", "c");
        boolean hasRich = false;
        for (int i = 0; i < cells.getLength(); i++)
            if (((Element) cells.item(i)).hasAttribute("vm")) hasRich = true;
        if (!hasRich) return;
        var metadata = requiredXml(entries, "xl/metadata.xml");
        var richValues =
                requiredXml(entries, "xl/richData/rdrichvalue.xml")
                        .getElementsByTagNameNS("*", "rv");
        var structures =
                requiredXml(entries, "xl/richData/rdRichValueStructure.xml")
                        .getElementsByTagNameNS("*", "s");
        var relEntries =
                requiredXml(entries, "xl/richData/richValueRel.xml")
                        .getElementsByTagNameNS("*", "rel");
        var relationships =
                relationships(entries, "xl/richData/_rels/richValueRel.xml.rels", "xl/richData");
        Element valueMetadata =
                (Element) metadata.getElementsByTagNameNS("*", "valueMetadata").item(0);
        if (valueMetadata == null) throw HandoverWorkbook.invalid(1, "图片", "Excel图片元数据缺失");
        var valueRecords = valueMetadata.getElementsByTagNameNS("*", "bk");
        var futures = metadata.getElementsByTagNameNS("*", "futureMetadata");
        Element future = null;
        for (int i = 0; i < futures.getLength(); i++) {
            Element candidate = (Element) futures.item(i);
            if ("XLRICHVALUE".equals(candidate.getAttribute("name"))) future = candidate;
        }
        if (future == null) throw HandoverWorkbook.invalid(1, "图片", "Excel图片富数据缺失");
        var futureRecords = future.getElementsByTagNameNS("*", "bk");
        for (int i = 0; i < cells.getLength(); i++) {
            Element cell = (Element) cells.item(i);
            if (!cell.hasAttribute("vm")) continue;
            CellReference position = new CellReference(cell.getAttribute("r"));
            var column = columns.get((int) position.getCol());
            if (column == null || !column.key().equals("photos"))
                throw HandoverWorkbook.invalid(position.getRow() + 1, "图片", "Excel图片必须放在图片列中");
            try {
                int vm = Integer.parseInt(cell.getAttribute("vm")) - 1;
                Element rc =
                        (Element)
                                ((Element) valueRecords.item(vm))
                                        .getElementsByTagNameNS("*", "rc")
                                        .item(0);
                int futureIndex = Integer.parseInt(rc.getAttribute("v"));
                Element rvb =
                        (Element)
                                ((Element) futureRecords.item(futureIndex))
                                        .getElementsByTagNameNS("*", "rvb")
                                        .item(0);
                Element rich = (Element) richValues.item(Integer.parseInt(rvb.getAttribute("i")));
                Element structure =
                        (Element) structures.item(Integer.parseInt(rich.getAttribute("s")));
                var keys = structure.getElementsByTagNameNS("*", "k");
                int localImageKey = -1;
                for (int k = 0; k < keys.getLength(); k++)
                    if ("_rvRel:LocalImageIdentifier"
                            .equals(((Element) keys.item(k)).getAttribute("n"))) localImageKey = k;
                if (localImageKey < 0) throw new IllegalArgumentException("Not a local image");
                var values = rich.getElementsByTagNameNS("*", "v");
                Element rel =
                        (Element)
                                relEntries.item(
                                        Integer.parseInt(
                                                values.item(localImageKey).getTextContent()));
                addPart(
                        result,
                        position.getRow(),
                        relationships.get(attribute(rel, "id")),
                        entries);
            } catch (HandoverImportException error) {
                throw error;
            } catch (Exception error) {
                throw HandoverWorkbook.invalid(
                        position.getRow() + 1, "图片", "Excel单元格图片引用无效，请重新插入该图片");
            }
        }
    }

    private static org.w3c.dom.Document requiredXml(Map<String, byte[]> entries, String path)
            throws Exception {
        byte[] value = entries.get(path);
        if (value == null) throw HandoverWorkbook.invalid(1, "图片", "缺少Excel图片引用文件：" + path);
        return xml(value);
    }

    private static void addPart(
            Map<Integer, List<HandoverWorkbook.EmbeddedImage>> result,
            int row,
            String path,
            Map<String, byte[]> entries) {
        byte[] bytes = path == null ? null : entries.get(path);
        if (bytes == null || bytes.length == 0)
            throw HandoverWorkbook.invalid(row + 1, "图片", "图片引用无效或图片文件缺失");
        String extension =
                path.substring(path.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        if (!List.of("png", "jpg", "jpeg", "gif", "bmp").contains(extension))
            throw HandoverWorkbook.invalid(row + 1, "图片", "仅支持常用位图图片");
        add(result, row, new HandoverWorkbook.EmbeddedImage(bytes, extension, "photos"));
    }

    private static void add(
            Map<Integer, List<HandoverWorkbook.EmbeddedImage>> result,
            int row,
            HandoverWorkbook.EmbeddedImage image) {
        result.computeIfAbsent(row, ignored -> new ArrayList<>()).add(image);
    }

    private static Map<String, String> relationships(
            Map<String, byte[]> entries, String path, String base) throws Exception {
        var result = new HashMap<String, String>();
        if (!entries.containsKey(path)) return result;
        var nodes = xml(entries.get(path)).getElementsByTagNameNS("*", "Relationship");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element rel = (Element) nodes.item(i);
            if ("External".equals(rel.getAttribute("TargetMode"))) continue;
            String target = rel.getAttribute("Target");
            String normalized =
                    target.startsWith("/")
                            ? target.substring(1)
                            : Path.of(base)
                                    .resolve(target)
                                    .normalize()
                                    .toString()
                                    .replace('\\', '/');
            if (!normalized.startsWith("xl/")) throw HandoverWorkbook.invalid(1, "图片", "非法图片引用路径");
            result.put(rel.getAttribute("Id"), normalized);
        }
        return result;
    }

    private static Map<String, byte[]> unzip(byte[] bytes) throws Exception {
        var result = new HashMap<String, byte[]>();
        long total = 0;
        // 平台流式模板使用 ZIP64 数据描述符；JDK ZipInputStream 会误判 entry size 为0。
        try (var stream = new ZipArchiveInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = stream.getNextEntry(); entry != null; entry = stream.getNextEntry()) {
                String name = entry.getName();
                if (entry.isDirectory()) continue;
                if (!name.startsWith("xl/")) continue;
                var data = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = stream.read(buffer)) != -1; ) {
                    total += n;
                    if (total > MAX_EXPANDED_BYTES)
                        throw HandoverWorkbook.invalid(1, "文件", "解压内容超过128MB，请拆分文件");
                    data.write(buffer, 0, n);
                }
                result.put(name, data.toByteArray());
            }
        }
        return result;
    }

    private static org.w3c.dom.Document xml(byte[] bytes) throws Exception {
        var factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
    }

    private static String firstAttribute(Element parent, String tag, String name) {
        NodeList nodes = parent.getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 ? "" : attribute((Element) nodes.item(0), name);
    }

    private static String attribute(Element element, String local) {
        if (element == null) return "";
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            var attr = element.getAttributes().item(i);
            if (local.equals(attr.getLocalName()) || local.equals(attr.getNodeName()))
                return attr.getNodeValue();
        }
        return "";
    }
}
