package org.jetlinks.pro.smartpark.handover.command;

import org.jetlinks.core.command.AbstractCommand;
import org.jetlinks.pro.smartpark.handover.api.ProblemRequest;

import reactor.core.publisher.Mono;

/**
 * Controlled report-to-inspection boundary. The receiver checks the live caller, project, source
 * and attachments exactly as HTTP intake; no entity or manager dependency crosses it.
 *
 * @since 2.20.0
 * @see ProblemRequest
 */
public class CreateHandoverProblemCommand
        extends AbstractCommand<Mono<String>, CreateHandoverProblemCommand> {
    public static final String SERVICE_ID = "propertyService:handover";

    public ProblemRequest getRequest() {
        return getOrNull("request", ProblemRequest.class);
    }

    public void setRequest(ProblemRequest request) {
        with("request", request);
    }
}
