package org.jetlinks.pro.property.finance.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ReceivableCalculationContext {
    private final BillingPeriod period;
    private final LocalDate coveredStart;
    private final LocalDate coveredEnd;
    private final String formulaExpression;
    private final Map<String, BigDecimal> variableValues;
    private final Map<String, String> variableNames;
    private final int scale;
    private final RoundingMode roundingMode;

    public ReceivableCalculationContext(BillingPeriod period, LocalDate coveredStart,
                                        LocalDate coveredEnd, String formulaExpression,
                                        Map<String, BigDecimal> variableValues,
                                        Map<String, String> variableNames, int scale,
                                        RoundingMode roundingMode) {
        this.period = period;
        this.coveredStart = coveredStart;
        this.coveredEnd = coveredEnd;
        this.formulaExpression = formulaExpression;
        this.variableValues = Collections.unmodifiableMap(new LinkedHashMap<>(
                variableValues == null ? Collections.emptyMap() : variableValues));
        this.variableNames = Collections.unmodifiableMap(new LinkedHashMap<>(
                variableNames == null ? Collections.emptyMap() : variableNames));
        this.scale = scale;
        this.roundingMode = roundingMode;
    }

    public BillingPeriod getPeriod() {
        return period;
    }

    public LocalDate getCoveredStart() {
        return coveredStart;
    }

    public LocalDate getCoveredEnd() {
        return coveredEnd;
    }

    public String getFormulaExpression() {
        return formulaExpression;
    }

    public Map<String, BigDecimal> getVariableValues() {
        return variableValues;
    }

    public Map<String, String> getVariableNames() {
        return variableNames;
    }

    public int getScale() {
        return scale;
    }

    public RoundingMode getRoundingMode() {
        return roundingMode;
    }

}
