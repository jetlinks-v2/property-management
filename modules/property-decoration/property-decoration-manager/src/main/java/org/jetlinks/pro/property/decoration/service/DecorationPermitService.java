package org.jetlinks.pro.property.decoration.service;

import lombok.RequiredArgsConstructor;
import org.jetlinks.pro.property.decoration.entity.DecorationApplicationEntity;
import org.jetlinks.pro.property.decoration.model.DecorationModels;
import org.jetlinks.pro.property.decoration.model.DecorationRequests;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.time.ZoneId;
import static org.jetlinks.pro.property.decoration.service.DecorationRules.*;

/** 备案发证、打印和延期均保留快照；支付集成缺失不会绕过收款门槛。 */
@Service
@RequiredArgsConstructor
public class DecorationPermitService {
    private final DecorationApplicationService applications;
    private final DecorationConfigurationService configurations;
    private final DecorationScope scope;
    private final DecorationPermitDocumentService documents;
    public Mono<DecorationApplicationEntity> issue(String id, DecorationRequests.IssuePermit input) {
        return applications.mutate(id, "issuePermit", input, false, (row, ctx) -> {
            paid(row); require(row.getPermit() == null, "permit_exists");
            return Mono.zip(configurations.validity(ctx, row.getProjectId()), scope.employee()).doOnNext(tuple -> {
                int months = tuple.getT1();
                var permit = new DecorationModels.Permit(); permit.setNumber("ZXBA-" + row.getNumber().substring(3));
                permit.setSafetyPerson(input.getSafetyPerson()); permit.setSafetyPhone(input.getSafetyPhone()); permit.setContent(input.getContent());
                permit.setEntrusted(input.isEntrusted()); permit.setWorkers(input.getWorkers()); permit.setValidityMonths(months);
                permit.setStartTime(row.getDetails().getStartTime()); permit.setEndTime(addMonths(permit.getStartTime(), months));
                permit.setApproverId(ctx.userId()); permit.setApproverName(ctx.userName());
                permit.setApproverPhone(tuple.getT2().telephone()); row.setPermit(permit); row.setStatus("pending_permit");
            }).then();
        });
    }
    public Mono<DecorationApplicationEntity> print(String id, DecorationRequests.PrintPermit input) {
        return applications.mutate(id, "printPermit", input, false, (row, ctx) -> {
            active(row); require(row.getPermit() != null, "permit_required");
            return documents.resolve(row, ctx).doOnNext(document -> {
                require(document.sealDigest().equals(input.getSealDigest()), "project_seal_changed");
                row.getPermit().setPrintTime(System.currentTimeMillis());
                if (row.getAcceptanceType() == 0) row.setStatus("permit_issued");
            }).then();
        });
    }
    public Mono<DecorationApplicationEntity> extend(String id, DecorationRequests.ExtendPermit input) {
        return applications.mutate(id, "extendPermit", input, false, (row, ctx) -> {
            active(row); require(row.getPermit() != null && input.getMonths() > 0 && input.getMonths() <= 120, "permit_required");
            var permit = row.getPermit(); var extension = new DecorationModels.Extension();
            extension.setBeforeEndTime(permit.getEndTime()); extension.setAfterEndTime(addMonths(permit.getEndTime(), input.getMonths())); extension.setMonths(input.getMonths());
            extension.setActorId(ctx.userId()); extension.setActorName(ctx.userName()); extension.setTime(System.currentTimeMillis());
            permit.setEndTime(extension.getAfterEndTime()); permit.getExtensions().add(extension); return Mono.empty();
        });
    }
    static long addMonths(long time, int months) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Shanghai")).plusMonths(months).toInstant().toEpochMilli();
    }
}
