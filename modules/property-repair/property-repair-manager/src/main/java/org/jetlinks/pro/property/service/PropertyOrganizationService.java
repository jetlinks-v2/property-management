package org.jetlinks.pro.property.service;

import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.entity.PropertyOrganizationEntity;
/** 共享的组织投影与业务项目标签；ID 与 Cloud 组织一致，不按应用复制。 */
@Service("repairPropertyOrganizationService")
public class PropertyOrganizationService extends GenericReactiveCrudService<PropertyOrganizationEntity,String> {}
