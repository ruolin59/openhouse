package com.linkedin.openhouse.internal.catalog.view;

import static com.linkedin.openhouse.internal.catalog.view.ViewTestFixtures.DB;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * Typed list and typed drop over a key space that genuinely holds both kinds of entity.
 *
 * <p>Views and tables share one House Table key space, so "typed" only means something if a mixed
 * population is present. Every earlier list/drop test either mocked an already-filtered page or
 * seeded views only, which cannot distinguish a typed route from an untyped one.
 */
public class OpenHouseInternalViewRepositoryMixedEntityTest {

  private ViewRepositoryHarness harness;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    harness = new ViewRepositoryHarness(tempDir);
    harness.getHouseTableRepository().seed(row("view_a", "VIEW"));
    harness.getHouseTableRepository().seed(row("view_b", "VIEW"));
    harness.getHouseTableRepository().seed(row("table_a", "TABLE"));
    harness.getHouseTableRepository().seed(row("legacy_a", null));
  }

  private static HouseTable row(String id, String entityType) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(id)
        .tableLocation("/loc/" + id + "/00001-a.metadata.json")
        .tableVersion("INITIAL_VERSION")
        .storageType(ViewTestFixtures.LOCAL_STORAGE_TYPE)
        .entityType(entityType)
        .build();
  }

  @Test
  void listReturnsOnlyViewsFromAMixedKeySpace() {
    Page<ViewPointer> page = harness.getViewRepository().listViews(DB, PageRequest.of(0, 10));

    List<String> ids =
        page.getContent().stream()
            .map(ViewPointer::getViewId)
            .sorted()
            .collect(Collectors.toList());
    Assertions.assertEquals(java.util.Arrays.asList("view_a", "view_b"), ids);
    Assertions.assertEquals(2L, page.getTotalElements());
    Assertions.assertEquals(1, page.getTotalPages());
  }

  @Test
  void listPaginatesOverViewsOnlyAndNeverParsesMetadata() {
    Page<ViewPointer> firstPage = harness.getViewRepository().listViews(DB, PageRequest.of(0, 1));
    Assertions.assertEquals(1, firstPage.getContent().size());
    Assertions.assertEquals(2L, firstPage.getTotalElements());
    Assertions.assertEquals(2, firstPage.getTotalPages());

    Page<ViewPointer> secondPage = harness.getViewRepository().listViews(DB, PageRequest.of(1, 1));
    Assertions.assertEquals(1, secondPage.getContent().size());
    Assertions.assertNotEquals(
        firstPage.getContent().get(0).getViewId(), secondPage.getContent().get(0).getViewId());

    Assertions.assertTrue(
        harness.events().stream().noneMatch(event -> event.startsWith("codec.")),
        "listing must never open a metadata file: " + harness.events());
  }

  @Test
  void droppingAViewLeavesTableAndLegacyRowsUntouched() {
    Assertions.assertTrue(harness.getViewRepository().dropView(DB, "view_a"));

    Assertions.assertFalse(harness.getHouseTableRepository().peek(DB, "view_a").isPresent());
    Assertions.assertTrue(harness.getHouseTableRepository().peek(DB, "view_b").isPresent());
    Assertions.assertEquals(
        row("table_a", "TABLE"), harness.getHouseTableRepository().peek(DB, "table_a").get());
    Assertions.assertEquals(
        row("legacy_a", null), harness.getHouseTableRepository().peek(DB, "legacy_a").get());
  }

  /** A table is not a view; the typed drop must decline rather than delete the wrong entity. */
  @Test
  void droppingATableThroughTheViewPathReportsFalseAndDeletesNothing() {
    Assertions.assertFalse(harness.getViewRepository().dropView(DB, "table_a"));

    Assertions.assertEquals(
        row("table_a", "TABLE"), harness.getHouseTableRepository().peek(DB, "table_a").get());
  }

  /** A legacy row has no discriminator and means TABLE, so it is equally out of reach. */
  @Test
  void droppingALegacyRowThroughTheViewPathReportsFalseAndDeletesNothing() {
    Assertions.assertFalse(harness.getViewRepository().dropView(DB, "legacy_a"));

    Assertions.assertEquals(
        row("legacy_a", null), harness.getHouseTableRepository().peek(DB, "legacy_a").get());
  }

  /** Loading a table through the view path is a miss, not a mis-typed success. */
  @Test
  void loadingATableThroughTheViewPathIsNoSuchView() {
    Assertions.assertThrows(
        org.apache.iceberg.exceptions.NoSuchViewException.class,
        () -> harness.getViewRepository().loadView(DB, "table_a"));
    Assertions.assertThrows(
        org.apache.iceberg.exceptions.NoSuchViewException.class,
        () -> harness.getViewRepository().loadView(DB, "legacy_a"));
  }
}
