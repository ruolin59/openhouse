package com.linkedin.openhouse.tablestest;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Typed view accessors over the H2-backed House Table stand-in, with a mixed key space.
 *
 * <p>These are the fixture's real in-memory semantics, not stubs, and they are the seam the
 * Iceberg-1.5 fixture will use to exercise commit/load/list/drop end to end. A view and a table
 * share one key space here exactly as they do in House Table, so a typed accessor that quietly
 * behaved like an untyped one would be visible.
 *
 * <p>The class lives in the 1.2 fixture's test sources, which the 1.5 fixture also compiles and
 * runs; every type named here exists in both Iceberg versions.
 */
public class HouseTablesH2ViewAccessorTest {

  private static final String DB = "viewdb";

  private ConfigurableApplicationContext context;
  private HouseTablesH2Repository repository;

  @BeforeEach
  public void setUp() {
    try {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.register();
    } catch (Error e) {
      org.apache.catalina.webresources.TomcatURLStreamHandlerFactory.disable();
    }
    SpringApplication application = new SpringApplication(SpringH2TestApplication.class);
    application.setDefaultProperties(Collections.singletonMap("server.port", "0"));
    context = application.run();
    repository = context.getBean(HouseTablesH2Repository.class);
    repository.deleteAll();

    repository.save(row("view_a", "VIEW"));
    repository.save(row("view_b", "VIEW"));
    repository.save(row("table_a", "TABLE"));
    repository.save(row("legacy_a", null));
  }

  @AfterEach
  public void tearDown() {
    if (context != null) {
      context.close();
    }
  }

  private static HouseTable row(String id, String entityType) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(id)
        .tableLocation("/loc/" + id + "/00001-a.metadata.json")
        .tableVersion("INITIAL_VERSION")
        .storageType("local")
        .entityType(entityType)
        .build();
  }

  private static HouseTablePrimaryKey key(String id) {
    return HouseTablePrimaryKey.builder().databaseId(DB).tableId(id).build();
  }

  @Test
  public void neutralLookupResolvesEitherKindOfEntity() {
    Assertions.assertEquals("VIEW", repository.findEntityById(key("view_a")).get().getEntityType());
    Assertions.assertEquals(
        "TABLE", repository.findEntityById(key("table_a")).get().getEntityType());
    Assertions.assertNull(repository.findEntityById(key("legacy_a")).get().getEntityType());
    Assertions.assertFalse(repository.findEntityById(key("absent")).isPresent());
  }

  @Test
  public void typedLookupResolvesViewsOnly() {
    Assertions.assertTrue(repository.findViewById(key("view_a")).isPresent());
    Assertions.assertFalse(repository.findViewById(key("table_a")).isPresent());
    Assertions.assertFalse(repository.findViewById(key("legacy_a")).isPresent());
  }

  @Test
  public void typedListReturnsOnlyViewsWithCorrectTotals() {
    Page<HouseTable> page = repository.findAllViewsByDatabaseId(DB, PageRequest.of(0, 10));

    List<String> ids =
        page.getContent().stream()
            .map(HouseTable::getTableId)
            .sorted()
            .collect(Collectors.toList());
    Assertions.assertEquals(java.util.Arrays.asList("view_a", "view_b"), ids);
    Assertions.assertEquals(2L, page.getTotalElements());
  }

  @Test
  public void typedListPaginatesOverViewsOnly() {
    Page<HouseTable> firstPage = repository.findAllViewsByDatabaseId(DB, PageRequest.of(0, 1));
    Page<HouseTable> secondPage = repository.findAllViewsByDatabaseId(DB, PageRequest.of(1, 1));

    Assertions.assertEquals(1, firstPage.getContent().size());
    Assertions.assertEquals(1, secondPage.getContent().size());
    Assertions.assertEquals(2L, firstPage.getTotalElements());
    Assertions.assertNotEquals(
        firstPage.getContent().get(0).getTableId(), secondPage.getContent().get(0).getTableId());
  }

  @Test
  public void savingAViewStampsTheEntityTypeAsTheRouteWould() {
    HouseTable saved =
        repository.saveView(
            HouseTable.builder()
                .databaseId(DB)
                .tableId("view_c")
                .tableLocation("/loc/view_c/00001-a.metadata.json")
                .tableVersion("INITIAL_VERSION")
                .storageType("local")
                .build());

    Assertions.assertEquals("VIEW", saved.getEntityType());
    Assertions.assertEquals("VIEW", repository.findViewById(key("view_c")).get().getEntityType());
  }

  @Test
  public void typedDeleteRemovesTheViewAndLeavesEveryOtherRowIntact() {
    // Snapshot copies, so the comparison is against detached values rather than whatever the
    // persistence context happens to hand back afterwards.
    HouseTable tableBefore = repository.findEntityById(key("table_a")).get().toBuilder().build();
    HouseTable legacyBefore = repository.findEntityById(key("legacy_a")).get().toBuilder().build();
    HouseTable otherViewBefore = repository.findEntityById(key("view_b")).get().toBuilder().build();

    Assertions.assertTrue(repository.deleteViewById(key("view_a")));

    Assertions.assertFalse(repository.findEntityById(key("view_a")).isPresent());
    Assertions.assertEquals(
        tableBefore,
        repository.findEntityById(key("table_a")).get(),
        "a view delete must leave a table row byte-for-byte unchanged");
    Assertions.assertEquals(
        legacyBefore,
        repository.findEntityById(key("legacy_a")).get(),
        "a view delete must leave a legacy row byte-for-byte unchanged");
    Assertions.assertEquals(
        otherViewBefore,
        repository.findEntityById(key("view_b")).get(),
        "a view delete must leave sibling views byte-for-byte unchanged");
  }

  @Test
  public void typedDeleteDeclinesTableAndLegacyRows() {
    HouseTable tableBefore = repository.findEntityById(key("table_a")).get().toBuilder().build();
    HouseTable legacyBefore = repository.findEntityById(key("legacy_a")).get().toBuilder().build();

    Assertions.assertFalse(repository.deleteViewById(key("table_a")));
    Assertions.assertFalse(repository.deleteViewById(key("legacy_a")));
    Assertions.assertFalse(repository.deleteViewById(key("absent")));

    Assertions.assertEquals(tableBefore, repository.findEntityById(key("table_a")).get());
    Assertions.assertEquals(legacyBefore, repository.findEntityById(key("legacy_a")).get());
  }
}
