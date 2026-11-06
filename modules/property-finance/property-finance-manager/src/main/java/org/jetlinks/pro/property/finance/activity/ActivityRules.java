package org.jetlinks.pro.property.finance.activity;

import static org.jetlinks.pro.property.finance.service.FinanceScope.error;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashSet;

/** 旧充赠档位口径保留；取消审批不等于取消日期、范围和季度周期校验。 */
public final class ActivityRules {
    private ActivityRules() {}

    public static void validate(ActivityInput i, String settlementMethod) {
        if (i == null
                || !text(i.getName(), 100)
                || !text(i.getActivityType(), 64)
                || !text(i.getSubjectId(), 64)
                || !text(i.getStandardId(), 64)
                || i.getOffsetArrears() == null
                || (i.getRemark() != null && i.getRemark().length() > 1000))
            throw error("activity_invalid", 400);
        i.setName(i.getName().trim());
        if (date(i.getStartDate()).isAfter(date(i.getEndDate())))
            throw error("activity_dates", 400);
        if (i.getOffsetArrears()) {
            if (date(i.getOffsetStartDate()).isAfter(date(i.getOffsetEndDate())))
                throw error("activity_dates", 400);
        } else {
            i.setOffsetStartDate(null);
            i.setOffsetEndDate(null);
        }
        if (i.getTargetIds() == null
                || i.getTargetIds().isEmpty()
                || i.getTargetIds().size() > 1000
                || i.getTargetIds().stream().anyMatch(id -> !text(id, 64))
                || new HashSet<>(i.getTargetIds()).size() != i.getTargetIds().size())
            throw error("activity_targets", 400);
        if (i.getTiers() == null || i.getTiers().isEmpty() || i.getTiers().size() > 100)
            throw error("activity_tiers", 400);
        var seen = new HashSet<ActivityInput.Tier>();
        boolean quarter = settlementMethod != null && settlementMethod.startsWith("QUARTER");
        for (var tier : i.getTiers()) {
            if (tier == null
                    || tier.paidMonths() == null
                    || tier.giftMonths() == null
                    || tier.paidMonths() <= 0
                    || tier.giftMonths() <= 0
                    || tier.paidMonths() > 1200
                    || tier.giftMonths() > 1200
                    || !seen.add(tier)) throw error("activity_tiers", 400);
            if (quarter && (tier.paidMonths() % 3 != 0 || tier.giftMonths() % 3 != 0))
                throw error("activity_quarter", 400);
        }
        if (i.getAttachments() == null || i.getAttachments().size() > 20)
            throw error("activity_attachments", 400);
        var files = new HashSet<String>();
        for (var file : i.getAttachments()) {
            if (file == null
                    || !text(file.fileId(), 128)
                    || !text(file.fileName(), 255)
                    || !files.add(file.fileId())) throw error("activity_attachments", 400);
        }
    }

    public static LocalDate date(String value) {
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}"))
                throw error("activity_dates", 400);
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw error("activity_dates", 400);
        }
    }

    public static String status(String start, String end, LocalDate today) {
        return today.isBefore(date(start))
                ? "NOT_STARTED"
                : today.isAfter(date(end)) ? "ENDED" : "RUNNING";
    }

    public static void changeEnd(
            String start, String end, String next, boolean extend, LocalDate today) {
        var date = date(next);
        if (extend
                ? !date.isAfter(date(end))
                : date.isBefore(date(start)) || !date.isBefore(date(end)) || date.isBefore(today))
            throw error("activity_dates", 400);
    }

    private static boolean text(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.length() <= max
                && value.equals(value.trim());
    }
}
