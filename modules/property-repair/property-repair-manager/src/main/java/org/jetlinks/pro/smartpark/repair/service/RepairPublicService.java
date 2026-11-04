package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.jetlinks.pro.smartpark.repair.api.CreateRepairRequest;
import org.jetlinks.pro.smartpark.repair.api.PublicRepairRequest;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.jetlinks.pro.smartpark.repair.entity.RepairQrUploadEntity;
import org.jetlinks.pro.tenant.holder.TenantHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 匿名入口仅允许创建，不返回工单实体、员工、流程角色、联系方式列表或文件访问凭证。 */
@Service @AllArgsConstructor
public class RepairPublicService {
    private final RepairQrGrantProvider grants;
    private final RepairQrUploadService uploads;
    private final RepairQrAssetBinder binder;
    private final RepairCreationService creation;
    private final RepairFormOptionsService formOptions;

    public Mono<Map<String,Object>> form(String code) {
        return grants.resolve(code).flatMap(grant -> formOptions.load(grant.context(),grant.project.getId()).map(options -> {
            grant.channel();
            java.util.Set<String> categories=grant.configurations.values().stream().filter(row->grant.categoryAllowed(row.getId()))
                .map(RepairConfigurationEntity::getId).collect(java.util.stream.Collectors.toSet());
            List<RepairConfigurationEntity> labels=grant.configurations.values().stream()
                .filter(row->"label".equals(row.getKind())&&categories.contains(row.getParentId())).toList();
            List<String> labelIds=labels.stream().map(RepairConfigurationEntity::getId).toList();
            List<Map<String,Object>> dictionaries=grant.configurations.values().stream().filter(row->
                categories.contains(row.getId())||labelIds.contains(row.getId())||"description".equals(row.getKind())&&labelIds.contains(row.getParentId()))
                .sorted(java.util.Comparator.comparing(row->Objects.requireNonNullElse(row.getSortIndex(),0)))
                .map(row->Map.<String,Object>of("id",row.getId(),"name",row.getName(),"kind",row.getKind(),
                    "parentId",Objects.toString(row.getParentId(),""),"content",Objects.toString(row.getConfiguration()==null?null:row.getConfiguration().get("content"),""))).toList();
            List<Map<String,Object>> spaces=grant.spaces.values().stream().filter(row->grant.spaceAllowed(row.getId()))
                .sorted(java.util.Comparator.comparing(row->display(row.getName(),row.getName())))
                .map(row->Map.<String,Object>of("id",row.getId(),"name",display(row.getName(),row.getName()),
                    "parentId",row.getParentId()!=null&&grant.spaceAllowed(row.getParentId())?row.getParentId():"",
                    "selectable",grant.selectable(row.getId()),"positionType",grant.positionType(row.getId()))).toList();
            return Map.<String,Object>of("name",grant.qr.getName(),"welcomeText",grant.settings.get("welcomeText"),
                "projectName",display(grant.project.getName(),grant.project.getName()),"configurations",dictionaries,
                "spaces",spaces,"enableCampus",Boolean.TRUE.equals(grant.settings.get("enableCampus")),
                "campusOptions",RepairQrRules.areas(grant.settings),"formOptions",options.values());
        }));
    }

    @Transactional
    public Mono<Map<String,String>> submit(String code,PublicRepairRequest input) {
        return grants.resolve(code).flatMap(grant -> formOptions.load(grant.context(),grant.project.getId()).flatMap(options -> {
            options.normalize(input);
            RepairFlowRules.require(grant.categoryAllowed(input.getCategoryId()),"qr_category_not_allowed");
            RepairFlowRules.require(grant.selectable(input.getSpaceId()),"qr_space_not_allowed");
            List<RepairConfigurationEntity> labels=input.getLabelIds().stream().distinct().map(id->grant.configuration(id,"label")).toList();
            labels.forEach(label->RepairFlowRules.require(Objects.equals(label.getParentId(),input.getCategoryId()),"label_category_mismatch"));
            List<String> choices=input.getDescriptionChoiceIds().stream().distinct().map(id->{
                var choice=grant.configuration(id,"description");
                RepairFlowRules.require(input.getLabelIds().contains(choice.getParentId()),"description_label_mismatch");
                return Objects.toString(choice.getConfiguration().get("content"),choice.getName());
            }).toList();
            input.setDescription(options.description(input.getDescription(),choices));
            return uploads.receipts(input.getUploadIds(),grant).flatMap(receipts -> {
                CreateRepairRequest request=new CreateRepairRequest();request.setBusinessProjectId(grant.project.getId());
                request.setCategoryId(input.getCategoryId());request.setChannelId(grant.channel().getId());
                request.setSpaceId(input.getSpaceId());request.setLocation(input.getLocation());request.setPositionType(grant.positionType(input.getSpaceId()));
                request.setUrgency(input.getUrgency());request.setReportSource(2);request.setDescription(input.getDescription());request.setContactName(input.getContactName());request.setContactPhone(input.getContactPhone());
                request.setLabelIds(input.getLabelIds());request.setDescriptionChoiceIds(input.getDescriptionChoiceIds());
                request.setAttachmentIds(receipts.stream().map(RepairQrUploadEntity::getFileId).toList());
                return creation.create(request,grant.project,grant.configuration(input.getCategoryId(),"category"),grant.channel(),grant.context(),order->{
                    RepairIntakeService.applyFields(order,request);order.setReporterId(null);
                    order.setRegisterPhone(input.getContactPhone());
                    order.setLabelIds(labels.stream().map(RepairConfigurationEntity::getId).toList());order.setLabelNames(labels.stream().map(RepairConfigurationEntity::getName).toList());
                    order.setIntakeIdentity("anonymous");order.setQrConfigurationId(grant.qr.getId());
                    return Mono.empty();
                },order->binder.bind("repairOrder",order.getId(),grant),grant.spaces.get(input.getSpaceId()))
                    .flatMap(order->uploads.consume(receipts,order.getId()).thenReturn(Map.of("number",order.getNumber())))
                    .contextWrite(TenantHolder.makeCurrent(grant.context().tenantId()));
            });
        }));
    }
    private static String display(String custom,String source){return custom==null||custom.isBlank()?source:custom;}
}
