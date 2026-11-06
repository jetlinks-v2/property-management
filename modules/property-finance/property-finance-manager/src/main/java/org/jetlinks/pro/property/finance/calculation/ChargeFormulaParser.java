package org.jetlinks.pro.property.finance.calculation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Component
public class ChargeFormulaParser {

    public ParsedFormula parse(String expression, Set<String> allowedVariables) {
        Map<String, String> displayNames = new LinkedHashMap<>();
        if (allowedVariables != null) {
            allowedVariables.forEach(variable -> displayNames.put(variable, variable));
        }
        return parse(expression, displayNames);
    }

    public ParsedFormula parse(String expression, Map<String, String> allowedVariables) {
        Map<String, String> displayNames = allowedVariables == null
                ? Collections.emptyMap()
                : new LinkedHashMap<>(allowedVariables);
        return new SourceParser(expression, displayNames).parse();
    }

    public static final class ParsedFormula {
        private final String expression;
        private final Node root;
        private final Set<String> variables;
        private final Map<String, String> displayNames;

        private ParsedFormula(String expression, Node root, Set<String> variables,
                              Map<String, String> displayNames) {
            this.expression = expression;
            this.root = root;
            this.variables = Collections.unmodifiableSet(new LinkedHashSet<>(variables));
            this.displayNames = Collections.unmodifiableMap(new LinkedHashMap<>(displayNames));
        }

        public String getExpression() {
            return expression;
        }

        public Set<String> getVariables() {
            return variables;
        }

        public Set<String> variables() {
            return variables;
        }

        public boolean containsVariable(String variable) {
            return variables.contains(variable);
        }

        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context) {
            return root.evaluate(values, context, displayNames);
        }
    }

    private abstract static class Node {
        private final int position;

        private Node(int position) {
            this.position = position;
        }

        abstract BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context,
                                     Map<String, String> displayNames);

        abstract BigDecimal constantValue();

        int position() {
            return position;
        }
    }

    private static final class NumberNode extends Node {
        private final BigDecimal value;

        private NumberNode(BigDecimal value, int position) {
            super(position);
            this.value = value;
        }

        @Override
        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context,
                            Map<String, String> displayNames) {
            return value;
        }

        @Override
        BigDecimal constantValue() {
            return value;
        }
    }

    private static final class VariableNode extends Node {
        private final String variable;

        private VariableNode(String variable, int position) {
            super(position);
            this.variable = variable;
        }

        @Override
        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context,
                            Map<String, String> displayNames) {
            BigDecimal value = values.get(variable);
            if (value == null) {
                String name = displayNames.getOrDefault(variable, variable);
                throw new FormulaEvaluationException(
                        "缺少公式变量：" + name + "，位置：" + position());
            }
            return value;
        }

        @Override
        BigDecimal constantValue() {
            return null;
        }
    }

    private static final class UnaryNode extends Node {
        private final char operator;
        private final Node value;

        private UnaryNode(char operator, Node value, int position) {
            super(position);
            this.operator = operator;
            this.value = value;
        }

        @Override
        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context,
                            Map<String, String> displayNames) {
            BigDecimal result = value.evaluate(values, context, displayNames);
            return operator == '-' ? result.negate(context) : result;
        }

        @Override
        BigDecimal constantValue() {
            BigDecimal constant = value.constantValue();
            if (constant == null) {
                return null;
            }
            return operator == '-' ? constant.negate(MathContext.DECIMAL128) : constant;
        }
    }

    private static final class BinaryNode extends Node {
        private final char operator;
        private final Node left;
        private final Node right;

        private BinaryNode(char operator, Node left, Node right, int position) {
            super(position);
            this.operator = operator;
            this.left = left;
            this.right = right;
        }

        @Override
        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context,
                            Map<String, String> displayNames) {
            BigDecimal leftValue = left.evaluate(values, context, displayNames);
            BigDecimal rightValue = right.evaluate(values, context, displayNames);
            switch (operator) {
                case '+':
                    return leftValue.add(rightValue, context);
                case '-':
                    return leftValue.subtract(rightValue, context);
                case '*':
                    return leftValue.multiply(rightValue, context);
                case '/':
                    if (rightValue.compareTo(BigDecimal.ZERO) == 0) {
                        throw new FormulaEvaluationException("除数不能为0，位置：" + position());
                    }
                    return leftValue.divide(rightValue, context);
                default:
                    throw new FormulaEvaluationException("不支持的运算符：" + operator);
            }
        }

        @Override
        BigDecimal constantValue() {
            BigDecimal leftValue = left.constantValue();
            BigDecimal rightValue = right.constantValue();
            if (leftValue == null || rightValue == null) {
                return null;
            }
            switch (operator) {
                case '+':
                    return leftValue.add(rightValue, MathContext.DECIMAL128);
                case '-':
                    return leftValue.subtract(rightValue, MathContext.DECIMAL128);
                case '*':
                    return leftValue.multiply(rightValue, MathContext.DECIMAL128);
                case '/':
                    return rightValue.compareTo(BigDecimal.ZERO) == 0
                            ? null
                            : leftValue.divide(rightValue, MathContext.DECIMAL128);
                default:
                    return null;
            }
        }
    }

    private static final class SourceParser {
        private final String source;
        private final Map<String, String> allowedVariables;
        private final Set<String> usedVariables = new LinkedHashSet<>();
        private int index;

        private SourceParser(String source, Map<String, String> allowedVariables) {
            this.source = source == null ? "" : source;
            this.allowedVariables = allowedVariables;
        }

        private ParsedFormula parse() {
            if (source.trim().isEmpty()) {
                throw new IllegalArgumentException("计费公式不能为空");
            }
            Node root = expression();
            whitespace();
            if (index != source.length()) {
                error("包含不支持的字符");
            }
            return new ParsedFormula(source, root, usedVariables, allowedVariables);
        }

        private Node expression() {
            Node result = term();
            while (true) {
                whitespace();
                if (peek('+') || peek('-')) {
                    int position = index + 1;
                    char operator = source.charAt(index++);
                    result = new BinaryNode(operator, result, term(), position);
                } else {
                    return result;
                }
            }
        }

        private Node term() {
            Node result = factor();
            while (true) {
                whitespace();
                if (peek('*') || peek('/')) {
                    int position = index + 1;
                    char operator = source.charAt(index++);
                    Node right = factor();
                    if (operator == '/') {
                        BigDecimal constant = right.constantValue();
                        if (constant != null && constant.compareTo(BigDecimal.ZERO) == 0) {
                            throw new IllegalArgumentException(
                                    "除数不能为常量0，位置：" + position);
                        }
                    }
                    result = new BinaryNode(operator, result, right, position);
                } else {
                    return result;
                }
            }
        }

        private Node factor() {
            whitespace();
            int position = index + 1;
            if (peek('+') || peek('-')) {
                char operator = source.charAt(index++);
                return new UnaryNode(operator, factor(), position);
            }
            if (peek('(')) {
                index++;
                Node value = expression();
                whitespace();
                if (!peek(')')) {
                    error("括号不匹配");
                }
                index++;
                return value;
            }
            if (source.startsWith("${", index)) {
                return variable(position);
            }
            return number(position);
        }

        private Node variable(int position) {
            int end = source.indexOf('}', index + 2);
            if (end < 0) {
                error("变量缺少结束符}");
            }
            String variable = source.substring(index + 2, end).trim();
            if (!allowedVariables.containsKey(variable)) {
                throw new IllegalArgumentException(
                        "公式变量不存在或已停用：" + variable + "，位置：" + position);
            }
            usedVariables.add(variable);
            index = end + 1;
            return new VariableNode(variable, position);
        }

        private Node number(int position) {
            int start = index;
            boolean decimalPoint = false;
            while (index < source.length()) {
                char current = source.charAt(index);
                if (Character.isDigit(current)) {
                    index++;
                } else if (current == '.' && !decimalPoint) {
                    decimalPoint = true;
                    index++;
                } else {
                    break;
                }
            }
            String number = source.substring(start, index);
            if (number.isEmpty() || ".".equals(number)) {
                error("缺少数字、变量或括号");
            }
            try {
                return new NumberNode(new BigDecimal(number), position);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("数字格式不正确，位置：" + position, ex);
            }
        }

        private boolean peek(char expected) {
            return index < source.length() && source.charAt(index) == expected;
        }

        private void whitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private void error(String message) {
            throw new IllegalArgumentException(message + "，位置：" + (index + 1));
        }
    }
}
