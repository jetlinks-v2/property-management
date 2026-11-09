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
    private final DecorationAttachmentService attachments;
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
            return documents.resolve(row, ctx).flatMap(document -> {
                require(document.sealDigest().equals(input.getSealDigest()), "project_seal_changed");
                Mono<Void> validated;
                if (document.snapshotFileId() != null) {
                    require(document.snapshotFileId().equals(input.getSnapshotFileId()), "invalid_attachment");
                    validated = Mono.empty();
                } else validated = attachments.validatePermitSnapshot(input.getSnapshotFileId(), row, ctx);
                return validated.then(Mono.fromRunnable(() -> {
                    long now = System.currentTimeMillis();
                    row.getPermit().setPrintTime(now);
                    row.getPermit().getPrints().add(new DecorationModels.PermitPrint(input.getSnapshotFileId(),
                        document.sealDigest(), row.getPermit().getEndTime(), ctx.userId(), ctx.userName(), now));
                    // 打印登记不代表领取，只有独立领取动作推进新申请的领证状态。
                    if (row.getAcceptanceType() == 0 && row.getPermit().getCollectedTime() == null) row.setStatus("pending_permit");
                }));
            });
        });
    }
    /** 领取与打印分开，沿用打印操作权限、员工资格及聚合范围/版本/幂等边界。 */
    public Mono<DecorationApplicationEntity> collect(String id, DecorationRequests.CollectPermit input) {
        return applications.mutate(id, "collectPermit", input, false, (row, ctx) -> {
            active(row);
            var permit = row.getPermit();
            require(permit != null && DecorationPermitDocumentService.currentPrint(row) != null, "permit_snapshot_required");
            require(permit.getCollectedTime() == null, "permit_collected");
            long firstPrint = permit.getPrints().stream().filter(print -> java.util.Objects.equals(print.endTime(), permit.getEndTime()))
                .mapToLong(DecorationModels.PermitPrint::time).min().orElseThrow();
            require(input.getCollectedTime() >= firstPrint
                && input.getCollectedTime() <= System.currentTimeMillis(), "invalid_collection_time");
            permit.setCollectedTime(input.getCollectedTime()); permit.setCollectedBy(input.getCollectedBy());
            permit.setCollectionOperatorId(ctx.userId()); permit.setCollectionOperatorName(ctx.userName());
            permit.setCollectionSnapshotFileId(DecorationPermitDocumentService.currentPrint(row).snapshotFileId());
            permit.getCollections().add(new DecorationModels.PermitCollection(permit.getCollectionSnapshotFileId(), permit.getEndTime(),
                input.getCollectedBy(), input.getCollectedTime(), ctx.userId(), ctx.userName()));
            if (row.getAcceptanceType() == 0) row.setStatus("permit_issued");
            return Mono.empty();
        });
    }
    public Mono<DecorationApplicationEntity> extend(String id, DecorationRequests.ExtendPermit input) {
        return applications.mutate(id, "extendPermit", input, false, (row, ctx) -> {
            active(row); require(row.getPermit() != null && input.getMonths() > 0 && input.getMonths() <= 120, "permit_required");
            var permit = row.getPermit(); var extension = new DecorationModels.Extension();
            extension.setBeforeEndTime(permit.getEndTime()); extension.setAfterEndTime(addMonths(permit.getEndTime(), input.getMonths())); extension.setMonths(input.getMonths());
            extension.setActorId(ctx.userId()); extension.setActorName(ctx.userName()); extension.setTime(System.currentTimeMillis());
            if (permit.getPrints().isEmpty() && permit.getPrintTime() != null) permit.setLegacyPrintTime(permit.getPrintTime());
            permit.setEndTime(extension.getAfterEndTime()); permit.getExtensions().add(extension);
            // 延期产生待打印、待领取的新证，历史打印/领取不可被新证覆盖。
            permit.setPrintTime(null); permit.setCollectedTime(null); permit.setCollectedBy(null);
            permit.setCollectionOperatorId(null); permit.setCollectionOperatorName(null); permit.setCollectionSnapshotFileId(null);
            if (row.getAcceptanceType() == 0) row.setStatus("pending_permit");
            return Mono.empty();
        });
    }
    static long addMonths(long time, int months) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Shanghai")).plusMonths(months).toInstant().toEpochMilli();
    }
}
