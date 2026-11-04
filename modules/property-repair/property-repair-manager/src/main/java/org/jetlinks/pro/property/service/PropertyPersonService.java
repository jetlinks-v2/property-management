package org.jetlinks.pro.property.service;

import org.springframework.stereotype.Service;
import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.entity.PropertyPersonEntity;
/** 共享人员投影；员工与业主为独立标记，同一人可以同时具备两种身份。 */
@Service
public class PropertyPersonService extends GenericReactiveCrudService<PropertyPersonEntity,String> {}
