package org.jetlinks.pro.smartpark.handover.service;

/** Trusted tenant/application/user tuple; reconstructed for every request and export attempt. */
public record HandoverContext(
        String tenantId, String applicationId, String userId, String userName) {}
