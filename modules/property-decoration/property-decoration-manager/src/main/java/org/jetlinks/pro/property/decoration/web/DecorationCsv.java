package org.jetlinks.pro.property.decoration.web;

import java.util.List;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;

/** UTF-8 BOM与公式防护支持表格工具直接打开；导出复用业务查询授权及条数上限。 */
final class DecorationCsv {
    private DecorationCsv() {}
    static String write(List<DecorationApplicationEntity> rows) {
        var csv = new StringBuilder("\uFEFF装修编号,项目,房屋,业主,联系电话,施工单位,施工负责人,开始时间,竣工时间,状态,登记人\r\n");
        for (var row : rows) {
            Object[] values = {row.getNumber(), row.getProjectName(), row.getSpaceName(), row.getOwnerName(), row.getContactPhone(),
                row.getConstructionUnit(), row.getConstructionManager(), row.getStartTime(), row.getCompletionTime(), row.getStatus(), row.getCreatorName()};
            for (int i = 0; i < values.length; i++) { if (i > 0) csv.append(','); csv.append(cell(values[i])); }
            csv.append("\r\n");
        }
        return csv.toString();
    }
    static String cell(Object value) {
        String text = value == null ? "" : value.toString();
        if (!text.isEmpty() && "=+-@\t\r\n".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }
}
