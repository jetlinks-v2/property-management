package org.jetlinks.pro.smartpark.handover.service;

import org.hswebframework.web.crud.service.GenericReactiveCrudService;
import org.jetlinks.pro.smartpark.handover.entity.HandoverItemEntity;
import org.springframework.stereotype.Service;

/** Persistence only; authorization and transactions belong to the application service. */
@Service
public class HandoverItemRepository
        extends GenericReactiveCrudService<HandoverItemEntity, String> {}
