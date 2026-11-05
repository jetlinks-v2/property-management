package org.jetlinks.pro.smartpark.handover.command;

import lombok.RequiredArgsConstructor;

import org.hswebframework.web.authorization.annotation.Resource;
import org.hswebframework.web.authorization.annotation.SaveAction;
import org.jetlinks.core.annotation.command.CommandHandler;
import org.jetlinks.pro.annotation.command.CommandService;
import org.jetlinks.pro.smartpark.handover.service.HandoverProblemService;
import org.jetlinks.pro.smartpark.handover.service.HandoverRules;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

/** Report integration uses the same live project, employee, attachment and idempotency checks. */
@Component
@RequiredArgsConstructor
@Resource(id = "handover-problem", name = "承接查验问题")
@CommandService(id = CreateHandoverProblemCommand.SERVICE_ID, name = "承接查验")
public class HandoverCommandSupport {
    private final HandoverProblemService problems;

    @CommandHandler(name = "报事转现场查验")
    @SaveAction
    public Mono<String> create(CreateHandoverProblemCommand command) {
        var request = command.getRequest();
        HandoverRules.require(
                request != null && HandoverRules.hasText(request.getSourceReportId()),
                "report_required");
        HandoverRules.require("SITE_INSPECTION".equals(request.getSource()), "source_invalid");
        HandoverRules.require(
                HandoverRules.hasText(request.getClientRequestId()), "request_required");
        return problems.create(request).map(problem -> problem.getId());
    }
}
