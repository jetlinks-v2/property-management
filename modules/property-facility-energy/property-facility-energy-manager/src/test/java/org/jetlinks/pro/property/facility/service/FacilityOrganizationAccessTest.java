package org.jetlinks.pro.property.facility.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.*;
import java.util.stream.Stream;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.authorization.*;
import org.hswebframework.web.authorization.simple.*;
import org.jetlinks.pro.assets.*;
import org.jetlinks.pro.assets.impl.*;
import org.jetlinks.pro.assets.impl.ctx.LocalAssetAccessesHolderContext;
import org.jetlinks.pro.assets.impl.supports.OrgIncludeChildrenAssetsAccessSupport;
import org.jetlinks.pro.assets.impl.terms.DimensionAssetsTerm;
import org.jetlinks.pro.assets.scope.*;
import org.jetlinks.pro.authorize.*;
import org.jetlinks.pro.property.facility.asset.*;
import org.junit.jupiter.api.Test;

/** 真实策略注册与维度条件：多应用维度不得使设施查询依赖本地组织闭包表。 */
class FacilityOrganizationAccessTest {

  private final AssetManager manager = mock(AssetManager.class);
  private final OrgIncludeChildrenAssetsAccessSupport platform =
    new OrgIncludeChildrenAssetsAccessSupport(manager);
  private final FacilityOrganizationAccessSupport facility =
    new FacilityOrganizationAccessSupport(manager);

  private SimpleAuthentication authentication(boolean manyDimensions) {
    var auth = new SimpleAuthentication();
    var user = new SimpleUser();
    user.setId("worker");
    auth.setUser(user);
    var dims = new ArrayList<Dimension>();
    dims.add(
      SimpleDimension.of(
        "project",
        "Project",
        OrgDimensionType.org,
        Map.of("direct", true)
      )
    );
    dims.add(
      SimpleDimension.of(
        "child",
        "Child",
        OrgDimensionType.org,
        Map.of("direct", false)
      )
    );
    dims.add(
      SimpleDimension.of(
        "ancestor",
        "Ancestor",
        OrgDimensionType.parentOrg,
        Map.of()
      )
    );
    var accesses = Stream.concat(
      Arrays.stream(FacilityAssetType.values()).map(AssetType::getId),
      Stream.of("device")
    )
      .map(type ->
        Map.of(
          "assetType",
          type,
          "accesses",
          List.of(Map.of("supportId", "org-include-children")),
          "options",
          Map.of("scopeStrategy", "IGNORE")
        )
      )
      .toList();
    dims.add(
      SimpleDimension.of(
        "role",
        "Role",
        DefaultDimensionType.role,
        Map.of(
          "dataAccess",
          accesses,
          AssetScopeSources.OPTION_KEY,
          Map.of("business_application", List.of("app"))
        )
      )
    );
    dims.add(
      SimpleDimension.of(
        "app",
        "App",
        BusinessApplicationDimension.INSTANCE,
        Map.of(AssetScopeSources.POLICY_OPTION_KEY, Map.of("*", "IGNORE"))
      )
    );
    if (manyDimensions) {
      dims.add(
        SimpleDimension.of(
          "another-app",
          "Other",
          BusinessApplicationDimension.INSTANCE,
          Map.of()
        )
      );
      dims.add(
        SimpleDimension.of(
          "another-role",
          "Other role",
          DefaultDimensionType.role,
          Map.of()
        )
      );
      dims.add(
        SimpleDimension.of(
          "third-role",
          "Third role",
          DefaultDimensionType.role,
          Map.of()
        )
      );
    }
    auth.setDimensions(dims);
    return auth;
  }

  private Stream<Term> flatten(List<Term> terms) {
    return terms
      .stream()
      .flatMap(t -> Stream.concat(Stream.of(t), flatten(t.getTerms())));
  }

  private DimensionAssetsTerm dimensionTerm(QueryParamEntity query) {
    return flatten(query.getTerms())
      .filter(t -> "dim-assets".equals(t.getTermType()))
      .map(DimensionAssetsTerm::of)
      .findFirst()
      .orElseThrow();
  }

  @Test
  void platformOptimizationDependsOnTotalAuthenticationDimensions() {
    var q = new QueryParamEntity();
    platform
      .injectConditional0(
        q.toQuery().nest(),
        authentication(true),
        "facilityDevice",
        "id"
      )
      .block();
    assertEquals(Boolean.TRUE, dimensionTerm(q).getClosure());
  }

  @Test
  void registeredFacilityPolicyKeepsSameAuthorizedOrganizationsWithManyRoles() {
    var provider = new DimensionAssetsHolderProvider(
      manager,
      mock(AssetBindManager.class),
      List.of(facility, platform),
      new LocalAssetAccessesHolderContext()
    );
    var registration = new FixedAssetsHolderProvider(provider);
    try {
      for (boolean many : List.of(false, true)) {
        var activation = new AssetScopeResolverManager()
          .resolve(
            AssetScope.of("business_application", "app"),
            authentication(many)
          )
          .block();
        var holder = AssetsHolder.fromAuth(
          activation.authentication(),
          activation.context()
        ).block();
        for (var type : FacilityAssetType.values()) {
          var q = holder
            .injectQuery(new QueryParamEntity(), type.getId(), "id")
            .contextWrite(activation.replace())
            .block();
          var term = dimensionTerm(q);
          assertNotEquals(Boolean.TRUE, term.getClosure());
          assertEquals(
            Set.of("project", "child"),
            new HashSet<>(term.toTargetMapping().get("org"))
          );
        }
      }
    } finally {
      registration.dispose();
    }
  }

  @Test
  void delegatesOtherAssetsToPlatformStrategy() {
    var q = new QueryParamEntity();
    facility
      .injectConditional0(
        q.toQuery().nest(),
        authentication(true),
        "device",
        "id"
      )
      .block();
    assertEquals(Boolean.TRUE, dimensionTerm(q).getClosure());
  }
}
