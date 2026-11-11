package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.jetlinks.core.trace.MonoTracer;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.entity.MeterBillEntity;
import org.springframework.stereotype.Service;
import reactor.core.publisher.*;

/** CAS claim precedes remote confirmation; a failed/abandoned claim can only recover the same financial key. */
@Service @RequiredArgsConstructor
public class FacilityMeterReviews {
  private final FacilityMeterDrafts drafts;
  private final FacilityRecords records;
  private final FacilityScope scope;
  private final MeterBillStore bills;
  public record Decision(String id, Long version, String comment, String receivableDate, String dueDate) {}
  public record Outcome(String id, String name, boolean success, String error) {}

  public Mono<List<Outcome>> batch(String action,List<Decision> inputs) {
    require(inputs!=null && !inputs.isEmpty() && inputs.size()<=100 && inputs.stream().allMatch(i -> i!=null && i.id()!=null)
      && inputs.stream().map(Decision::id).distinct().count()==inputs.size(),"too_many");
    return Flux.fromIterable(inputs).concatMap(input -> act(action,input).map(row -> new Outcome(row.getId(),row.getDeviceName(),true,null))
      .onErrorResume(e -> Mono.just(new Outcome(input.id(),input.id(),false,FacilityMeterDrafts.error(e))))).collectList();
  }

  public Mono<MeterBillEntity> act(String action, Decision input) {
    require(Set.of("review","reject","recalculate","recover").contains(action),"invalid_state");
    require(input!=null && input.version()!=null,"conflict");
    return drafts.read(input.id(),CrudAssetPermission.save).flatMap(row -> {
      require(Objects.equals(row.getVersion(),input.version()),"conflict");
      return switch(action) {
        case "reject" -> reject(row,input);
        case "recalculate" -> recalculate(row,input);
        case "recover" -> confirm(row,input,true);
        default -> confirm(row,input,false);
      };
    }).as(MonoTracer.create("property.facility.meter.monthly."+action));
  }

  private Mono<MeterBillEntity> reject(MeterBillEntity row,Decision input) {
    require("pending".equals(row.getState()),"meter_review_state");
    require(input.comment()!=null && !input.comment().isBlank() && input.comment().length()<=512,"meter_review_reason");
    return scope.current().flatMap(c -> {
      row.setState("rejected"); row.setComment(input.comment().trim()); row.setReviewerId(c.userId()); row.setReviewerName(c.userName());
      row.setReviewedTime(System.currentTimeMillis());
      return records.update(bills,row,input.version()).flatMap(saved -> log(saved,"reject","pending").thenReturn(saved));
    });
  }

  private Mono<MeterBillEntity> recalculate(MeterBillEntity row, Decision input) {
    require(Set.of("pending","rejected","error").contains(row.getState()),"meter_review_state");
    if(input.receivableDate()!=null || input.dueDate()!=null) {
      try {
        var date=LocalDate.parse(input.receivableDate()); var due=LocalDate.parse(input.dueDate());
        require(!due.isBefore(date),"invalid_period"); row.setReceivableDate(date.toString()); row.setDueDate(due.toString());
      } catch(java.time.format.DateTimeParseException|NullPointerException invalid) { require(false,"invalid_period"); }
    }
    String previous=row.getState();
    return drafts.calculate(row).flatMap(calculated -> records.update(bills,calculated,input.version(),
      FacilityRecords.nullFields(calculated,"error"))).flatMap(saved -> log(saved,"recalculate",previous).thenReturn(saved));
  }

  private Mono<MeterBillEntity> confirm(MeterBillEntity row,Decision input,boolean recovering) {
    if(recovering) require("failed".equals(row.getState()) || "processing".equals(row.getState()) &&
      row.getModifyTime()<System.currentTimeMillis()-120000,"meter_review_state");
    else require("pending".equals(row.getState()),"meter_review_state");
    require(row.getResult()!=null && row.getResult().fingerprint()!=null,"meter_review_state");
    require(input.comment()==null || input.comment().length()<=512,"too_many");
    String previous=row.getState();
    return scope.current().flatMap(c -> {
      row.setState("processing");
      if(!recovering) {
        row.setReviewerId(c.userId()); row.setReviewerName(c.userName()); row.setReviewedTime(System.currentTimeMillis()); row.setComment(input.comment());
      }
      // No local transaction spans the RPC. Persist the claim first so restarts cannot turn an uncertain write into a new draft.
      return records.update(bills,row,input.version(),FacilityRecords.nullFields(row,"comment"))
        .flatMap(claim -> drafts.process(claim,true).flatMap(result -> {
          require(result.billId()!=null,"meter_review_state");
          FacilityMeterDrafts.apply(claim,result);
          return records.update(bills,claim,claim.getVersion(),"error");
        }).onErrorResume(error -> {
          // CAS must target the persisted claim, not a mutated version from a failed completion update.
          return bills.findById(claim.getId()).flatMap(current -> {
            if(!"processing".equals(current.getState())) return Mono.error(error);
            // Finance explicitly rejects a changed fingerprint before inserting. Transport failures remain uncertain.
            current.setState("error.property_finance_meter_preview_changed".equals(error.getMessage())?"error":"failed");
            current.setError(FacilityMeterDrafts.error(error));
            return records.update(bills,current,current.getVersion())
              .flatMap(saved -> records.log(saved,"meterBill","failed","processing",saved.getState(),saved.getError(),List.of()))
              .then(Mono.<MeterBillEntity>error(error));
          });
        })).flatMap(saved -> log(saved,recovering?"recover":"review",previous).thenReturn(saved));
    });
  }

  private Mono<Void> log(MeterBillEntity row,String action,String previous) {
    return records.log(row,"meterBill",action,previous,row.getState(),row.getComment(),List.of());
  }
}
