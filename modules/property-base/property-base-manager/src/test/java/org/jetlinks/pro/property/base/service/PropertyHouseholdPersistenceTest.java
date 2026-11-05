package org.jetlinks.pro.property.base.service;

import org.hswebframework.ezorm.rdb.mapping.ReactiveRepository;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.simple.*;
import org.hswebframework.web.authorization.exception.AccessDenyException;
import org.jetlinks.pro.assets.AssetsHolder;
import org.jetlinks.pro.property.base.command.*;
import org.jetlinks.pro.property.base.entity.*;
import org.jetlinks.pro.property.base.organization.*;
import org.jetlinks.pro.property.base.web.PropertyCustomerController;
import org.jetlinks.pro.property.base.web.PropertyHouseController;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.test.context.*;
import reactor.core.publisher.*;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 房屋命令使用真实档案与 AssetsHolder 入口，手机号相同也不能替代认证关系。 */
@SpringBootTest(classes=PropertyHouseholdPersistenceTest.Config.class,webEnvironment=SpringBootTest.WebEnvironment.NONE)
class PropertyHouseholdPersistenceTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){OrganizationPersistenceTest.database(r);}
    @Autowired PropertyHouseholdCommandSupport support;
    @Autowired PropertyDirectoryCommandSupport directory;
    @Autowired ReactiveRepository<PropertyCustomerEntity,String> customers;
    @Autowired ReactiveRepository<PropertyCustomerRelationshipEntity,String> relations;
    @Autowired ReactiveRepository<PropertyHouseEntity,String> houses;
    @Autowired ReactiveRepository<PropertyOrganizationEntity,String> organizations;
    @Autowired ReactiveRepository<PropertySpaceEntity,String> spaces;
    @Autowired ReactiveRepository<PropertySpaceBindingEntity,String> bindings;
    @BeforeEach void prepare(){
        customers.deleteById(customers.createQuery().fetch().map(PropertyCustomerEntity::getId)).block();
        relations.deleteById(relations.createQuery().fetch().map(PropertyCustomerRelationshipEntity::getId)).block();
        houses.deleteById(houses.createQuery().fetch().map(PropertyHouseEntity::getId)).block();
        spaces.deleteById(spaces.createQuery().fetch().map(PropertySpaceEntity::getId)).block();
        bindings.deleteById(bindings.createQuery().fetch().map(PropertySpaceBindingEntity::getId)).block();
        organizations.deleteById(organizations.createQuery().fetch().map(PropertyOrganizationEntity::getId)).block();
        reset(directory);var project=new PropertyOrganizationInfo();project.setId("project");
        when(directory.projects(any())).thenReturn(Flux.just(project));
        var org=new PropertyOrganizationEntity();org.setId("project");org.setName("项目");org.setVersion(0L);org.setDeleted(false);org.setOrganizationType(OrganizationType.project);organizations.insert(org).block();
        var root=new PropertySpaceBindingEntity();root.setId("root");root.setOrganizationId("project");root.setVersion(0L);root.setDeleted(false);bindings.insert(root).block();
        for(String id:List.of("house","denied")){
            var space=new PropertySpaceEntity();space.setId(id);space.setName(id);space.setFullName(id);space.setRootId("root");space.setLeaf(true);space.setDeleted(false);spaces.insert(space).block();
            var house=new PropertyHouseEntity();house.setId(id);house.setProjectId("project");house.setRootId("root");house.setHouseType("residential");house.setHouseState("delivered");house.setBillingArea(BigDecimal.TEN);house.setVersion(0L);house.setDeleted(false);houses.insert(house).block();
        }
        person("alice");person("bob");relationship("alice-live","alice","house",false,null,1L);relationship("bob-live","bob","house",false,null,1L);
        relationship("alice-ended","alice","house",false,2L,1L);relationship("alice-deleted","alice","house",true,null,1L);
        relationship("alice-future","alice","house",false,null,System.currentTimeMillis()+86400000);relationship("alice-denied","alice","denied",false,null,1L);
    }
    @Test void mineUsesUserIdAndOnlyActiveAuthorizedHouse(){
        var command=command(true);
        var result=support.query(command).contextWrite(access("alice",Set.of("house"))).collectList().block();
        assertEquals(1,result.size());assertEquals("alice-live",result.get(0).relationshipId());assertEquals("alice",result.get(0).userId());
        assertEquals(2,support.query(command(false)).contextWrite(access("alice",Set.of("house"))).count().block());
        assertEquals(0,support.query(command(true)).contextWrite(access("stranger",Set.of("house"))).count().block());
    }
    @Test void customerToHousesAndHouseToCustomersUseTheSameCurrentRelations() {
        var query=command(false); query.setCustomerId("alice");
        var homes=support.query(query).contextWrite(access("admin",Set.of("house","denied"))).collectList().block();
        assertEquals(Set.of("house","denied"),homes.stream().map(h->h.spaceId()).collect(java.util.stream.Collectors.toSet()));
        query.setSpaceId("house"); query.setCustomerId(null);
        assertEquals(Set.of("alice","bob"),support.query(query).contextWrite(access("admin",Set.of("house")))
            .map(h->h.customerId()).collect(java.util.stream.Collectors.toSet()).block());
        query.setCustomerId("alice"); assertEquals(1,support.query(query).contextWrite(access("admin",Set.of("house"))).count().block());
        query.setCustomerId("missing");assertEquals(0,support.query(query).contextWrite(access("admin",Set.of("house"))).count().block());
        query.setSpaceId("denied");
        StepVerifier.create(support.query(query).contextWrite(access("admin",Set.of("house")))).expectError(AccessDenyException.class).verify();
    }
    @Test void httpControllersPreserveProjectAndDirection() {
        var customer = new PropertyCustomerController(null, null, support);
        var house = new PropertyHouseController(null, null, support);
        assertEquals(Set.of("house", "denied"), customer.houses("project", "alice")
            .contextWrite(access("admin", Set.of("house", "denied")))
            .map(row -> row.spaceId()).collect(java.util.stream.Collectors.toSet()).block());
        assertEquals(Set.of("alice", "bob"), house.customers("project", "house")
            .contextWrite(access("admin", Set.of("house")))
            .map(row -> row.customerId()).collect(java.util.stream.Collectors.toSet()).block());
        StepVerifier.create(house.customers("foreign", "house")
            .contextWrite(access("admin", Set.of("house")))).expectError(AccessDenyException.class).verify();
    }
    @Test void unauthorizedProjectAndDirectoryFailuresDoNotFallback(){
        var query=command(false);query.setProjectId("foreign");
        StepVerifier.create(support.query(query).contextWrite(access("alice",Set.of("house")))).expectError(AccessDenyException.class).verify();
        when(directory.projects(any())).thenReturn(Flux.error(new IllegalStateException("directory unavailable")));
        StepVerifier.create(support.query(command(true)).contextWrite(access("alice",Set.of("house")))).expectErrorMessage("directory unavailable").verify();
    }
    private QueryPropertyHouseholdsCommand command(boolean mine){var c=new QueryPropertyHouseholdsCommand();c.setProjectId("project");c.setMine(mine);return c;}
    private void person(String id){var c=new PropertyCustomerEntity();c.setId(id);c.setProjectId("project");c.setUserId(id);c.setCustomerType("person");c.setName("同名");c.setTelephone("13800000000");c.setVersion(0L);c.setDeleted(false);customers.insert(c).block();}
    private void relationship(String id,String customer,String space,boolean deleted,Long end,Long start){var r=new PropertyCustomerRelationshipEntity();r.setId(id);r.setProjectId("project");r.setCustomerId(customer);r.setSpaceId(space);r.setNature("owner");r.setVersion(0L);r.setDeleted(deleted);r.setStartTime(start);r.setEndTime(end);relations.insert(r).block();}
    private Context access(String userId,Set<String> allowed){var holder=mock(AssetsHolder.class);
        when(holder.assertPermission(anyString(),anyCollection(),any())).thenAnswer(call->{Collection<?> ids=call.getArgument(1);return "organization".equals(call.getArgument(0))||allowed.containsAll(ids)?Mono.empty():Mono.error(new AccessDenyException());});
        when(holder.filter(any(Flux.class),any(),any(Function.class),any())).thenAnswer(call->{Flux<PropertySpaceEntity> source=call.getArgument(0);return source.filter(row->allowed.contains(row.getId()));});
        var user=new SimpleUser();user.setId(userId);var auth=new SimpleAuthentication();auth.setUser(user);return Context.of(Authentication.class,auth,AssetsHolder.class,holder);}
    @Import({OrganizationPersistenceTest.Config.class,ArchiveScope.class,PropertyHouseholdCommandSupport.class})
    static class Config {@Bean PropertyDirectoryCommandSupport directory(){return mock(PropertyDirectoryCommandSupport.class);}}
}
