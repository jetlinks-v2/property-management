package org.jetlinks.pro.property.finance.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.property.finance.meter.MeterBillingModels;
import reactor.core.publisher.Mono;

/**
 * Preview or confirm a natural-month bill. Finance re-fetches facility evidence and house/customer/standard.
 * Confirmation requires the exact preview fingerprint; a stable meter/month key makes retries idempotent.
 * No caller-supplied quantity, amount, formula or customer identity is accepted. @since 2.20.0
 */
public class ProcessMeterBillCommand extends AbstractCommand<Mono<MeterBillingModels.Result>, ProcessMeterBillCommand> {
  public String getDeviceId() { return getOrNull("deviceId", String.class); }
  public void setDeviceId(String value) { with("deviceId", value); }
  public String getMonth() { return getOrNull("month", String.class); }
  public void setMonth(String value) { with("month", value); }
  public String getReceivableDate() { return getOrNull("receivableDate", String.class); }
  public void setReceivableDate(String value) { with("receivableDate", value); }
  public String getDueDate() { return getOrNull("dueDate", String.class); }
  public void setDueDate(String value) { with("dueDate", value); }
  public boolean isConfirm() { return Boolean.TRUE.equals(getOrNull("confirm", Boolean.class)); }
  public void setConfirm(boolean value) { with("confirm", value); }
  public String getFingerprint() { return getOrNull("fingerprint", String.class); }
  public void setFingerprint(String value) { with("fingerprint", value); }
}
