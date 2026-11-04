package org.jetlinks.pro.patrol.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.Answer;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.CheckResult;
import org.jetlinks.pro.patrol.entity.PatrolExecutionEntity.ItemResult;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity.CheckItem;
import org.jetlinks.pro.patrol.entity.PatrolStandardEntity.ItemType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 标准的结构与答案语义唯一校验处；只做纯计算，附件权限由附件服务负责。 */
public final class PatrolInspection {
    private PatrolInspection() {}

    public static void validateStandard(List<CheckItem> items) {
        var ids = new HashSet<String>();
        for (CheckItem item : items) {
            if (!ids.add(item.id()) || new HashSet<>(item.options()).size() != item.options().size()
                    || new HashSet<>(item.qualifiedOptions()).size() != item.qualifiedOptions().size())
                throw new BusinessException("error.patrol.standard.duplicate_item");
            if (item.type() == ItemType.choice) {
                if (item.options().isEmpty() || !item.options().containsAll(item.qualifiedOptions()))
                    throw new BusinessException("error.patrol.standard.options");
            } else if (!item.options().isEmpty() || !item.qualifiedOptions().isEmpty()) {
                throw new BusinessException("error.patrol.standard.options");
            }
            if (item.type() != ItemType.number && (item.lowerLimit() != null || item.upperLimit() != null)
                    || item.lowerLimit() != null && item.upperLimit() != null
                    && item.lowerLimit().compareTo(item.upperLimit()) > 0)
                throw new BusinessException("error.patrol.standard.range");
        }
    }

    public record Evaluation(List<ItemResult> items, CheckResult result) {}

    public static Evaluation evaluate(List<CheckItem> standard, List<Answer> submitted) {
        Map<String, Answer> answers = new HashMap<>();
        var expected = new HashSet<String>();
        standard.forEach(item -> expected.add(item.id()));
        for (Answer answer : submitted) {
            if (!expected.contains(answer.itemId()) || answers.put(answer.itemId(), answer) != null)
                throw invalidAnswer();
        }
        List<ItemResult> results = new ArrayList<>();
        for (CheckItem item : standard)
            results.add(new ItemResult(item.id(), evaluateItem(item, answers.get(item.id()))));
        CheckResult overall = results.stream().anyMatch(item -> item.result() == CheckResult.fail)
                ? CheckResult.fail
                : results.stream().allMatch(item -> item.result() == CheckResult.pass)
                        ? CheckResult.pass : CheckResult.not_evaluated;
        return new Evaluation(List.copyOf(results), overall);
    }

    private static CheckResult evaluateItem(CheckItem item, Answer answer) {
        boolean hasValue = answer != null && answer.value() != null && !answer.value().isBlank();
        boolean hasImage = answer != null && answer.attachmentId() != null && !answer.attachmentId().isBlank();
        boolean hasContent = item.type() == ItemType.image ? hasImage : hasValue;
        if (item.required() && !hasContent || item.photoRequired() && !hasImage
                || item.type() == ItemType.image && hasValue)
            throw invalidAnswer();
        if (!hasContent) {
            if (hasImage && item.type() != ItemType.image) throw invalidAnswer();
            return CheckResult.not_evaluated;
        }
        return switch (item.type()) {
            case choice -> {
                if (!item.options().contains(answer.value())) throw invalidAnswer();
                yield item.qualifiedOptions().isEmpty() ? CheckResult.not_evaluated
                        : item.qualifiedOptions().contains(answer.value()) ? CheckResult.pass : CheckResult.fail;
            }
            case number -> evaluateNumber(item, answer.value());
            // 参考文本和例图用于人工检查，不用字符串相等或“上传成功”替代检查合格。
            case text, image -> CheckResult.not_evaluated;
        };
    }

    private static CheckResult evaluateNumber(CheckItem item, String value) {
        if (value.length() > 64 || !value.matches("[+-]?\\d+(\\.\\d+)?")) throw invalidAnswer();
        BigDecimal number = new BigDecimal(value);
        if (item.lowerLimit() == null && item.upperLimit() == null) return CheckResult.not_evaluated;
        return item.lowerLimit() != null && number.compareTo(item.lowerLimit()) < 0
                || item.upperLimit() != null && number.compareTo(item.upperLimit()) > 0
                ? CheckResult.fail : CheckResult.pass;
    }

    private static BusinessException invalidAnswer() {
        return new BusinessException("error.patrol.execution.answers");
    }
}
