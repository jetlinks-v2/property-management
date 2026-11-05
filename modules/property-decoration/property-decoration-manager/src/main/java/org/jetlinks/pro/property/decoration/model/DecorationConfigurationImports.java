package org.jetlinks.pro.property.decoration.model;

import org.jetlinks.pro.property.decoration.excel.DecorationCheckWorkbook;
import java.util.List;

/** 导入结果只记录保存的配置版本与私有附件回执；不保存原始工作簿或公共文件地址。 */
public final class DecorationConfigurationImports {
    private DecorationConfigurationImports() {}
    public record SavedConfiguration(String id, String projectId, long version) {}
    public record Result(int totalCount, int validCount, int importedCount, List<DecorationCheckWorkbook.Problem> errors,
                         List<SavedConfiguration> configurations, List<String> attachmentIds) {}
}
