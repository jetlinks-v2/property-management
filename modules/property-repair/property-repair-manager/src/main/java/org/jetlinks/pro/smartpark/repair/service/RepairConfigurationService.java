package org.jetlinks.pro.smartpark.repair.service;

import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.repair.entity.RepairConfigurationEntity;
/** RepairConfiguration 的响应式持久化服务；业务动作由工单应用服务统一编排。 */
@Service
public class RepairConfigurationService extends GenericReactiveCrudService<RepairConfigurationEntity,String> {}
