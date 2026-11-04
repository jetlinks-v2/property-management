package org.jetlinks.pro.smartpark.handover.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.handover.entity.HandoverEquipmentEntity;
import org.springframework.stereotype.Service;

/** Persistence only; authorization and transactions belong to the application service. */
@Service
public class HandoverEquipmentRepository
        extends GenericReactiveCrudService<HandoverEquipmentEntity, String> {}
