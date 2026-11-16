package org.jetlinks.pro.property.facility.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.property.facility.entity.DeviceAllocationEntity;
import org.springframework.stereotype.Service;

@Service
public class DeviceAllocationStore extends GenericReactiveCrudService<DeviceAllocationEntity, String> {}
