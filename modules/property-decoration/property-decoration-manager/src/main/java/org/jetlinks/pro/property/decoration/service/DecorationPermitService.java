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

/** 备案发证、打印下载和延期保留快照；沿用审核收款和申请权限边界，输出完成后推进备案。 */
@Service
@RequiredArgsConstructor
public class DecorationPermitService {
    private final DecorationApplicationService applications;
    private final DecorationConfigurationService configurations;
    private final DecorationScope scope;
    private final DecorationPermitDocumentService documents;
    private final DecorationAttachmentService attachments;
    /** 与发证共用既有申请聚合、员工和项目权限边界，继续登记不修改已发证的正文与历史图片。 */
    public Mono<DecorationApplicationEntity> registerWorkers(String id, DecorationRequests.RegisterWorkers input) {
        return applications.mutate(id, "registerWorkers", input, false, (row, ctx) -> {
            paid(row);
            var registration = new DecorationModels.WorkerRegistration();
            var permit = row.getPermit();
            registration.setSafetyPerson(permit == null ? input.getSafetyPerson() : permit.getSafetyPerson());
            registration.setSafetyPhone(permit == null ? input.getSafetyPhone() : permit.getSafetyPhone());
            registration.setContent(permit == null ? input.getContent() : permit.getContent());
            registration.setEntrusted(permit == null ? input.isEntrusted() : permit.isEntrusted());
            registration.setWorkers(input.getWorkers());
            registration.setOperatorId(ctx.userId()); registration.setOperatorName(ctx.userName());
            registration.setTime(System.currentTimeMillis());
            row.getDetails().setWorkerRegistration(registration);
            if (permit != null) permit.setWorkers(input.getWorkers());
            return Mono.empty();
        });
    }
    public Mono<DecorationApplicationEntity> issue(String id, DecorationRequests.IssuePermit input) {
        return applications.mutate(id, "issuePermit", input, false, (row, ctx) -> {
            paid(row); require(row.getPermit() == null, "permit_exists");
            return Mono.zip(configurations.validity(ctx, row.getProjectId()), scope.employee()).doOnNext(tuple -> {
                int months = tuple.getT1();
                var permit = new DecorationModels.Permit(); permit.setNumber("ZXBA-" + row.getNumber().substring(3));
                // 已独立登记的申请使用可信保存内容；已发布的原办理接口仍可原子登记并发证。
                var registration = row.getDetails().getWorkerRegistration();
                permit.setSafetyPerson(registration == null ? input.getSafetyPerson() : registration.getSafetyPerson());
                permit.setSafetyPhone(registration == null ? input.getSafetyPhone() : registration.getSafetyPhone());
                permit.setContent(registration == null ? input.getContent() : registration.getContent());
                permit.setEntrusted(registration == null ? input.isEntrusted() : registration.isEntrusted());
                permit.setWorkers(registration == null ? input.getWorkers() : registration.getWorkers()); permit.setValidityMonths(months);
                permit.setStartTime(row.getDetails().getStartTime()); permit.setEndTime(addMonths(permit.getStartTime(), months));
                permit.setApproverId(ctx.userId()); permit.setApproverName(ctx.userName());
                permit.setApproverPhone(tuple.getT2().telephone()); row.setPermit(permit); row.setStatus("pending_permit");
            }).then();
        });
    }
    public Mono<DecorationApplicationEntity> print(String id, DecorationRequests.PrintPermit input) {
        require("print".equals(input.getOutputType()) || "download".equals(input.getOutputType()), "invalid_attachment");
        boolean downloading = "download".equals(input.getOutputType());
        return applications.mutate(id, downloading ? "downloadPermit" : "printPermit", input, false, (row, ctx) -> {
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
                    if (downloading) row.getPermit().setDownloadTime(now);
                    else row.getPermit().setPrintTime(now);
                    row.getPermit().getPrints().add(new DecorationModels.PermitPrint(input.getSnapshotFileId(),
                        document.sealDigest(), row.getPermit().getEndTime(), ctx.userId(), ctx.userName(), now, input.getOutputType()));
                    // 首次输出完成当前版本备案；重复输出不能倒退已进入的验收流程，不补造领取记录。
                    if ("pending_permit".equals(row.getStatus())) row.setStatus("permit_issued");
                }));
            });
        });
    }
    /** 兼容旧客户端的领取登记；新流程不依赖该动作，历史领取信息仍保留原权限和幂等边界。 */
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
            require(row.getPermit().getEndTime() <= System.currentTimeMillis() + 7 * 86400000L, "permit_extension_too_early");
            var permit = row.getPermit(); var extension = new DecorationModels.Extension();
            extension.setBeforeEndTime(permit.getEndTime()); extension.setAfterEndTime(addMonths(permit.getEndTime(), input.getMonths())); extension.setMonths(input.getMonths());
            extension.setActorId(ctx.userId()); extension.setActorName(ctx.userName()); extension.setTime(System.currentTimeMillis());
            if (permit.getPrints().isEmpty() && permit.getPrintTime() != null) permit.setLegacyPrintTime(permit.getPrintTime());
            permit.setEndTime(extension.getAfterEndTime()); permit.getExtensions().add(extension);
            // 延期后需输出新有效期的证件；历史图片、输出与既有领取记录不覆盖。
            permit.setPrintTime(null); permit.setDownloadTime(null); permit.setCollectedTime(null); permit.setCollectedBy(null);
            permit.setCollectionOperatorId(null); permit.setCollectionOperatorName(null); permit.setCollectionSnapshotFileId(null);
            if (row.getAcceptanceType() == 0) row.setStatus("pending_permit");
            return Mono.empty();
        });
    }
    static long addMonths(long time, int months) {
        return Instant.ofEpochMilli(time).atZone(ZoneId.of("Asia/Shanghai")).plusMonths(months).toInstant().toEpochMilli();
    }
}
