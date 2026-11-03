package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.PagerResult;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.springframework.beans.BeanWrapperImpl;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import static org.jetlinks.pro.property.base.service.ArchiveScope.error;

/** 有界镜像派生列表的查询；先取得完整资产范围，再筛选、统计和分页。 */
public final class ArchiveQuery {
    private ArchiveQuery() {}

    public static <T> PagerResult<T> page(
            List<T> values, QueryParamEntity input, Set<String> fields) {
        var query = input.clone();
        query.setPageIndex(Math.max(0, input.getPageIndex()));
        query.setPageSize(Math.min(200, Math.max(1, input.getPageSize())));
        var all = matching(values, query, fields);
        long start = (long) query.getPageIndex() * query.getPageSize();
        var page =
                start >= all.size()
                        ? List.<T>of()
                        : all.subList(
                                (int) start,
                                (int) Math.min(all.size(), start + query.getPageSize()));
        return PagerResult.of(all.size(), page, query);
    }

    public static <T> List<T> filter(List<T> values, QueryParamEntity query, Set<String> fields) {
        var all = matching(values, query, fields);
        if (all.size() > 10000) throw error("export_limit", 400);
        return all;
    }

    private static <T> List<T> matching(
            List<T> values, QueryParamEntity query, Set<String> fields) {
        if (values.size() > 20000) throw error("too_many", 400);
        Predicate<T> predicate = terms(query.getTerms(), fields, 0);
        var result = new ArrayList<T>();
        for (T value : values) if (predicate.test(value)) result.add(value);
        Comparator<T> comparator = null;
        if (query.getSorts() != null)
            for (var sort : query.getSorts()) {
                checkField(sort.getName(), fields);
                if (!"asc".equalsIgnoreCase(sort.getOrder())
                        && !"desc".equalsIgnoreCase(sort.getOrder()))
                    throw error("invalid_query", 400);
                Comparator<T> next =
                        (left, right) ->
                                compare(read(left, sort.getName()), read(right, sort.getName()));
                if ("desc".equalsIgnoreCase(sort.getOrder())) next = next.reversed();
                comparator = comparator == null ? next : comparator.thenComparing(next);
            }
        if (comparator != null) result.sort(comparator);
        return result;
    }

    private static <T> Predicate<T> terms(List<Term> terms, Set<String> fields, int depth) {
        if (depth > 8 || (terms != null && terms.size() > 100)) throw error("invalid_query", 400);
        Predicate<T> result = null;
        Predicate<T> group = null;
        if (terms != null)
            for (var term : terms) {
                Predicate<T> item = single(term, fields, depth);
                if (group == null) group = item;
                else if (term.getType() == Term.Type.or) {
                    result = result == null ? group : result.or(group);
                    group = item;
                } else group = group.and(item);
            }
        return group == null ? ignored -> true : result == null ? group : result.or(group);
    }

    private static <T> Predicate<T> single(Term term, Set<String> fields, int depth) {
        if (term.getColumn() == null || term.getColumn().isBlank())
            return terms(term.getTerms(), fields, depth + 1);
        checkField(term.getColumn(), fields);
        String operation = term.getTermType() == null ? "eq" : term.getTermType();
        Set<String> supported =
                Set.of(
                        "eq",
                        "not",
                        "neq",
                        "like",
                        "nlike",
                        "in",
                        "nin",
                        "gt",
                        "gte",
                        "lt",
                        "lte",
                        "isnull",
                        "notnull",
                        "isnotnull");
        if (!supported.contains(operation)) throw error("invalid_query", 400);
        Object expected = term.getValue();
        Pattern pattern =
                (operation.equals("like") || operation.equals("nlike"))
                        ? like(String.valueOf(expected))
                        : null;
        Predicate<T> own =
                row -> {
                    Object actual = read(row, term.getColumn());
                    return switch (operation) {
                        case "eq" -> equal(actual, expected);
                        case "not", "neq" -> actual != null && !equal(actual, expected);
                        case "isnull" -> actual == null;
                        case "notnull", "isnotnull" -> actual != null;
                        case "like" ->
                                actual != null && pattern.matcher(String.valueOf(actual)).matches();
                        case "nlike" ->
                                actual != null
                                        && !pattern.matcher(String.valueOf(actual)).matches();
                        case "in", "nin" -> {
                            if (!(expected instanceof Collection<?> collection))
                                throw error("invalid_query", 400);
                            boolean included =
                                    collection.stream().anyMatch(value -> equal(actual, value));
                            yield actual != null && (operation.equals("in") == included);
                        }
                        case "gt" ->
                                actual != null && expected != null && compare(actual, expected) > 0;
                        case "gte" ->
                                actual != null
                                        && expected != null
                                        && compare(actual, expected) >= 0;
                        case "lt" ->
                                actual != null && expected != null && compare(actual, expected) < 0;
                        case "lte" ->
                                actual != null
                                        && expected != null
                                        && compare(actual, expected) <= 0;
                        default -> throw error("invalid_query", 400);
                    };
                };
        return term.getTerms() == null || term.getTerms().isEmpty()
                ? own
                : own.and(terms(term.getTerms(), fields, depth + 1));
    }

    private static void checkField(String name, Set<String> fields) {
        if (!fields.contains(name)) throw error("invalid_query", 400);
    }

    private static Object read(Object bean, String name) {
        return new BeanWrapperImpl(bean).getPropertyValue(name);
    }

    private static boolean equal(Object left, Object right) {
        // 一份客户主档可以关联多套有权房屋；按客户性质筛选须匹配当前范围内任意有效关系。
        if (left instanceof Collection<?> values)
            return values.stream().anyMatch(value -> equal(value, right));
        return left == null || right == null ? left == right : compare(left, right) == 0;
    }

    private static int compare(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : left == null ? 1 : -1;
        if (left instanceof Number && right != null) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()));
            } catch (NumberFormatException e) {
                throw error("invalid_query", 400);
            }
        }
        if (left instanceof Enum<?> value) left = value.name();
        return String.valueOf(left).compareTo(String.valueOf(right));
    }

    private static Pattern like(String value) {
        if (value.length() > 500) throw error("invalid_query", 400);
        if (!value.contains("%") && !value.contains("_")) value = "%" + value + "%";
        StringBuilder regex = new StringBuilder();
        for (char ch : value.toCharArray())
            regex.append(ch == '%' ? ".*" : ch == '_' ? "." : Pattern.quote(String.valueOf(ch)));
        return Pattern.compile(
                regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
    }
}
