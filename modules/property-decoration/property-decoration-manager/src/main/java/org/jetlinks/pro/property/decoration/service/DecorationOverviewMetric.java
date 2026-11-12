package org.jetlinks.pro.property.decoration.service;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** 概况与明细共用的申请级口径；同一申请存在多个任务时仍只计一笔。 */
public enum DecorationOverviewMetric {
    totalHouses("summary"), decorating("summary"), monthNew("summary"), monthEnded("summary"),
    pendingReview("property"), pendingPermit("property"), pendingInspection("property"),
    pendingRectificationReview("property"), firstAcceptance("property"), secondAcceptance("property"), pendingRefund("property"),
    rejected("owner"), pendingPayment("owner"), ownerRectification("owner"),
    expired("risk"), expiring("risk"), renewal("risk");

    public final String group;
    DecorationOverviewMetric(String group) { this.group = group; }
    public record Condition(String sql, List<Object> parameters) {}
    private static Condition condition(String sql, Object... parameters) { return new Condition(sql, List.of(parameters)); }

    // 打印或下载即完成当前版本备案，旧领取数据继续保留。
    private static final String RECEIVED = "(a.permit::jsonb->>'collectedTime' is not null or "
        + "a.permit::jsonb->>'printTime' is not null or a.permit::jsonb->>'downloadTime' is not null)";
    private static final String ACTIVE = "a.refund_status<>2 and a.actual_end_time is null and a.status<>'finished'";
    private static final String LATEST = "not exists (select 1 from pd_application newer where newer.application_id=a.application_id "
        + "and newer.tenant_id=a.tenant_id and newer.project_id=a.project_id and newer.space_id=a.space_id and newer.deleted=false "
        + "and (newer.create_time>a.create_time or (newer.create_time=a.create_time and newer.id>a.id)))";

    /** 仅枚举内受控SQL可进入查询；所有日期均为绑定参数。 */
    public Condition condition(long asOf) {
        var month = Instant.ofEpochMilli(asOf).atZone(ZoneId.of("Asia/Shanghai")).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai"));
        long start = month.toInstant().toEpochMilli(), next = month.plusMonths(1).toInstant().toEpochMilli();
        return switch (this) {
            case totalHouses -> condition(LATEST);
            case decorating -> condition(ACTIVE + " and " + RECEIVED + " and a.start_time<=? and a.status in "
                + "('pending_permit','permit_issued','first_acceptance_waiting','first_acceptance_in_progress','first_acceptance_failed',"
                + "'pending_second_acceptance','second_acceptance_waiting','second_acceptance_in_progress','second_acceptance_failed','acceptance_passed')", asOf);
            case monthNew -> condition("a.create_time>=? and a.create_time<?", start, next);
            case monthEnded -> condition("a.refund_status=2 and a.actual_end_time>=? and a.actual_end_time<?", start, next);
            case pendingReview -> condition(ACTIVE + " and a.status='reviewing'");
            case rejected -> condition(ACTIVE + " and a.status='rejected'");
            case pendingPayment -> condition(ACTIVE + " and a.status='pending_payment'");
            case pendingPermit -> condition(ACTIVE + " and a.payment_status=2 and not coalesce(" + RECEIVED + ",false)");
            case pendingInspection -> task("t.kind='daily' and t.status='pending'");
            case pendingRectificationReview -> task("t.kind='inspectionRecord' and t.status='waiting_review'");
            case ownerRectification -> task("t.kind='inspectionRecord' and t.status in ('owner_rectification','rectification_rejected')");
            case firstAcceptance -> condition(ACTIVE + " and a.status in ('first_acceptance_waiting','first_acceptance_in_progress')");
            case secondAcceptance -> condition(ACTIVE + " and a.status in ('second_acceptance_waiting','second_acceptance_in_progress')");
            case pendingRefund -> condition(ACTIVE + " and a.status='acceptance_passed' and a.payment_status=2 and a.refund_status<>2");
            case expired -> condition(ACTIVE + " and (a.permit::jsonb->>'endTime')::bigint<?", asOf);
            case expiring -> condition(ACTIVE + " and (a.permit::jsonb->>'endTime')::bigint>=? and (a.permit::jsonb->>'endTime')::bigint<=?", asOf, asOf + 7 * 86400000L);
            case renewal -> condition(ACTIVE + " and coalesce(jsonb_array_length(a.permit::jsonb->'extensions'),0)>0 and not coalesce(" + RECEIVED + ",false)");
        };
    }

    private static Condition task(String predicate) {
        // 子任务必须同时匹配应用、租户、项目及装修ID，避免异常或外域任务影响统计。
        return condition(ACTIVE + " and exists (select 1 from pd_task t where t.decoration_id=a.id "
            + "and t.application_id=a.application_id and t.tenant_id=a.tenant_id and t.project_id=a.project_id and " + predicate + ")");
    }
}
