package org.jetlinks.pro.property.facility.command;

import java.util.List;
import org.jetlinks.core.command.AbstractCommand;
import reactor.core.publisher.Mono;

/**
 * Facility attachment association contract, provided only by the Cloud file owner.
 * Requires protected files in the caller's tenant/application and the record's project.
 * Authentication and asset context travel with the command; any missing or unauthorized file fails.
 */
public class AssertFacilityFilesCommand extends AbstractCommand<Mono<Boolean>, AssertFacilityFilesCommand> {
  public static final String SERVICE_ID = "propertyFacilityCloudService:files";

  @SuppressWarnings("unchecked")
  public List<String> getFileIds() {
    var ids = getOrNull("fileIds", List.class);
    return ids == null ? List.of() : ids;
  }

  public void setFileIds(List<String> ids) { with("fileIds", ids); }
  public String getProjectId() { return getOrNull("projectId", String.class); }
  public void setProjectId(String id) { with("projectId", id); }
}
