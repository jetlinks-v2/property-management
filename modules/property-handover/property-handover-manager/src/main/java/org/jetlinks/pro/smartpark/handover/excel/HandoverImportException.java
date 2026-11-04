package org.jetlinks.pro.smartpark.handover.excel;

import org.hswebframework.web.exception.BusinessException;

import java.util.List;

/** 行号为 Excel 可见行号；校验失败时只返回错误，不写任何业务记录。 */
public final class HandoverImportException extends BusinessException {
    public record RowError(int row, String column, String message) {}

    private final List<RowError> errors;

    public HandoverImportException(List<RowError> errors) {
        super("error.handover.excel_validation_failed", 400);
        this.errors = List.copyOf(errors);
    }

    public List<RowError> getErrors() {
        return errors;
    }
}
