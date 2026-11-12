package org.jetlinks.pro.property.decoration.service;

import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.property.decoration.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DecorationNotificationBoundaryTest {
    private DecorationScope.Context context(){
        var auth=new SimpleAuthentication();var user=new SimpleUser();user.setId("person");auth.setUser(user);
        auth.setDimensions(List.of(SimpleDimension.of("review","复核",SimpleDimensionType.of("role","角色"),Map.of())));
        return new DecorationScope.Context("tenant","app","person","人员",auth);
    }
    private boolean matches(List<Term> terms,Map<String,Object> row){
        boolean result=true;
        for(int i=0;i<terms.size();i++){
            var term=terms.get(i);Object actual=row.get(term.getColumn());
            boolean next=!term.getTerms().isEmpty()?matches(term.getTerms(),row):switch(term.getTermType()){
                case "eq" -> Objects.equals(actual,term.getValue());
                case "in" -> ((Collection<?>)term.getValue()).contains(actual);
                case "isnull" -> actual==null;
                default -> throw new AssertionError(term.getTermType());
            };
            result=i==0?next:term.getType()==Term.Type.or?result||next:result&&next;
        }return result;
    }
    @Test void arbitraryOrFiltersCannotEscapeRecipientSideAndRole(){
        var query=QueryParamEntity.newQuery().where("userId","foreign").or("event","event").getParam();
        DecorationNotificationService.restrictRecipient(query,context(),true);
        var row=new HashMap<String,Object>();row.put("userId","person");row.put("event","event");row.put("recipientSide","staff");row.put("roleId","review");
        assertTrue(matches(query.getTerms(),row));row.put("userId","foreign");assertFalse(matches(query.getTerms(),row));
        row.put("userId","person");row.put("recipientSide","owner");assertFalse(matches(query.getTerms(),row));
        row.put("recipientSide","staff");row.put("roleId","removed");assertFalse(matches(query.getTerms(),row));
        row.remove("roleId");assertTrue(matches(query.getTerms(),row));
        var owner=new QueryParamEntity();DecorationNotificationService.restrictRecipient(owner,context(),false);
        row.remove("recipientSide");assertTrue(matches(owner.getTerms(),row));row.put("recipientSide","staff");assertFalse(matches(owner.getTerms(),row));
    }
    @SuppressWarnings("unchecked")
    private Mono<DecorationTaskEntity> current(DecorationNotificationService service,DecorationNotificationEntity notice){
        return ReflectionTestUtils.invokeMethod(service,"currentTask",notice);
    }
    @Test void supersededTaskVersionRoleAssigneeAndBoundaryCancelOldEmployeeNotice(){
        var tasks=mock(DecorationTaskRepository.class);var service=new DecorationNotificationService(null,null,null,null,tasks,null);
        var notice=new DecorationNotificationEntity();notice.setTaskId("task");notice.setTaskVersion(2L);notice.setDecorationId("d");notice.setTenantId("tenant");notice.setApplicationId("app");notice.setProjectId("project");notice.setUserId("person");
        var task=new DecorationTaskEntity();task.setId("task");task.setVersion(2L);task.setDecorationId("d");task.setTenantId("tenant");task.setApplicationId("app");task.setProjectId("project");task.setAssigneeId("person");task.setStatus("pending");
        when(tasks.findById("task")).thenReturn(Mono.just(task));assertNotNull(current(service,notice).block());
        task.setAssigneeId("other");assertNull(current(service,notice).block());task.setAssigneeId("person");
        task.setVersion(3L);assertNull(current(service,notice).block());task.setVersion(2L);
        task.setApplicationId("other");assertNull(current(service,notice).block());task.setApplicationId("app");
        notice.setRoleId("review");task.setDetails(new org.jetlinks.pro.property.decoration.model.DecorationModels.TaskDetails());task.getDetails().setReviewRoleId("review");task.setStatus("waiting_review");assertNotNull(current(service,notice).block());
        task.getDetails().setReviewRoleId("removed");assertNull(current(service,notice).block());
        task.getDetails().setReviewRoleId("review");task.setStatus("rectified");assertNull(current(service,notice).block());
    }
}
