package com.linkedin.openhouse.internal.catalog.view;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;
import static com.linkedin.openhouse.internal.catalog.view.ViewTestFixtures.DB;
import static com.linkedin.openhouse.internal.catalog.view.ViewTestFixtures.VIEW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.view.model.SqlViewRepresentationIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.io.OutputFile;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewProperties;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Create and replace behavior of the view commit repository: collision classification, UUID
 * stability, Iceberg-owned version identity, no-op detection, dialect safety, and the exact
 * properties stamped into the metadata file.
 *
 * <p>All metadata assertions are golden round-trips: the file is written by the repository and read
 * back through the real Iceberg parser.
 */
public class OpenHouseInternalViewRepositoryCommitTest {

  private ViewRepositoryHarness harness;

  @BeforeEach
  void setUp(@TempDir Path tempDir) {
    harness = new ViewRepositoryHarness(tempDir);
  }

  /* -------------------------------------------------------------------------
   * Create collision classification. The occupancy read is advisory, but it is
   * what turns a collision into an actionable error, so every occupant shape has
   * to be classified and none may leave a side effect behind.
   * ---------------------------------------------------------------------- */

  @Test
  void createCollidingWithAnExistingViewReportsViewAlreadyExists() {
    harness
        .getHouseTableRepository()
        .seed(ViewTestFixtures.viewRow("/existing/00001-a.metadata.json"));

    Assertions.assertThrows(
        AlreadyExistsException.class,
        () -> harness.getViewRepository().commit(ViewTestFixtures.createIntent()));

    assertCreateCollisionLeftNoTrace();
  }

  @Test
  void createCollidingWithATableReportsNameOccupiedCarryingTheOccupantType() {
    harness
        .getHouseTableRepository()
        .seed(ViewTestFixtures.tableRow("/existing/00001-a.metadata.json"));

    ViewNameOccupiedException thrown =
        Assertions.assertThrows(
            ViewNameOccupiedException.class,
            () -> harness.getViewRepository().commit(ViewTestFixtures.createIntent()));

    Assertions.assertEquals(ViewTestFixtures.ENTITY_TYPE_TABLE, thrown.getOccupantEntityType());
    Assertions.assertEquals(DB, thrown.getDatabaseId());
    Assertions.assertEquals(VIEW, thrown.getViewId());
    assertCreateCollisionLeftNoTrace();
  }

  /**
   * A row predating the discriminator column carries no entity type and means TABLE, per the House
   * Table {@code EntityType} contract. It must classify as a table collision, not as a free name.
   */
  @Test
  void createCollidingWithALegacyRowReportsNameOccupiedAsTable() {
    harness
        .getHouseTableRepository()
        .seed(ViewTestFixtures.legacyRow("/existing/00001-a.metadata.json"));

    ViewNameOccupiedException thrown =
        Assertions.assertThrows(
            ViewNameOccupiedException.class,
            () -> harness.getViewRepository().commit(ViewTestFixtures.createIntent()));

    Assertions.assertEquals(ViewTestFixtures.ENTITY_TYPE_TABLE, thrown.getOccupantEntityType());
    assertCreateCollisionLeftNoTrace();
  }

  /** An entity type this build does not know about must fail closed, preserving the raw value. */
  @Test
  void createCollidingWithAnUnknownEntityTypeFailsClosed() {
    harness
        .getHouseTableRepository()
        .seed(
            ViewTestFixtures.row(
                ViewTestFixtures.ENTITY_TYPE_UNKNOWN, "/existing/00001-a.metadata.json"));

    ViewNameOccupiedException thrown =
        Assertions.assertThrows(
            ViewNameOccupiedException.class,
            () -> harness.getViewRepository().commit(ViewTestFixtures.createIntent()));

    Assertions.assertEquals(ViewTestFixtures.ENTITY_TYPE_UNKNOWN, thrown.getOccupantEntityType());
    assertCreateCollisionLeftNoTrace();
  }

  /** A non-canonical spelling is not a recognized type, so the name is occupied by something. */
  @Test
  void createCollidingWithANonCanonicalDiscriminatorFailsClosed() {
    harness
        .getHouseTableRepository()
        .seed(ViewTestFixtures.row("view", "/existing/00001-a.metadata.json"));

    ViewNameOccupiedException thrown =
        Assertions.assertThrows(
            ViewNameOccupiedException.class,
            () -> harness.getViewRepository().commit(ViewTestFixtures.createIntent()));

    Assertions.assertEquals("view", thrown.getOccupantEntityType());
    assertCreateCollisionLeftNoTrace();
  }

  @Test
  void createOnAFreeNameProceedsAndPublishesExactlyOnce() {
    ViewCommitResult result = harness.getViewRepository().commit(ViewTestFixtures.createIntent());

    Assertions.assertTrue(result.isCreated());
    Assertions.assertTrue(result.isMetadataChanged());
    Assertions.assertEquals(1, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertEquals(1, harness.getHouseTableRepository().getFindEntityByIdCalls());
    Assertions.assertEquals(1, harness.metadataFiles().size());
    Optional<HouseTable> pointer = harness.getHouseTableRepository().peek(DB, VIEW);
    Assertions.assertTrue(pointer.isPresent());
    Assertions.assertEquals(
        result.getPointer().getMetadataLocation(), pointer.get().getTableLocation());
  }

  private void assertCreateCollisionLeftNoTrace() {
    Assertions.assertEquals(0, harness.getHouseTableRepository().getSaveViewCalls());
    verify(harness.getCodec(), never()).write(any(ViewMetadata.class), any(OutputFile.class));
    verify(harness.getStorageSelector(), never()).selectStorage(anyString(), anyString());
    verify(harness.getStorage(), never())
        .allocateTableLocation(anyString(), anyString(), anyString(), anyString(), any());
    Assertions.assertTrue(harness.metadataFiles().isEmpty());
  }

  /* -------------------------------------------------------------------------
   * UUID identity.
   * ---------------------------------------------------------------------- */

  /**
   * The Iceberg view UUID and the OpenHouse table UUID are the same identity; the allocated
   * directory embeds it, and a replace must not mint a new one.
   */
  @Test
  void uuidIsAssignedOnceEmbeddedInTheLocationAndPreservedAcrossReplace() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    ViewMetadata createdMetadata = harness.readMetadata(created.getPointer().getMetadataLocation());

    Assertions.assertEquals(createdMetadata.uuid(), created.getViewUuid());
    Assertions.assertEquals(
        createdMetadata.uuid(),
        createdMetadata.properties().get(CatalogConstants.OPENHOUSE_UUID_KEY));
    Assertions.assertTrue(
        createdMetadata.location().endsWith(VIEW + "-" + createdMetadata.uuid()),
        "allocated location must embed the UUID: " + createdMetadata.location());

    ViewCommitResult replaced =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .schema(ViewTestFixtures.schemaV2())
                    .representations(
                        Collections.singletonList(
                            ViewTestFixtures.sql(
                                ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT)))
                    .baseViewVersion(created.getPointer().getMetadataLocation())
                    .build());
    ViewMetadata replacedMetadata =
        harness.readMetadata(replaced.getPointer().getMetadataLocation());

    Assertions.assertEquals(created.getViewUuid(), replaced.getViewUuid());
    Assertions.assertEquals(createdMetadata.uuid(), replacedMetadata.uuid());
    Assertions.assertEquals(createdMetadata.location(), replacedMetadata.location());
    Assertions.assertEquals(
        replacedMetadata.uuid(),
        replacedMetadata.properties().get(CatalogConstants.OPENHOUSE_UUID_KEY));
  }

  /** Server-owned properties are authoritative: a caller cannot smuggle one in. */
  @Test
  void callerSuppliedReservedPropertyIsRejectedBeforeAnythingIsWritten() {
    Map<String, String> hostile = new LinkedHashMap<>();
    hostile.put(CatalogConstants.OPENHOUSE_UUID_KEY, "00000000-0000-0000-0000-000000000000");

    Assertions.assertThrows(
        BadRequestException.class,
        () ->
            harness
                .getViewRepository()
                .commit(ViewTestFixtures.baseIntent().viewProperties(hostile).build()));

    Assertions.assertEquals(0, harness.getHouseTableRepository().getSaveViewCalls());
    verify(harness.getCodec(), never()).write(any(ViewMetadata.class), any(OutputFile.class));
    verify(harness.getStorage(), never())
        .allocateTableLocation(anyString(), anyString(), anyString(), anyString(), any());
    Assertions.assertTrue(harness.metadataFiles().isEmpty());
  }

  /* -------------------------------------------------------------------------
   * Version identity is Iceberg's, not OpenHouse's.
   * ---------------------------------------------------------------------- */

  /**
   * Every step below is a materially different definition, so Iceberg cannot de-duplicate it. The
   * assertions read the resulting metadata only: no submitted candidate id and no {@code max+1}
   * arithmetic of our own appears anywhere.
   */
  @Test
  void versionIdsAndHistoryAreAssignedByIcebergAcrossMateriallyDifferentDefinitions() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    ViewMetadata afterCreate = harness.readMetadata(created.getPointer().getMetadataLocation());
    Assertions.assertEquals(1, afterCreate.versions().size());
    Assertions.assertEquals(1, afterCreate.history().size());
    Assertions.assertEquals(
        afterCreate.currentVersionId(), afterCreate.history().get(0).versionId());

    ViewCommitResult second =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .schema(ViewTestFixtures.schemaV2())
                    .representations(
                        Collections.singletonList(
                            ViewTestFixtures.sql(
                                ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT)))
                    .baseViewVersion(created.getPointer().getMetadataLocation())
                    .build());
    ViewMetadata afterSecond = harness.readMetadata(second.getPointer().getMetadataLocation());
    Assertions.assertEquals(2, afterSecond.versions().size());
    Assertions.assertEquals(2, afterSecond.history().size());
    Assertions.assertNotEquals(afterCreate.currentVersionId(), afterSecond.currentVersionId());

    ViewCommitResult third =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .schema(ViewTestFixtures.schemaV2())
                    .representations(
                        Collections.singletonList(
                            ViewTestFixtures.sql(
                                ViewTestFixtures.SQL_V3, ViewTestFixtures.SPARK_DIALECT)))
                    .baseViewVersion(second.getPointer().getMetadataLocation())
                    .build());
    ViewMetadata afterThird = harness.readMetadata(third.getPointer().getMetadataLocation());
    Assertions.assertEquals(3, afterThird.versions().size());
    Assertions.assertEquals(3, afterThird.history().size());
    Assertions.assertEquals(
        afterThird.currentVersionId(),
        afterThird.history().get(afterThird.history().size() - 1).versionId());

    List<Integer> historyIds =
        Arrays.asList(
            afterThird.history().get(0).versionId(),
            afterThird.history().get(1).versionId(),
            afterThird.history().get(2).versionId());
    Assertions.assertEquals(historyIds.size(), historyIds.stream().distinct().count());
    Assertions.assertTrue(historyIds.contains(afterCreate.currentVersionId()));
    Assertions.assertTrue(historyIds.contains(afterSecond.currentVersionId()));
  }

  /* -------------------------------------------------------------------------
   * No-op detection.
   * ---------------------------------------------------------------------- */

  /**
   * Replacing a view with the definition it already has changes nothing observable, so it must not
   * write a metadata file, must not move the pointer, and must not bump last-modified time. Only
   * the candidate timestamp and summary differ between the two submissions.
   */
  @Test
  void identicalDefinitionReplaceIsANoOpThatWritesNothing() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    ViewMetadata afterCreate = harness.readMetadata(created.getPointer().getMetadataLocation());
    int filesAfterCreate = harness.metadataFiles().size();
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();
    HouseTable pointerAfterCreate = harness.getHouseTableRepository().peek(DB, VIEW).get();

    ViewCommitResult replayed =
        harness
            .getViewRepository()
            .commit(ViewTestFixtures.replaceIntent(created.getPointer().getMetadataLocation()));

    Assertions.assertFalse(replayed.isCreated());
    Assertions.assertFalse(replayed.isMetadataChanged());
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(), replayed.getPointer().getMetadataLocation());
    Assertions.assertEquals(created.getViewUuid(), replayed.getViewUuid());
    Assertions.assertEquals(created.getLastModifiedTime(), replayed.getLastModifiedTime());
    Assertions.assertEquals(filesAfterCreate, harness.metadataFiles().size());
    Assertions.assertEquals(savesAfterCreate, harness.getHouseTableRepository().getSaveViewCalls());

    HouseTable pointerAfterReplay = harness.getHouseTableRepository().peek(DB, VIEW).get();
    Assertions.assertEquals(
        pointerAfterCreate.getTableLocation(), pointerAfterReplay.getTableLocation());
    Assertions.assertEquals(
        pointerAfterCreate.getTableVersion(), pointerAfterReplay.getTableVersion());

    ViewMetadata unchanged = harness.readMetadata(replayed.getPointer().getMetadataLocation());
    Assertions.assertEquals(afterCreate.currentVersionId(), unchanged.currentVersionId());
    Assertions.assertEquals(afterCreate.versions().size(), unchanged.versions().size());
    Assertions.assertEquals(afterCreate.history().size(), unchanged.history().size());
  }

  /**
   * A property-only change is persisted state even when Iceberg legitimately reuses the current
   * version id, so it is a real commit and must write and publish.
   */
  @Test
  void propertyOnlyReplaceStillWritesAndPublishes() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();

    ViewCommitResult updated =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .viewProperties(ViewTestFixtures.userProperties("a", "2"))
                    .baseViewVersion(created.getPointer().getMetadataLocation())
                    .build());

    Assertions.assertFalse(updated.isCreated());
    Assertions.assertTrue(updated.isMetadataChanged());
    Assertions.assertNotEquals(
        created.getPointer().getMetadataLocation(), updated.getPointer().getMetadataLocation());
    Assertions.assertEquals(
        savesAfterCreate + 1, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertEquals(2, harness.metadataFiles().size());

    ViewMetadata metadata = harness.readMetadata(updated.getPointer().getMetadataLocation());
    Assertions.assertEquals("2", metadata.properties().get("a"));
  }

  /** User properties the caller omits survive; supplied ones win. */
  @Test
  void replacePreservesOmittedUserPropertiesAndMergesSuppliedOnes() {
    Map<String, String> initial = new LinkedHashMap<>();
    initial.put("a", "1");
    initial.put("keep", "yes");
    ViewCommitResult created =
        harness
            .getViewRepository()
            .commit(ViewTestFixtures.baseIntent().viewProperties(initial).build());

    ViewCommitResult updated =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .viewProperties(ViewTestFixtures.userProperties("a", "2"))
                    .baseViewVersion(created.getPointer().getMetadataLocation())
                    .build());

    Map<String, String> properties =
        harness.readMetadata(updated.getPointer().getMetadataLocation()).properties();
    Assertions.assertEquals("2", properties.get("a"));
    Assertions.assertEquals("yes", properties.get("keep"));
  }

  /* -------------------------------------------------------------------------
   * Bounding the no-op comparison.
   *
   * Iceberg 1.5.2.21 will not de-duplicate these for us: sameViewVersion compares
   * the whole summary map, and the repository stamps a differing summary, so every
   * resubmission looks new to Iceberg. The repository therefore owns a normalized
   * structural comparison, and each field below is part of it. One field changes
   * per test so an under-comparing implementation cannot hide behind another field.
   * ---------------------------------------------------------------------- */

  private static final List<SqlViewRepresentationIntent> BOTH_DIALECTS_V1 =
      ViewTestFixtures.sparkAndTrino(ViewTestFixtures.SQL_V1);

  private ViewCommitIntent changedReplaceOf(ViewCommitResult created) {
    return ViewTestFixtures.baseIntent()
        .schema(ViewTestFixtures.schemaV2())
        .representations(
            Collections.singletonList(
                ViewTestFixtures.sql(ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT)))
        .baseViewVersion(created.getPointer().getMetadataLocation())
        .build();
  }

  private ViewCommitResult createWithBothDialects() {
    return harness
        .getViewRepository()
        .commit(ViewTestFixtures.baseIntent().representations(BOTH_DIALECTS_V1).build());
  }

  /**
   * Runs a replace that differs from the created view in exactly one structural field and requires
   * it to be treated as a real change.
   */
  private void assertStructuralChangeIsNotANoOp(
      java.util.function.UnaryOperator<ViewCommitIntent.ViewCommitIntentBuilder> mutation,
      String changedField) {
    ViewCommitResult created = createWithBothDialects();
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();
    int filesAfterCreate = harness.metadataFiles().size();

    ViewCommitIntent intent =
        mutation
            .apply(
                ViewTestFixtures.baseIntent()
                    .representations(BOTH_DIALECTS_V1)
                    .baseViewVersion(created.getPointer().getMetadataLocation()))
            .build();

    ViewCommitResult result = harness.getViewRepository().commit(intent);

    Assertions.assertTrue(
        result.isMetadataChanged(), "a changed " + changedField + " is not a no-op");
    Assertions.assertFalse(result.isCreated());
    Assertions.assertNotEquals(
        created.getPointer().getMetadataLocation(),
        result.getPointer().getMetadataLocation(),
        "a changed " + changedField + " must move the pointer");
    Assertions.assertEquals(
        filesAfterCreate + 1,
        harness.metadataFiles().size(),
        "a changed " + changedField + " must write a metadata file");
    Assertions.assertEquals(
        savesAfterCreate + 1,
        harness.getHouseTableRepository().getSaveViewCalls(),
        "a changed " + changedField + " must publish");
  }

  @Test
  void aChangedSchemaIsNotANoOp() {
    assertStructuralChangeIsNotANoOp(
        builder -> builder.schema(ViewTestFixtures.schemaV2()), "schema");
  }

  @Test
  void changedSqlTextIsNotANoOp() {
    assertStructuralChangeIsNotANoOp(
        builder -> builder.representations(ViewTestFixtures.sparkAndTrino(ViewTestFixtures.SQL_V2)),
        "SQL text");
  }

  /** Only one representation's SQL changes, so a whole-set-only comparison would miss it. */
  @Test
  void changedSqlInASingleRepresentationIsNotANoOp() {
    assertStructuralChangeIsNotANoOp(
        builder ->
            builder.representations(
                Arrays.asList(
                    ViewTestFixtures.sql(ViewTestFixtures.SQL_V1, ViewTestFixtures.SPARK_DIALECT),
                    ViewTestFixtures.sql(ViewTestFixtures.SQL_V2, ViewTestFixtures.TRINO_DIALECT))),
        "SQL of one representation");
  }

  /** Adding a dialect is allowed and is a real change; only dropping one is rejected. */
  @Test
  void anAddedRepresentationDialectIsNotANoOp() {
    List<SqlViewRepresentationIntent> withPresto = new java.util.ArrayList<>(BOTH_DIALECTS_V1);
    withPresto.add(ViewTestFixtures.sql(ViewTestFixtures.SQL_V1, "presto"));
    assertStructuralChangeIsNotANoOp(
        builder -> builder.representations(withPresto), "representation set");
  }

  @Test
  void aChangedSourceDialectIsNotANoOp() {
    assertStructuralChangeIsNotANoOp(
        builder -> builder.sourceDialect(ViewTestFixtures.TRINO_DIALECT), "source dialect");
  }

  @Test
  void aChangedDefaultCatalogIsNotANoOp() {
    assertStructuralChangeIsNotANoOp(
        builder -> builder.defaultCatalog("other_catalog"), "default catalog");
  }

  @Test
  void aChangedDefaultNamespaceIsNotANoOp() {
    assertStructuralChangeIsNotANoOp(
        builder -> builder.defaultNamespace(Namespace.of("other_db")), "default namespace");
  }

  /**
   * Identifier fields are part of the schema but not of its struct, so a comparison that only looks
   * at columns reports "nothing changed" and drops the caller's edit on the floor.
   */
  @Test
  void aChangedIdentifierFieldSetIsNotANoOp() {
    Schema withoutIdentifier =
        new Schema(
            Arrays.asList(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get())),
            Collections.emptySet());
    Schema withIdentifier =
        new Schema(
            Arrays.asList(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.required(2, "name", Types.StringType.get())),
            Collections.singleton(1));
    Assertions.assertEquals(
        withoutIdentifier.asStruct(),
        withIdentifier.asStruct(),
        "the two schemas must differ only in identifier fields, or this test proves nothing");

    ViewCommitResult created =
        harness
            .getViewRepository()
            .commit(ViewTestFixtures.baseIntent().schema(withoutIdentifier).build());
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();
    int filesAfterCreate = harness.metadataFiles().size();

    ViewCommitResult updated =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .schema(withIdentifier)
                    .baseViewVersion(created.getPointer().getMetadataLocation())
                    .build());

    Assertions.assertTrue(updated.isMetadataChanged(), "an identifier-field change is a change");
    Assertions.assertEquals(filesAfterCreate + 1, harness.metadataFiles().size());
    Assertions.assertEquals(
        savesAfterCreate + 1, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertEquals(
        Collections.singleton(1),
        harness
            .readMetadata(updated.getPointer().getMetadataLocation())
            .schema()
            .identifierFieldIds());
  }

  /**
   * Reducing representations to a map keyed by dialect keeps only the last entry, so a submission
   * that repeats a dialect could compare equal to the stored definition and be answered as a no-op
   * — which would also skip the validation that rejects two queries for one dialect.
   */
  @Test
  void aDuplicateDialectSubmissionIsRejectedRatherThanTreatedAsANoOp() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();
    int filesAfterCreate = harness.metadataFiles().size();

    // The last entry alone equals the stored definition, so a lossy comparison would see no change.
    ViewCommitIntent duplicated =
        ViewTestFixtures.baseIntent()
            .representations(
                Arrays.asList(
                    ViewTestFixtures.sql(ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT),
                    ViewTestFixtures.sql(ViewTestFixtures.SQL_V1, ViewTestFixtures.SPARK_DIALECT)))
            .baseViewVersion(created.getPointer().getMetadataLocation())
            .build();

    Assertions.assertThrows(
        BadRequestException.class, () -> harness.getViewRepository().commit(duplicated));

    Assertions.assertEquals(filesAfterCreate, harness.metadataFiles().size());
    Assertions.assertEquals(savesAfterCreate, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(),
        harness.getHouseTableRepository().peek(DB, VIEW).get().getTableLocation());
  }

  /** Iceberg compares dialects case-insensitively, so the duplicate check has to as well. */
  @Test
  void aDuplicateDialectDifferingOnlyInCaseIsAlsoRejected() {
    Assertions.assertThrows(
        BadRequestException.class,
        () ->
            harness
                .getViewRepository()
                .commit(
                    ViewTestFixtures.baseIntent()
                        .representations(
                            Arrays.asList(
                                ViewTestFixtures.sql(
                                    ViewTestFixtures.SQL_V1, ViewTestFixtures.SPARK_DIALECT),
                                ViewTestFixtures.sql(ViewTestFixtures.SQL_V2, "SPARK")))
                        .build()));

    Assertions.assertEquals(0, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertTrue(harness.metadataFiles().isEmpty());
  }

  /**
   * A changed commit has to be observably newer than what it replaced. With the clock pinned, the
   * only thing that can make that true is the repository's own monotonic guard.
   */
  @Test
  void aChangedReplaceAdvancesLastModifiedEvenWhenTheClockDoesNot() {
    long fixedNow = 1_700_000_000_000L;
    OpenHouseInternalViewRepository pinnedClock =
        new OpenHouseInternalViewRepositoryImpl(
            harness.getHouseTableRepository(),
            harness.getFileIOManager(),
            harness.getRecordingCodec(),
            harness.getStorageSelector(),
            new StorageType(),
            harness.getHouseTableMapper()) {
          @Override
          protected long nowMillis() {
            return fixedNow;
          }
        };

    ViewCommitResult created = pinnedClock.commit(ViewTestFixtures.createIntent());
    Assertions.assertEquals(fixedNow, created.getLastModifiedTime());

    ViewCommitResult updated = pinnedClock.commit(changedReplaceOf(created));

    Assertions.assertTrue(updated.isMetadataChanged());
    Assertions.assertTrue(
        updated.getLastModifiedTime() > created.getLastModifiedTime(),
        "a changed replace must advance last-modified even inside one clock tick: "
            + updated.getLastModifiedTime()
            + " vs "
            + created.getLastModifiedTime());
    Assertions.assertEquals(
        updated.getLastModifiedTime(),
        Long.parseLong(
            harness
                .readMetadata(updated.getPointer().getMetadataLocation())
                .properties()
                .get(getCanonicalFieldName("lastModifiedTime"))),
        "the advanced value must be what was persisted");
    Assertions.assertEquals(
        fixedNow,
        Long.parseLong(
            harness
                .readMetadata(updated.getPointer().getMetadataLocation())
                .properties()
                .get(getCanonicalFieldName("creationTime"))),
        "creation time still belongs to the create");
  }

  /* -------------------------------------------------------------------------
   * Dialect safety.
   * ---------------------------------------------------------------------- */

  /**
   * The server stamps {@code replace.drop-dialect.allowed=false}, so a replacement that silently
   * drops a dialect an engine still reads is rejected by Iceberg itself, before any file is
   * written.
   */
  @Test
  void replaceDroppingAPreviouslyStoredDialectIsRejected() {
    ViewCommitResult created =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .representations(ViewTestFixtures.sparkAndTrino(ViewTestFixtures.SQL_V1))
                    .build());

    ViewMetadata createdMetadata = harness.readMetadata(created.getPointer().getMetadataLocation());
    Assertions.assertEquals(
        "false", createdMetadata.properties().get(ViewProperties.REPLACE_DROP_DIALECT_ALLOWED));
    int filesAfterCreate = harness.metadataFiles().size();
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();

    IllegalStateException thrown =
        Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                harness
                    .getViewRepository()
                    .commit(
                        ViewTestFixtures.baseIntent()
                            .representations(
                                Collections.singletonList(
                                    ViewTestFixtures.sql(
                                        ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT)))
                            .baseViewVersion(created.getPointer().getMetadataLocation())
                            .build()));
    // Pin the dialect-specific failure, so an unrelated repository ISE cannot satisfy this test.
    Assertions.assertTrue(
        thrown.getMessage() != null && thrown.getMessage().contains("view dialects"),
        "expected the dropped-dialect failure, got: " + thrown.getMessage());
    Assertions.assertTrue(
        thrown.getMessage().contains(ViewProperties.REPLACE_DROP_DIALECT_ALLOWED),
        "the failure must name the guard that rejected it: " + thrown.getMessage());

    Assertions.assertEquals(filesAfterCreate, harness.metadataFiles().size());
    Assertions.assertEquals(savesAfterCreate, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(),
        harness.getHouseTableRepository().peek(DB, VIEW).get().getTableLocation());
  }

  /** A caller cannot re-enable dialect dropping through view properties. */
  @Test
  void callerCannotOverrideTheDropDialectGuard() {
    Map<String, String> hostile = new HashMap<>();
    hostile.put(ViewProperties.REPLACE_DROP_DIALECT_ALLOWED, "true");

    Assertions.assertThrows(
        BadRequestException.class,
        () ->
            harness
                .getViewRepository()
                .commit(ViewTestFixtures.baseIntent().viewProperties(hostile).build()));

    Assertions.assertEquals(0, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertTrue(harness.metadataFiles().isEmpty());
  }

  /* -------------------------------------------------------------------------
   * Stamping golden round-trip.
   * ---------------------------------------------------------------------- */

  /**
   * The exact server-owned property set, read back through the real parser. The {@code
   * openhouse.table*} namespace is reused for views on purpose, because House Table stores an
   * entity-neutral pointer; entity type is never stamped into metadata, because House Table sets it
   * from the route the write arrived on.
   */
  @Test
  void createStampsInitialVersionAndReplaceStampsThePriorExactPath() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    ViewMetadata createdMetadata = harness.readMetadata(created.getPointer().getMetadataLocation());
    Map<String, String> createdProperties = createdMetadata.properties();

    Assertions.assertEquals(
        CatalogConstants.INITIAL_VERSION,
        createdProperties.get(getCanonicalFieldName("tableVersion")));
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(),
        createdProperties.get(getCanonicalFieldName("tableLocation")));
    Assertions.assertEquals(VIEW, createdProperties.get(CatalogConstants.OPENHOUSE_TABLEID_KEY));
    Assertions.assertEquals(DB, createdProperties.get(CatalogConstants.OPENHOUSE_DATABASEID_KEY));
    Assertions.assertEquals(
        ViewTestFixtures.CREATOR, createdProperties.get(getCanonicalFieldName("tableCreator")));
    Assertions.assertNotNull(createdProperties.get(getCanonicalFieldName("creationTime")));
    Assertions.assertNotNull(createdProperties.get(getCanonicalFieldName("lastModifiedTime")));
    Assertions.assertEquals(
        createdProperties.get(getCanonicalFieldName("creationTime")),
        createdProperties.get(getCanonicalFieldName("lastModifiedTime")));
    Assertions.assertFalse(
        createdProperties.containsKey(getCanonicalFieldName("entityType")),
        "entity type belongs to the House Table row and its route, never to metadata");
    Assertions.assertEquals("1", createdProperties.get("a"));

    Assertions.assertEquals(
        ViewTestFixtures.SPARK_DIALECT,
        createdMetadata
            .currentVersion()
            .summary()
            .get(ViewTestFixtures.SOURCE_DIALECT_SUMMARY_KEY));

    // The resolution context and schema the caller supplied must survive verbatim: they are part of
    // what makes two submissions structurally equal, so a lossy round trip would corrupt no-op
    // detection as well as the view itself.
    Assertions.assertEquals("openhouse", createdMetadata.currentVersion().defaultCatalog());
    Assertions.assertEquals(
        Collections.singletonList(DB),
        Arrays.asList(createdMetadata.currentVersion().defaultNamespace().levels()));
    Assertions.assertEquals(
        ViewTestFixtures.schemaV1().asStruct(),
        createdMetadata.schema().asStruct(),
        "the supplied schema must round trip through the metadata file");
    Assertions.assertEquals(
        createdMetadata.currentVersion().schemaId(), createdMetadata.currentSchemaId().intValue());

    List<SQLViewRepresentation> representations =
        createdMetadata.currentVersion().representations().stream()
            .map(SQLViewRepresentation.class::cast)
            .collect(java.util.stream.Collectors.toList());
    Assertions.assertEquals(1, representations.size());
    Assertions.assertEquals(ViewTestFixtures.SQL_V1, representations.get(0).sql());
    Assertions.assertEquals(ViewTestFixtures.SPARK_DIALECT, representations.get(0).dialect());

    Assertions.assertEquals(
        Long.parseLong(createdProperties.get(getCanonicalFieldName("lastModifiedTime"))),
        created.getLastModifiedTime(),
        "the returned last-modified time must be the one persisted in metadata");

    ViewCommitResult replaced =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .schema(ViewTestFixtures.schemaV2())
                    .representations(
                        Collections.singletonList(
                            ViewTestFixtures.sql(
                                ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT)))
                    .baseViewVersion(created.getPointer().getMetadataLocation())
                    .build());
    Map<String, String> replacedProperties =
        harness.readMetadata(replaced.getPointer().getMetadataLocation()).properties();

    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(),
        replacedProperties.get(getCanonicalFieldName("tableVersion")));
    Assertions.assertEquals(
        replaced.getPointer().getMetadataLocation(),
        replacedProperties.get(getCanonicalFieldName("tableLocation")));
    Assertions.assertEquals(
        createdProperties.get(getCanonicalFieldName("creationTime")),
        replacedProperties.get(getCanonicalFieldName("creationTime")));
    Assertions.assertNotEquals(
        createdProperties.get(getCanonicalFieldName("lastModifiedTime")),
        replacedProperties.get(getCanonicalFieldName("lastModifiedTime")));
  }

  /**
   * The published token is the whole compare-and-swap contract: {@code INITIAL_VERSION} claims a
   * free name, and a replace must send back the exact path the caller captured. A re-read, a
   * default, or a re-derived value would silently turn a conditional write into a blind one.
   */
  @Test
  void publishedPointerRowCarriesTheNewPathAndTheCapturedBaseAsExpectedVersion() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());

    HouseTable afterCreate = harness.getHouseTableRepository().peek(DB, VIEW).get();
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(), afterCreate.getTableLocation());
    Assertions.assertEquals(
        CatalogConstants.INITIAL_VERSION,
        afterCreate.getTableVersion(),
        "a create must claim the name with INITIAL_VERSION");
    Assertions.assertEquals(ViewTestFixtures.LOCAL_STORAGE_TYPE, afterCreate.getStorageType());
    Assertions.assertEquals(DB, afterCreate.getDatabaseId());
    Assertions.assertEquals(VIEW, afterCreate.getTableId());

    String capturedBase = created.getPointer().getMetadataLocation();
    harness.clearEvents();

    ViewCommitResult replaced = harness.getViewRepository().commit(changedReplaceOf(created));

    HouseTable afterReplace = harness.getHouseTableRepository().peek(DB, VIEW).get();
    Assertions.assertEquals(
        replaced.getPointer().getMetadataLocation(), afterReplace.getTableLocation());
    Assertions.assertEquals(
        capturedBase,
        afterReplace.getTableVersion(),
        "a replace must send back exactly the path the caller captured");

    List<String> events = harness.events();
    Assertions.assertEquals(
        1,
        countStartingWith(events, InMemoryViewHouseTableRepository.FIND_VIEW),
        "a changed replace reads the pointer exactly once: " + events);
    Assertions.assertEquals(
        1,
        countStartingWith(events, InMemoryViewHouseTableRepository.SAVE_VIEW),
        "a changed replace publishes exactly once: " + events);

    int writeAt = indexOfStartingWith(events, RecordingViewMetadataCodec.WRITE);
    int saveAt = indexOfStartingWith(events, InMemoryViewHouseTableRepository.SAVE_VIEW);
    int readAt = indexOfStartingWith(events, InMemoryViewHouseTableRepository.FIND_VIEW);
    Assertions.assertTrue(writeAt >= 0 && saveAt >= 0 && readAt >= 0, "events: " + events);
    Assertions.assertTrue(
        writeAt < saveAt, "the immutable file must be written before publishing: " + events);
    Assertions.assertTrue(
        readAt < writeAt, "the base is captured before the file is built: " + events);
    Assertions.assertEquals(
        readAt,
        lastIndexOfStartingWith(events, InMemoryViewHouseTableRepository.FIND_VIEW),
        "nothing may be re-read between capturing the base and swapping: " + events);
    Assertions.assertTrue(
        events.get(saveAt).contains("expected=" + capturedBase),
        "the swap must carry the captured base as its token: " + events.get(saveAt));
    Assertions.assertEquals(
        saveAt, events.size() - 1, "the swap is the last thing that happens: " + events);
  }

  private static int countStartingWith(List<String> events, String prefix) {
    return (int) events.stream().filter(event -> event.startsWith(prefix)).count();
  }

  private static int indexOfStartingWith(List<String> events, String prefix) {
    for (int i = 0; i < events.size(); i++) {
      if (events.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  private static int lastIndexOfStartingWith(List<String> events, String prefix) {
    for (int i = events.size() - 1; i >= 0; i--) {
      if (events.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }

  /** The create publish carries INITIAL_VERSION on the wire-facing pointer, and writes first. */
  @Test
  void createPublishesInitialVersionAfterWritingItsFile() {
    harness.getViewRepository().commit(ViewTestFixtures.createIntent());

    List<String> events = harness.events();
    int probeAt = indexOfStartingWith(events, InMemoryViewHouseTableRepository.FIND_ENTITY);
    int writeAt = indexOfStartingWith(events, RecordingViewMetadataCodec.WRITE);
    int saveAt = indexOfStartingWith(events, InMemoryViewHouseTableRepository.SAVE_VIEW);
    Assertions.assertTrue(probeAt >= 0 && writeAt >= 0 && saveAt >= 0, "events: " + events);
    Assertions.assertTrue(probeAt < writeAt, "occupancy is checked first: " + events);
    Assertions.assertTrue(writeAt < saveAt, "write before publish: " + events);
    Assertions.assertTrue(
        events.get(saveAt).contains("expected=" + CatalogConstants.INITIAL_VERSION),
        "events: " + events);
    Assertions.assertEquals(
        1, countStartingWith(events, InMemoryViewHouseTableRepository.SAVE_VIEW));
    Assertions.assertEquals(
        1, countStartingWith(events, InMemoryViewHouseTableRepository.FIND_ENTITY));
  }

  /**
   * Every supplied dialect is persisted, in full, alongside the user properties. A partial write
   * here would break engines that read the missing dialect and would also make two structurally
   * different definitions compare equal.
   */
  @Test
  void everySuppliedRepresentationAndUserPropertyIsPersisted() {
    Map<String, String> userProperties = new LinkedHashMap<>();
    userProperties.put("owner", "team-a");
    userProperties.put("comment", "a view");

    ViewCommitResult created =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .representations(ViewTestFixtures.sparkAndTrino(ViewTestFixtures.SQL_V1))
                    .viewProperties(userProperties)
                    .build());

    ViewMetadata metadata = harness.readMetadata(created.getPointer().getMetadataLocation());
    Map<String, String> byDialect = new HashMap<>();
    metadata
        .currentVersion()
        .representations()
        .forEach(
            representation -> {
              SQLViewRepresentation sql = (SQLViewRepresentation) representation;
              byDialect.put(sql.dialect(), sql.sql());
            });
    Assertions.assertEquals(2, byDialect.size(), "both dialects must be persisted: " + byDialect);
    Assertions.assertEquals(ViewTestFixtures.SQL_V1, byDialect.get(ViewTestFixtures.SPARK_DIALECT));
    Assertions.assertEquals(ViewTestFixtures.SQL_V1, byDialect.get(ViewTestFixtures.TRINO_DIALECT));

    Assertions.assertEquals("team-a", metadata.properties().get("owner"));
    Assertions.assertEquals("a view", metadata.properties().get("comment"));
  }

  /** What the caller committed is exactly what a subsequent load reports. */
  @Test
  void aCommittedViewLoadsBackWithTheSameDefinition() {
    ViewCommitResult created =
        harness
            .getViewRepository()
            .commit(
                ViewTestFixtures.baseIntent()
                    .representations(ViewTestFixtures.sparkAndTrino(ViewTestFixtures.SQL_V1))
                    .build());
    ViewMetadata metadata = harness.readMetadata(created.getPointer().getMetadataLocation());

    com.linkedin.openhouse.internal.catalog.view.model.LoadedView loaded =
        harness.newRepositoryInstance().loadView(DB, VIEW);

    Assertions.assertEquals(created.getViewUuid(), loaded.getViewUuid());
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(), loaded.getPointer().getMetadataLocation());
    Assertions.assertEquals(metadata.currentVersionId(), loaded.getCurrentVersionId());
    Assertions.assertEquals(ViewTestFixtures.schemaV1().asStruct(), loaded.getSchema().asStruct());
    Assertions.assertEquals("openhouse", loaded.getDefaultCatalog());
    Assertions.assertEquals(Namespace.of(DB), loaded.getDefaultNamespace());
    Assertions.assertEquals(ViewTestFixtures.SPARK_DIALECT, loaded.getSourceDialect());

    // The complete dialect-to-SQL mapping, not just its size: returning one dialect twice and
    // dropping the other would otherwise pass, and would silently break every engine reading the
    // dropped dialect.
    Map<String, String> submitted = new LinkedHashMap<>();
    ViewTestFixtures.sparkAndTrino(ViewTestFixtures.SQL_V1)
        .forEach(
            representation -> submitted.put(representation.getDialect(), representation.getSql()));
    Map<String, String> loadedByDialect = new LinkedHashMap<>();
    loaded
        .getRepresentations()
        .forEach(
            representation ->
                Assertions.assertNull(
                    loadedByDialect.put(representation.getDialect(), representation.getSql()),
                    "a dialect must not be reported twice: " + representation.getDialect()));
    Assertions.assertEquals(submitted, loadedByDialect);

    Assertions.assertEquals(
        ViewTestFixtures.userProperties("a", "1").entrySet(),
        loaded.getProperties().entrySet().stream()
            .filter(entry -> !entry.getKey().startsWith("openhouse."))
            .filter(entry -> !entry.getKey().startsWith("replace."))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))
            .entrySet(),
        "the public model must carry exactly the user properties that were submitted");
    Assertions.assertEquals(created.getLastModifiedTime(), loaded.getLastModifiedTime());
  }

  /* -------------------------------------------------------------------------
   * Base-token handling on replace.
   * ---------------------------------------------------------------------- */

  /**
   * A replace based on a path that is no longer current is stale, and is rejected before any write.
   */
  @Test
  void replaceWithAStaleBaseTokenFailsBeforeWritingAnything() {
    ViewCommitResult created = harness.getViewRepository().commit(ViewTestFixtures.createIntent());
    int filesAfterCreate = harness.metadataFiles().size();
    int savesAfterCreate = harness.getHouseTableRepository().getSaveViewCalls();

    ViewCommitIntent stale =
        ViewTestFixtures.baseIntent()
            .schema(ViewTestFixtures.schemaV2())
            .representations(
                Collections.singletonList(
                    ViewTestFixtures.sql(ViewTestFixtures.SQL_V2, ViewTestFixtures.SPARK_DIALECT)))
            .baseViewVersion(created.getPointer().getMetadataLocation() + ".stale")
            .build();

    Assertions.assertThrows(
        CommitFailedException.class, () -> harness.getViewRepository().commit(stale));

    Assertions.assertEquals(filesAfterCreate, harness.metadataFiles().size());
    Assertions.assertEquals(savesAfterCreate, harness.getHouseTableRepository().getSaveViewCalls());
    verify(harness.getCodec(), times(1)).write(any(ViewMetadata.class), any(OutputFile.class));
    Assertions.assertEquals(
        created.getPointer().getMetadataLocation(),
        harness.getHouseTableRepository().peek(DB, VIEW).get().getTableLocation());
  }

  /** Replacing a view that is not there is a load failure, not an implicit create. */
  @Test
  void replaceOfAnAbsentViewNeverBecomesACreate() {
    Assertions.assertThrows(
        NoSuchViewException.class,
        () ->
            harness
                .getViewRepository()
                .commit(ViewTestFixtures.replaceIntent("/nowhere/00001-a.metadata.json")));

    Assertions.assertEquals(0, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertTrue(harness.metadataFiles().isEmpty());
  }

  /** A replace pointed at a key occupied by a table is not a view commit either. */
  @Test
  void replaceOfATablePointerIsRejectedAsNoSuchView() {
    harness
        .getHouseTableRepository()
        .seed(ViewTestFixtures.tableRow("/existing/00001-a.metadata.json"));

    Assertions.assertThrows(
        NoSuchViewException.class,
        () ->
            harness
                .getViewRepository()
                .commit(ViewTestFixtures.replaceIntent("/existing/00001-a.metadata.json")));

    Assertions.assertEquals(0, harness.getHouseTableRepository().getSaveViewCalls());
    Assertions.assertTrue(harness.metadataFiles().isEmpty());
  }
}
