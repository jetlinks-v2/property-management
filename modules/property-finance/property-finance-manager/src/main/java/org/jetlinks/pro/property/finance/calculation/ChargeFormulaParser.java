package org.jetlinks.pro.property.finance.calculation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;

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

        /** Explains only the tiered base; later discounts/additions remain visible in the frozen full formula. */
        public List<TierCharge> tierCharges(Map<String, BigDecimal> values) {
            var tiers = new ArrayList<TierCharge>();
            collectTiers(root, values, tiers);
            return List.copyOf(tiers);
        }

        private static void collectTiers(Node node, Map<String, BigDecimal> values, List<TierCharge> result) {
            if (node instanceof TieredNode tier) {
                BigDecimal quantity = values.get(tier.usage.variable), price = values.get(tier.price.variable), lower = BigDecimal.ZERO;
                if (quantity == null || price == null) return;
                for (int i = 0; i < tier.bands.size(); i += 2) {
                    BigDecimal upper = tier.bands.get(i), covered = quantity.min(upper).subtract(lower).max(BigDecimal.ZERO);
                    result.add(new TierCharge(lower, upper, price, covered, covered.multiply(price)));
                    lower = upper; price = tier.bands.get(i + 1);
                }
                BigDecimal covered = quantity.subtract(lower).max(BigDecimal.ZERO);
                result.add(new TierCharge(lower, null, price, covered, covered.multiply(price)));
            } else if (node instanceof BinaryNode binary) {
                collectTiers(binary.left, values, result); collectTiers(binary.right, values, result);
            } else if (node instanceof UnaryNode unary) collectTiers(unary.value, values, result);
        }

        /** 水电基础乘积必须保留一次，附加运算仅作用于该基础金额，不能再次引用用量或价格。 */
        public boolean preservesMeterBase(String usage) {
            return baseOnLeft(root, usage) && occurrences(root, usage) == 1
                    && occurrences(root, "STANDARD_PRICE") == 1;
        }

        private static boolean baseOnLeft(Node node, String usage) {
            if (node instanceof TieredNode tiered) return tiered.usage.variable.equals(usage);
            if (!(node instanceof BinaryNode binary)) return false;
            if (binary.operator == '*' && binary.left instanceof VariableNode price
                    && price.variable.equals("STANDARD_PRICE") && binary.right instanceof VariableNode quantity
                    && quantity.variable.equals(usage)) return true;
            return baseOnLeft(binary.left, usage);
        }

        private static int occurrences(Node node, String code) {
            if (node instanceof VariableNode variable) return variable.variable.equals(code) ? 1 : 0;
            if (node instanceof TieredNode tiered)
                return occurrences(tiered.usage, code) + occurrences(tiered.price, code);
            if (node instanceof BinaryNode binary)
                return occurrences(binary.left, code) + occurrences(binary.right, code);
            if (node instanceof UnaryNode unary) return occurrences(unary.value, code);
            return 0;
        }

        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context) {
            return root.evaluate(values, context, displayNames);
        }
    }

    public record TierCharge(BigDecimal from, BigDecimal to, BigDecimal price, BigDecimal quantity, BigDecimal amount) {}

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

    /** 有限阶梯只接收一个水/电/气用量与首档标准价，后续档位是冻结常量，不执行脚本。 */
    private static final class TieredNode extends Node {
        private final VariableNode usage;
        private final VariableNode price;
        private final List<BigDecimal> bands;

        private TieredNode(VariableNode usage, VariableNode price, List<BigDecimal> bands, int position) {
            super(position);
            this.usage = usage;
            this.price = price;
            this.bands = List.copyOf(bands);
            if (!Set.of("WATER_USAGE", "ELECTRICITY_USAGE", "GAS_USAGE").contains(usage.variable)
                    || !"STANDARD_PRICE".equals(price.variable) || bands.size() % 2 != 0 || bands.size() > 18) {
                throw new IllegalArgumentException("阶梯用量、单价或档位数量无效");
            }
            BigDecimal previous = BigDecimal.ZERO;
            for (int i = 0; i < bands.size(); i += 2) {
                var upper = bands.get(i);
                var nextPrice = bands.get(i + 1);
                if (upper.compareTo(previous) <= 0 || !upper.toPlainString().matches("[0-9]{1,18}(\\.[0-9]{1,6})?")
                        || !nextPrice.toPlainString().matches("[0-9]{1,18}(\\.[0-9]{1,2})?")) {
                    throw new IllegalArgumentException("阶梯上限须递增，单价须为非负金额");
                }
                previous = upper;
            }
        }

        @Override
        BigDecimal evaluate(Map<String, BigDecimal> values, MathContext context, Map<String, String> names) {
            BigDecimal quantity = usage.evaluate(values, context, names);
            BigDecimal unitPrice = price.evaluate(values, context, names);
            if (quantity.signum() < 0 || unitPrice.signum() < 0) {
                throw new FormulaEvaluationException("阶梯用量和单价不能为负数");
            }
            BigDecimal lower = BigDecimal.ZERO;
            BigDecimal total = BigDecimal.ZERO;
            // 每段精确累加后统一使用标准的金额精度，禁止逐档先舍入。
            for (int i = 0; i < bands.size(); i += 2) {
                BigDecimal upper = bands.get(i);
                BigDecimal covered = quantity.min(upper).subtract(lower).max(BigDecimal.ZERO);
                total = total.add(covered.multiply(unitPrice));
                lower = upper;
                unitPrice = bands.get(i + 1);
            }
            return total.add(quantity.subtract(lower).max(BigDecimal.ZERO).multiply(unitPrice));
        }

        @Override
        BigDecimal constantValue() { return null; }
    }

    private static final class SourceParser {
        private final String source;
        private final Map<String, String> allowedVariables;
        private final Set<String> usedVariables = new LinkedHashSet<>();
        private int index;
        private int tieredCount;

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
            if (source.startsWith("TIERED(", index)) {
                return tiered(position);
            }
            return number(position);
        }

        private Node tiered(int position) {
            if (++tieredCount > 1) error("公式只能包含一个阶梯基础金额");
            index += "TIERED(".length();
            whitespace();
            if (!source.startsWith("${", index)) error("阶梯缺少用量变量");
            var first = (VariableNode) variable(index + 1);
            VariableNode usage;
            VariableNode price;
            whitespace();
            if ("STANDARD_PRICE".equals(first.variable)) {
                price = first;
                if (!peek('*')) error("阶梯必须保留单价与用量乘积");
                index++;
                whitespace();
                if (!source.startsWith("${", index)) error("阶梯缺少用量变量");
                usage = (VariableNode) variable(index + 1);
            } else {
                // 已保存的旧阶梯表达式继续按原规则解释，不改写历史账单快照。
                usage = first;
                comma();
                if (!source.startsWith("${", index)) error("阶梯缺少标准单价");
                price = (VariableNode) variable(index + 1);
            }
            var bands = new ArrayList<BigDecimal>();
            whitespace();
            while (peek(',')) {
                comma();
                bands.add(number(index + 1).constantValue());
                if (bands.size() > 18) error("阶梯最多十档");
                whitespace();
            }
            if (!peek(')')) error("阶梯括号不匹配");
            index++;
            return new TieredNode(usage, price, bands, position);
        }

        private void comma() {
            whitespace();
            if (!peek(',')) error("阶梯参数缺少分隔符");
            index++;
            whitespace();
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
