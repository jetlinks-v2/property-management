package org.jetlinks.pro.property.facility.service;

import static org.jetlinks.pro.property.facility.service.FacilityScope.require;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.hswebframework.web.api.crud.entity.*;
import org.jetlinks.pro.assets.crud.CrudAssetPermission;
import org.jetlinks.pro.property.facility.asset.FacilityAssetType;
import org.jetlinks.pro.property.facility.entity.*;
import org.jetlinks.pro.property.facility.model.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;

/** 任务状态、参与者、版本和幂等owner；离线提交与在线执行共用此链路。 */
@Service
@RequiredArgsConstructor
public class FacilityTasks {

  private final FacilityFiles files;
  private final TaskStore tasks;
  private final FacilityScope scope;
  private final FacilityRecords records;
  private final FacilityDirectory directory;
  private final FacilityTaskExecutions executions;

  public Mono<TaskEntity> read(String id) {
    return records.read(
      tasks,
      id,
      FacilityAssetType.facilityTask,
      CrudAssetPermission.read
    );
  }

  public Mono<PagerResult<TaskEntity>> query(QueryParamEntity q, boolean mine) {
    return scope.current().flatMap(c -> {
      var request = q.clone().toNestQuery().isNull("parentTaskId").getParam();
      includeLegacyReview(request.getTerms());
      if (mine) request = request
        .toNestQuery()
        .nest(n ->
          n
            .is("assigneeId", c.userId())
            .or()
            .is("reviewerId", c.userId())
        )
        .getParam();
      return scope
        .query(request, FacilityAssetType.facilityTask, "id")
        .flatMap(tasks::queryPager);
    });
  }

  /** Previously submitted external orders now enter the same responsible-person review queue. */
  private void includeLegacyReview(List<org.hswebframework.ezorm.core.param.Term> terms) {
    for (var term : terms) {
      if ("state".equals(term.getColumn()) && "review".equals(term.getValue()) &&
          (term.getTermType() == null || "eq".equals(term.getTermType()))) {
        term.setTermType("in"); term.setValue(List.of("review", "external-submitted"));
      }
      if (term.getTerms() != null) includeLegacyReview(term.getTerms());
    }
  }

  public Mono<PagerResult<TaskEntity>> query(QueryParamEntity q, boolean mine, boolean pool) {
    return pool ? directory.claimPool(q).flatMap(scoped -> query(scoped, mine)) : query(q, mine);
  }

  /** Review queue is a participant filter intersected with the existing task AssetsHolder scope, before paging. */
  public Mono<PagerResult<TaskEntity>> reviews(QueryParamEntity query, String status) {
    var states = switch (status) {
      case "pending" -> List.of("review", "external-submitted");
      case "approved" -> List.of("completed");
      case "rejected" -> List.of("rectify");
      case "all" -> List.of("review", "external-submitted", "completed", "rectify");
      default -> List.<String>of();
    };
    require(!states.isEmpty(), "invalid_state");
    return scope.current().flatMap(c -> query(query.clone().toNestQuery()
      .and("reviewerId", c.userId()).in("state", states).getParam(), false));
  }

  public Flux<org.jetlinks.pro.property.base.user.PropertyEmployeeInfo> assignees(String id) {
    return records.read(tasks, id, FacilityAssetType.facilityTask, CrudAssetPermission.save)
      .doOnNext(task -> require(Set.of("open", "claimed", "executing", "rectify").contains(task.getState()), "invalid_state"))
      .flatMapMany(directory::taskAssignees);
  }

  public Mono<Boolean> canClaim(String id) {
    return read(id).flatMap(task -> task.getParentTaskId() == null ? directory.canClaim(task) : Mono.just(false));
  }

  public Mono<List<TaskEntity>> devices(String id, String groupId, String deviceId) {
    return executions.devices(id, groupId, deviceId);
  }

  /** 扫码目录可查询设备明细，但不改变任务列表、工单池和统计的整项粒度。 */
  public Mono<PagerResult<TaskEntity>> related(QueryParamEntity query) {
    return scope.query(query, FacilityAssetType.facilityTask, "id").flatMap(tasks::queryPager);
  }

  @Transactional
  public Mono<TaskEntity> action(
    String id,
    String action,
    TaskCommand command
  ) {
    return records
      .read(tasks, id, FacilityAssetType.facilityTask, CrudAssetPermission.save)
      .flatMap(task -> executions.before(task, action).thenReturn(task))
      .flatMap(task ->
        scope.current().flatMap(c ->
          directory.employee(c.userId()).then(
            Mono.defer(() -> {
              boolean submit = "submit".equals(action);
              // Replayed offline submissions are accepted only for the original actor and the same idempotency key.
              if (
                submit &&
                command.getSubmissionId() != null &&
                command
                  .getSubmissionId()
                  .equals(
                    task.getSubmissionId()
                  ) &&
                c
                  .userId()
                  .equals(
                    task.getAssigneeId()
                  )
              ) {
                require(!Set.of("closed", "cancelled").contains(task.getState()), "invalid_state");
                return directory.taskAssignee(task, c.userId()).thenReturn(task);
              }
              require(
                Objects.equals(command.getVersion(), task.getVersion()),
                "conflict"
              );
              String from = task.getState();
              long v = task.getVersion();
              Mono<Void> additional = Mono.empty();
              switch (action) {
                case "claim" -> {
                  require("open".equals(from), "invalid_state");
                  task.setAssigneeId(c.userId());
                  task.setState("claimed");
                  task.setAssigneeName(c.userName());
                }
                case "transfer" -> {
                  require(
                    Set.of("open", "claimed", "executing", "rectify").contains(
                      from
                    ),
                    "invalid_state"
                  );
                  require(
                    command.getUserId() != null &&
                      !command.getUserId().isBlank(),
                    "employee_required"
                  );
                  additional = directory.taskAssignee(task, command.getUserId()).then(directory.employeeInfo(command.getUserId())
                    .doOnNext(person -> task.setAssigneeName(person.name())).then());
                  require(!Objects.equals(command.getUserId(), task.getAssigneeId()), "same_assignee");
                  task.setAssigneeId(command.getUserId());
                  task.setState("claimed");
                  if (FacilityTaskExecutions.aggregate(task)) task.setSubmittedDeviceCount(0);
                  task.setResults(List.of());
                  task.setFollowResults(List.of());
                  task.setReviewComment(null);
                  task.setSubmissionId(null);
                  task.setFollowSubmissionId(null);
                }
                case "start" -> {
                  require(
                    c.userId().equals(task.getAssigneeId()) &&
                      Set.of("claimed", "rectify").contains(from),
                    "not_assignee"
                  );
                  task.setState("executing");
                }
                case "submit" -> {
                  require(
                    c.userId().equals(task.getAssigneeId()) &&
                      Set.of("claimed", "executing", "rectify").contains(from),
                    "not_assignee"
                  );
                  require(
                    command.getSubmissionId() != null &&
                      !command.getSubmissionId().isBlank(),
                    "submission_required"
                  );
                  FacilityRules.results(
                    task.getStandardSnapshot(),
                    command.getResults()
                  );
                  task.setResults(command.getResults());
                  task.setSubmissionId(command.getSubmissionId());
                  task.setState("review");
                }
                case "approve" -> {
                  require(
                    Set.of("review", "external-submitted").contains(from) &&
                      c.userId().equals(task.getReviewerId()),
                    "not_reviewer"
                  );
                  task.setState("completed");
                  task.setCompletedTime(System.currentTimeMillis());
                }
                case "reject" -> {
                  require(
                    Set.of("review", "external-submitted").contains(from) &&
                      c.userId().equals(task.getReviewerId()) &&
                      command.getComment() != null &&
                      !command.getComment().isBlank(),
                    "not_reviewer"
                  );
                  task.setState("rectify");
                  if (FacilityTaskExecutions.aggregate(task)) task.setSubmittedDeviceCount(0);
                  task.setSubmissionId(null);
                  task.setFollowSubmissionId(null);
                  task.setReviewComment(command.getComment());
                }
                case "close" -> {
                  require(!Set.of("completed", "cancelled", "closed").contains(from), "invalid_state");
                  task.setState("closed");
                  task.setCompletedTime(System.currentTimeMillis());
                }
                case "cancel" -> {
                  require(
                    !Set.of("completed", "cancelled", "closed").contains(from),
                    "invalid_state"
                  );
                  task.setState("cancelled");
                }
                default -> require(false, "invalid_action");
              }
              if (submit) additional = additional.then(
                files.validate(
                  command
                    .getResults()
                    .stream()
                    .filter(r -> r.getAttachments() != null)
                    .flatMap(r -> r.getAttachments().stream())
                    .toList(), task.getProjectId()
                )
              );
              if (Set.of("claim", "start", "submit").contains(action)) additional = additional.then(
                directory.taskAssignee(task, c.userId())
              );
              return additional
                .then(
                  records.update(
                    tasks,
                    task,
                    v,
                    java.util.stream.Stream.of(
                      "submissionId",
                      "followSubmissionId",
                      "reviewComment"
                    )
                      .filter(field ->
                        switch (field) {
                          case "submissionId" -> task.getSubmissionId() == null;
                          case "followSubmissionId" -> task.getFollowSubmissionId() == null;
                          default -> task.getReviewComment() == null;
                        }
                      )
                      .toArray(String[]::new)
                  )
                )
                .flatMap(saved ->
                  records
                    .log(
                      saved,
                      "task",
                      action,
                      from,
                      saved.getState(),
                      command.getComment(),
                      List.of()
                    )
                    .then(executions.after(saved, action))
                );
            })
          )
        )
      );
  }

}
