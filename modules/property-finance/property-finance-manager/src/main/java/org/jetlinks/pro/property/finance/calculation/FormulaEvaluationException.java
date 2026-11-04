package org.jetlinks.pro.property.finance.calculation;

public class FormulaEvaluationException extends IllegalArgumentException {
    public FormulaEvaluationException(String message) {
        super(message);
    }

    public FormulaEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
