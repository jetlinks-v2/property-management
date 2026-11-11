package org.jetlinks.pro.property.facility.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.facility.model.MeterUsageEvidence;
import reactor.core.publisher.Mono;

/** Reads current authorized device, allocation, rule and accepted readings; failures are not zero usage. @since 2.20.0 */
public class QueryFacilityMeterUsageCommand extends AbstractCommand<Mono<MeterUsageEvidence>, QueryFacilityMeterUsageCommand> {
  public static final String SERVICE_ID = "facilityService:meter-usage";
  public String getDeviceId() { return getOrNull("deviceId", String.class); }
  public void setDeviceId(String value) { with("deviceId", value); }
  public String getMonth() { return getOrNull("month", String.class); }
  public void setMonth(String value) { with("month", value); }
  /** Identity lookup still checks current device assets; it permits finance to recover an existing frozen bill after a rule is disabled. */
  public boolean isIdentityOnly() { return Boolean.TRUE.equals(getOrNull("identityOnly",Boolean.class)); }
  public void setIdentityOnly(boolean value) { with("identityOnly",value); }
}
