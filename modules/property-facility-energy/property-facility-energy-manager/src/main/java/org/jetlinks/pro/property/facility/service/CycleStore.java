package org.jetlinks.pro.property.facility.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.CycleEntity;
import org.springframework.stereotype.Service;

/** 周期计划持久化，权限由领域入口和统一资产体系控制。 */
@Service
public class CycleStore extends GenericReactiveCrudService<CycleEntity, String> {}
