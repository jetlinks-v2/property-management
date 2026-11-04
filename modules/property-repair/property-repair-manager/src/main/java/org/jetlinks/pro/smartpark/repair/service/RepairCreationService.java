package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.hswebframework.web.id.IDGenerator;
import org.jetlinks.pro.property.base.organization.PropertyOrganizationInfo;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairOrderEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairProcessEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 登录与二维码入口共用持久化、候选池和过程/通知事务；各入口先完成自己的身份与关联校验。 */
@Service @AllArgsConstructor
public class RepairCreationService {
    private final RepairOrderService orders;
    private final RepairProcessService processes;
    private final RepairCandidateService candidates;

    Mono<RepairOrderEntity> create(CreateRepairRequest request,PropertyOrganizationInfo project,
        RepairConfigurationEntity category,RepairConfigurationEntity channel,RepairContext ctx,
        Function<RepairOrderEntity,Mono<?>> populate,Function<RepairOrderEntity,Mono<Void>> bind,
        org.jetlinks.pro.property.base.space.PropertySpaceInfo space) {
        return Mono.defer(() -> {
            RepairOrderEntity order=new RepairOrderEntity();order.setId(IDGenerator.MD5.generate());
            order.setExtension("tenantId",ctx.tenantId());order.setApplicationId(ctx.applicationId());
            order.setBusinessProjectId(project.getId());order.setOrganizationId(project.getId());
            order.setBusinessProjectName(display(project.getName(),project.getName()));
            order.setNumber("BS-"+java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss").format(java.time.LocalDateTime.now())+"-"+order.getId().substring(0,8));
            order.setCategoryId(category.getId());order.setCategoryName(category.getName());
            order.setChannelId(channel.getId());order.setChannelName(channel.getName());
            order.setSpaceId(request.getSpaceId());order.setLocation(request.getLocation());
            order.setCreatorId(ctx.userId());order.setCreatorName(ctx.userName());
            order.setTransferCount(0);order.setDelayCount(0);order.setStaffEvaluated(false);order.setOwnerEvaluated(false);
            order.setReporterId(ctx.userId());order.setStatus(0);order.setVersion(0L);order.setFlowVersion(2);
            order.setAttachmentIds(request.getAttachmentIds());order.setCreateTime(System.currentTimeMillis());order.setModifyTime(order.getCreateTime());
            if (space != null) order.setSpaceName(space.getName());
            return populate.apply(order).then(Mono.defer(()->orders.insert(order)))
                .then(Mono.defer(()->bind.apply(order))).then(candidates.initialize(order,category,ctx))
                .flatMap(created->record(created,ctx,request).thenReturn(created));
        });
    }

    private Mono<Void> record(RepairOrderEntity order,RepairContext ctx,CreateRepairRequest request) {
        RepairProcessEntity process=new RepairProcessEntity();process.setId(IDGenerator.MD5.generate());process.setOrderId(order.getId());
        process.setTenantId(ctx.tenantId());process.setApplicationId(ctx.applicationId());process.setAction("create");process.setRequestId(process.getId());
        process.setActorId(ctx.userId());process.setActorName(ctx.userName());process.setAfterStatus(order.getStatus());process.setOrderVersion(order.getVersion());
        process.setDescription(request.getDescription());process.setAttachmentIds(request.getAttachmentIds());process.setCreateTime(System.currentTimeMillis());
        if(order.getCandidateRoleId()!=null)process.setDetails(Map.of("candidateRoleId",order.getCandidateRoleId(),"candidateSource",order.getCandidateSource()));
        return processes.insert(process).then();
    }
    private static String display(String custom,String source){return custom==null||custom.isBlank()?source:custom;}
}
