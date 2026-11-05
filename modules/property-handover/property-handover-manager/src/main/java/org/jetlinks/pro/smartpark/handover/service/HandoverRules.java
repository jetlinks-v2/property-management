package org.jetlinks.pro.smartpark.handover.service;

import org.hswebframework.web.exception.BusinessException;
import org.jetlinks.pro.smartpark.handover.api.ActionRequest;

import java.util.List;
import java.util.Set;

/** Pure business guards, shared by single and all-or-nothing batch actions. */
public final class HandoverRules {
    public static final List<String> STAGES =
            List.of(
                    "kickoff",
                    "material",
                    "disclosure",
                    "site",
                    "physical",
                    "rectification",
                    "quality",
                    "summary");
    public static final Set<String> SOURCES =
            Set.of("SITE_INSPECTION", "PHYSICAL_HANDOVER", "QUALITY_ACCEPTANCE");

    private HandoverRules() {}

    public static void require(boolean condition, String code) {
        if (!condition)
            throw new BusinessException(
                    "error.handover." + code,
                    java.util.Set.of("state_invalid", "item_confirmed").contains(code) ? 409 : 400);
    }

    public static void conflict(boolean condition) {
        if (!condition) throw new BusinessException("error.handover.version_conflict", 409);
    }

    public static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    public static int[] next(int status, int audit, String action, ActionRequest input) {
        switch (action) {
            case "accept":
                require(status == 0 || status == 4, "state_invalid");
                return new int[] {1, 0};
            case "rectify":
                require(status == 1, "state_invalid");
                require(hasText(input.getDescription()), "description_required");
                return new int[] {2, 0};
            case "initial-review", "final-review":
                require(
                        status == 2 && audit == ("initial-review".equals(action) ? 0 : 1),
                        "state_invalid");
                require(input.getPassed() != null, "review_result_required");
                if (!input.getPassed()) {
                    require(hasText(input.getReason()), "reason_required");
                    return new int[] {4, 0};
                }
                return "initial-review".equals(action) ? new int[] {2, 1} : new int[] {3, 2};
            case "assign":
                require(status != 3, "state_invalid");
                require(hasText(input.getOrganizationId()), "organization_required");
                return new int[] {status, audit};
            case "photos":
                require(status != 3, "state_invalid");
                require(
                        input.getPhotos() != null && !input.getPhotos().isEmpty(),
                        "photos_required");
                return new int[] {status, audit};
            default:
                throw new BusinessException("error.handover.action_invalid", 400);
        }
    }
}
