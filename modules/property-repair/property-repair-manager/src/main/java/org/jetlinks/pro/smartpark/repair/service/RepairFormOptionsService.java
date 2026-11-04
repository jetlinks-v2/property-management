package org.jetlinks.pro.smartpark.repair.service;

import lombok.AllArgsConstructor;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.Map;

/** 工单入口使用可信项目与应用读取业务规则；配置编辑、查询继续由原配置资产边界授权。 */
@Service @AllArgsConstructor
public class RepairFormOptionsService {
    private final RepairConfigurationService configurations;

    public Mono<RepairFormOptions> load(RepairContext context,String project) {
        return configurations.createQuery().where(RepairConfigurationEntity::getApplicationId,context.applicationId())
            .and(RepairConfigurationEntity::getBusinessProjectId,project).and("tenantId",context.tenantId())
            .and(RepairConfigurationEntity::getKind,"intakeOptions").fetchOne()
            .map(row->RepairFormOptions.from(row.getConfiguration()))
            .defaultIfEmpty(RepairFormOptions.from(Map.of()));
    }
}
