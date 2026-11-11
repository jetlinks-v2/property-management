package org.jetlinks.pro.property.finance.web;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Authorize;
import org.jetlinks.pro.property.finance.service.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;

import java.time.*;
import java.util.List;

/** 已认证客户的本人资源接口；每次由基础本人命令核验关联及租户，不能代替员工管理接口。 */
@RestController
@Authorize
@RequiredArgsConstructor
@RequestMapping("/property/finance/owner")
public class OwnerFinanceController {
    private final OwnerApplicationScope applicationScope;
    private final OwnerHouseSource houses;
    private final OwnerFinanceService finance;
    private final OwnerPrepayService prepay;
    private final BillingJobProperties properties;

    /** 供H5选择/恢复应用时核验；不返回其他成员、权限或内部配置。 */
    @GetMapping("/context")
    public Mono<ApplicationContext> context() {
        return applicationScope.current().map(ApplicationContext::new);
    }

    public record ApplicationContext(String applicationId) {}

    @GetMapping("/rooms")
    public Mono<List<Room>> rooms() {
        return houses.rooms(null, null)
                .map(
                        rows ->
                                rows.stream()
                                        .map(
                                                row -> {
                                                    var h = row.context().house();
                                                    var c = row.context().customers().get(0);
                                                    return new Room(
                                                            h.projectId(),
                                                            row.projectName(),
                                                            h.spaceId(),
                                                            h.name(),
                                                            h.fullName(),
                                                            c.name(),
                                                            c.nature());
                                                })
                                        .toList());
    }

    @GetMapping("/projects/{project}/spaces/{space}/bills")
    public Mono<OwnerFinanceService.BillPage> bills(
            @PathVariable String project,
            @PathVariable String space,
            @RequestParam(defaultValue = "false") boolean paid,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "20") int pageSize) {
        return finance.bills(project, space, paid, pageIndex, pageSize);
    }

    @GetMapping("/projects/{project}/spaces/{space}/accounts")
    public Mono<List<OwnerFinanceService.Account>> accounts(
            @PathVariable String project, @PathVariable String space) {
        return finance.accounts(project, space);
    }

    @GetMapping("/projects/{project}/spaces/{space}/accounts/{id}/entries")
    public Mono<OwnerFinanceService.Page<OwnerFinanceService.Entry>> entries(
            @PathVariable String project,
            @PathVariable String space,
            @PathVariable String id,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "20") int pageSize) {
        return finance.entries(project, space, id, pageIndex, pageSize);
    }

    @GetMapping("/projects/{project}/spaces/{space}/entries")
    public Mono<OwnerFinanceService.Page<OwnerFinanceService.Entry>> roomEntries(
            @PathVariable String project,
            @PathVariable String space,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "20") int pageSize) {
        return finance.roomEntries(project, space, pageIndex, pageSize);
    }

    @GetMapping("/projects/{project}/spaces/{space}/records")
    public Mono<OwnerFinanceService.Page<OwnerFinanceService.Record>> records(
            @PathVariable String project,
            @PathVariable String space,
            @RequestParam(defaultValue = "0") int pageIndex,
            @RequestParam(defaultValue = "20") int pageSize) {
        return finance.records(project, space, pageIndex, pageSize);
    }

    @GetMapping("/projects/{project}/spaces/{space}/prepay/options")
    public Mono<List<OwnerSetting>> options(
            @PathVariable String project, @PathVariable String space) {
        return prepay.options(project, space, date())
                .map(
                        rows ->
                                rows.stream()
                                        .map(
                                                row ->
                                                        new OwnerSetting(
                                                                row.id(),
                                                                row.settlementMethod(),
                                                                row.activities()))
                                        .toList());
    }

    @PostMapping("/projects/{project}/spaces/{space}/prepay/quote")
    public Mono<PrepayQuoteService.View> quote(
            @PathVariable String project,
            @PathVariable String space,
            @RequestBody OwnerPrepayService.Request input) {
        return prepay.quote(project, space, input, date());
    }

    @PostMapping("/projects/{project}/spaces/{space}/prepay/revalidate")
    public Mono<PrepayQuoteService.View> revalidate(
            @PathVariable String project, @PathVariable String space, @RequestBody Review input) {
        return prepay.revalidate(project, space, input.quoteId(), date());
    }

    private LocalDate date() {
        return LocalDate.now(ZoneId.of(properties.getZone()));
    }

    /** 收费配置仅供自动匹配，不向业主输出内部科目、标准名称或其他付款人。 */
    public record OwnerSetting(
            String id, String settlementMethod, List<PrepayOptionsService.Activity> activities) {}

    public record Review(String quoteId) {}

    public record Room(
            String projectId,
            String projectName,
            String spaceId,
            String name,
            String fullName,
            String customerName,
            String nature) {}
}
