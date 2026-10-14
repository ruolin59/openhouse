package com.linkedin.openhouse.tables.repository.impl;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableCallerException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import com.linkedin.openhouse.internal.catalog.view.ViewCommitEngine;
import com.linkedin.openhouse.internal.catalog.view.ViewMetadataCodec;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import com.linkedin.openhouse.tables.model.ViewDto;
import com.linkedin.openhouse.tables.model.ViewModelConstants;
import com.linkedin.openhouse.tables.repository.ViewCommitOutcome;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class OpenHouseInternalViewRepositoryImplTest {

  private static final String CAPTURED_POINTER =
      "file:/warehouse/my_database/my_view/metadata/00012-captured.metadata.json";
  private static final String CREATED_RESULT_UUID = "created-view-uuid";
  private static final String COMMITTED_POINTER =
      "file:/warehouse/my_database/my_view/metadata/00013-committed.metadata.json";

  @Test
  public void findByIdUsesHtsPointerAndReadsOnlyPointerMetadataFields() {
    HouseTable viewRow = capturedViewRow();
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(viewRow));
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    FileIOManager fileIOManager = Mockito.mock(FileIOManager.class);
    FileIO fileIO = Mockito.mock(FileIO.class);
    InputFile inputFile = Mockito.mock(InputFile.class);
    ViewMetadataCodec metadataCodec = Mockito.mock(ViewMetadataCodec.class);
    ViewMetadata metadata = Mockito.mock(ViewMetadata.class);
    when(fileIOManager.getFileIO(StorageType.LOCAL)).thenReturn(fileIO);
    when(fileIO.newInputFile(CAPTURED_POINTER)).thenReturn(inputFile);
    when(metadataCodec.read(inputFile)).thenReturn(metadata);
    when(metadata.properties())
        .thenReturn(
            Map.of(
                getCanonicalFieldName("tableCreator"),
                ViewModelConstants.VIEW_CREATOR,
                getCanonicalFieldName("lastModifiedTime"),
                String.valueOf(ViewModelConstants.LAST_MODIFIED_TIME)));
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(
            houseTableRepository,
            engine,
            fileIOManager,
            metadataCodec,
            new StorageType(),
            Mockito.mock(StorageSelector.class));

    ViewDto result =
        repository.findById(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);

    verify(houseTableRepository, times(1)).findViewById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never()).findEntityById(any(HouseTablePrimaryKey.class));
    Mockito.verifyNoInteractions(engine);
    verify(metadataCodec).read(inputFile);
    org.junit.jupiter.api.Assertions.assertEquals(ViewModelConstants.VIEW_ID, result.getViewId());
    org.junit.jupiter.api.Assertions.assertEquals(CAPTURED_POINTER, result.getMetadataLocation());
    org.junit.jupiter.api.Assertions.assertEquals(
        ViewModelConstants.VIEW_CREATOR, result.getViewCreator());
    org.junit.jupiter.api.Assertions.assertEquals(
        ViewModelConstants.LAST_MODIFIED_TIME, result.getLastModifiedTime());
    org.junit.jupiter.api.Assertions.assertNull(result.getSchema());
    org.junit.jupiter.api.Assertions.assertNull(result.getRepresentations());
    org.junit.jupiter.api.Assertions.assertNull(result.getViewProperties());
  }

  @Test
  public void findByIdAndPrepareDeleteHideAbsentOrSameNameTableBehindTypedViewLookup() {
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.empty());
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, engine, Mockito.mock(StorageSelector.class));

    org.junit.jupiter.api.Assertions.assertThrows(
        com.linkedin.openhouse.tables.exception.ViewApiException.class,
        () -> repository.findById(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID));
    PreparedViewOperation prepared =
        repository.prepareDelete(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);

    verify(houseTableRepository, times(2)).findViewById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never()).findEntityById(any(HouseTablePrimaryKey.class));
    Mockito.verifyNoInteractions(engine);
    org.junit.jupiter.api.Assertions.assertFalse(prepared.getViewBaseRow().isPresent());
  }

  @Test
  public void prepareDeleteUsesTypedViewLookupAndCarriesCapturedPointer() {
    HouseTable viewRow = capturedViewRow();
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(viewRow));
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, engine, Mockito.mock(StorageSelector.class));

    PreparedViewOperation prepared =
        repository.prepareDelete(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);

    verify(houseTableRepository, times(1)).findViewById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never()).findEntityById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never()).deleteViewById(any(HouseTablePrimaryKey.class));
    Mockito.verifyNoInteractions(engine);
    assertSame(viewRow, prepared.getViewBaseRow().get());
    org.junit.jupiter.api.Assertions.assertEquals(
        CAPTURED_POINTER, prepared.getViewBaseRow().get().getTableLocation());
  }

  @Test
  public void searchViewsUsesDirectHtsPointerPageAndPreservesContinuationMetadata() {
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    Pageable requested = PageRequest.of(0, 2, Sort.by("tableId"));
    when(houseTableRepository.findAllViewsByDatabaseId(
            eq(ViewModelConstants.DATABASE_ID), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(
                Collections.singletonList(
                    HouseTable.builder()
                        .databaseId(ViewModelConstants.DATABASE_ID)
                        .tableId(ViewModelConstants.VIEW_ID)
                        .tableLocation(CAPTURED_POINTER)
                        .storageType("local")
                        .entityType("VIEW")
                        .build()),
                requested,
                5));
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, engine, Mockito.mock(StorageSelector.class));

    Page<ViewDto> results = repository.searchViews(ViewModelConstants.DATABASE_ID, requested);

    ArgumentCaptor<Pageable> forwarded = ArgumentCaptor.forClass(Pageable.class);
    verify(houseTableRepository)
        .findAllViewsByDatabaseId(eq(ViewModelConstants.DATABASE_ID), forwarded.capture());
    assertEquals(requested, forwarded.getValue());
    Mockito.verifyNoInteractions(engine);
    assertEquals(1, results.getContent().size());
    assertEquals(ViewModelConstants.VIEW_ID, results.getContent().get(0).getViewId());
    assertEquals(ViewModelConstants.DATABASE_ID, results.getContent().get(0).getDatabaseId());
    assertEquals(0, results.getNumber());
    assertEquals(2, results.getSize());
    assertTrue(
        results.hasNext(),
        "A short source page with remaining rows must stay nonterminal for the cursor adapter.");
  }

  @Test
  public void searchViewsKeepsEmptyNonterminalAndTerminalPagesDistinct() {
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    Pageable emptyNonterminal = PageRequest.of(1, 2, Sort.by("tableId"));
    Pageable terminal = PageRequest.of(2, 2, Sort.by("tableId"));
    when(houseTableRepository.findAllViewsByDatabaseId(
            ViewModelConstants.DATABASE_ID, emptyNonterminal))
        .thenReturn(new PageImpl<>(Collections.emptyList(), emptyNonterminal, 6));
    when(houseTableRepository.findAllViewsByDatabaseId(ViewModelConstants.DATABASE_ID, terminal))
        .thenReturn(new PageImpl<>(Collections.emptyList(), terminal, 4));
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, engine, Mockito.mock(StorageSelector.class));

    Page<ViewDto> empty = repository.searchViews(ViewModelConstants.DATABASE_ID, emptyNonterminal);
    Page<ViewDto> last = repository.searchViews(ViewModelConstants.DATABASE_ID, terminal);

    assertTrue(empty.getContent().isEmpty());
    assertEquals(1, empty.getNumber());
    assertTrue(empty.hasNext(), "An empty page is not proof of the end of the listing.");
    assertTrue(last.getContent().isEmpty());
    assertFalse(last.hasNext());
    Mockito.verifyNoInteractions(engine);
  }

  @Test
  public void prepareWriteCapturesTableOccupantNeutrallyWithoutAllocationOrClassification() {
    HouseTable tableOccupant =
        HouseTable.builder()
            .databaseId(ViewModelConstants.DATABASE_ID)
            .tableId(ViewModelConstants.VIEW_ID)
            .tableLocation(CAPTURED_POINTER)
            .storageType("local")
            .entityType("TABLE")
            .build();
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.findEntityById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(tableOccupant));
    StorageSelector storageSelector = Mockito.mock(StorageSelector.class);
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, Mockito.mock(ViewCommitEngine.class), storageSelector);

    PreparedViewOperation prepared =
        repository.prepareWrite(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);

    assertSame(tableOccupant, prepared.getOccupantRow().get());
    assertFalse(
        prepared.getViewBaseRow().isPresent(),
        "A TABLE occupant is captured but not revealed as a view before service authorization.");
    verify(storageSelector, never()).selectStorage(any(), any());
  }

  @Test
  public void commitReplaceUsesTheSingleCapturedCasSnapshotWithoutRefreshingHts() {
    HouseTable captured =
        HouseTable.builder()
            .databaseId(ViewModelConstants.DATABASE_ID)
            .tableId(ViewModelConstants.VIEW_ID)
            .tableLocation(CAPTURED_POINTER)
            .tableUUID("view-uuid")
            .storageType("local")
            .entityType("VIEW")
            .build();
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.findEntityById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(captured))
        .thenThrow(new AssertionError("commit must not refresh the prepared row"));
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    when(engine.commit(any(ViewCommitIntent.class))).thenReturn(committedResult());
    StorageSelector storageSelector = Mockito.mock(StorageSelector.class);
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, engine, storageSelector);

    PreparedViewOperation prepared =
        repository.prepareWrite(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);
    ViewCommitOutcome result =
        repository.commitReplace(ViewModelConstants.fullyPopulatedRequest(), prepared, "alice");

    ArgumentCaptor<ViewCommitIntent> intent = ArgumentCaptor.forClass(ViewCommitIntent.class);
    verify(engine, times(1)).commit(intent.capture());
    Mockito.verifyNoMoreInteractions(engine);
    verify(houseTableRepository, times(1)).findEntityById(any(HouseTablePrimaryKey.class));
    assertSame(captured, prepared.getViewBaseRow().get());
    assertSame(captured, intent.getValue().getBaseRow());
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.FALSE, intent.getValue().getIsCreate());
    assertEquals(COMMITTED_POINTER, result.getDto().getMetadataLocation());
    assertEquals(ViewModelConstants.VIEW_CREATOR, result.getDto().getViewCreator());
    assertEquals("view-uuid", result.getCommittedViewUuid());
    assertFalse(result.isCreated());
    verify(storageSelector, never()).selectStorage(any(), any());
  }

  @Test
  public void commitCreateAllocatesSelectedUuidRootAndPassesStorageTypeToEngine() {
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    StorageSelector storageSelector = Mockito.mock(StorageSelector.class);
    Storage storage = Mockito.mock(Storage.class);
    when(storage.getType()).thenReturn(StorageType.HDFS);
    when(storage.allocateTableLocation(
            eq(ViewModelConstants.DATABASE_ID),
            eq(ViewModelConstants.VIEW_ID),
            any(),
            eq("alice"),
            anyMap()))
        .thenAnswer(
            invocation ->
                "hdfs:/warehouse/"
                    + ViewModelConstants.DATABASE_ID
                    + "/"
                    + ViewModelConstants.VIEW_ID
                    + "-"
                    + invocation.getArgument(2));
    when(storageSelector.selectStorage(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID))
        .thenReturn(storage);
    ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    when(engine.commit(any(ViewCommitIntent.class))).thenReturn(createdResult());
    OpenHouseInternalViewRepositoryImpl repository =
        newRepository(houseTableRepository, engine, storageSelector);

    ViewCommitOutcome outcome =
        repository.commitCreate(
            ViewModelConstants.createRequestWithoutBaseVersion(),
            PreparedViewOperation.observedAbsence(),
            "alice");

    ArgumentCaptor<ViewCommitIntent> intent = ArgumentCaptor.forClass(ViewCommitIntent.class);
    ArgumentCaptor<String> allocatedUuid = ArgumentCaptor.forClass(String.class);
    verify(storageSelector)
        .selectStorage(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);
    verify(storage)
        .allocateTableLocation(
            eq(ViewModelConstants.DATABASE_ID),
            eq(ViewModelConstants.VIEW_ID),
            allocatedUuid.capture(),
            eq("alice"),
            anyMap());
    verify(engine, times(1)).commit(intent.capture());
    Mockito.verifyNoMoreInteractions(engine);
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, intent.getValue().getIsCreate());
    org.junit.jupiter.api.Assertions.assertEquals("hdfs", intent.getValue().getStorageType());
    UUID.fromString(intent.getValue().getViewUuid());
    org.junit.jupiter.api.Assertions.assertEquals(
        allocatedUuid.getValue(), intent.getValue().getViewUuid());
    org.junit.jupiter.api.Assertions.assertEquals(
        "hdfs:/warehouse/"
            + ViewModelConstants.DATABASE_ID
            + "/"
            + ViewModelConstants.VIEW_ID
            + "-"
            + intent.getValue().getViewUuid(),
        intent.getValue().getViewLocation());
    // The audit carrier is sourced from the engine's committed result, not reread or parsed.
    assertEquals(CREATED_RESULT_UUID, outcome.getCommittedViewUuid());
    assertTrue(outcome.isCreated());
    assertEquals(COMMITTED_POINTER, outcome.getDto().getMetadataLocation());
    assertEquals(ViewModelConstants.VIEW_ID, outcome.getDto().getViewId());
    assertEquals("alice", outcome.getDto().getViewCreator());
    verify(houseTableRepository, never()).findEntityById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never()).findViewById(any(HouseTablePrimaryKey.class));
  }

  /** A drop is one typed HTS DELETE by name: no engine, metadata read, storage or refresh. */
  @Test
  public void deleteIsOneDirectTypedHtsDeleteWithoutEngineMetadataOrStorageWork() {
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.deleteViewById(viewKey())).thenReturn(true);
    DeleteCollaborators collaborators = new DeleteCollaborators(houseTableRepository);

    collaborators.repository.deleteById(ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID);

    verify(houseTableRepository, times(1)).deleteViewById(viewKey());
    Mockito.verifyNoMoreInteractions(houseTableRepository);
    collaborators.assertNoEngineMetadataOrStorageWork();
  }

  /**
   * After the service's typed capture the name became absent or now holds a table. That is the
   * ordinary NO_SUCH_VIEW outcome from the one typed DELETE, with no refresh, not a server fault.
   */
  @Test
  public void deleteThatFindsNoViewAfterCaptureIsNoSuchViewWithOneAttemptAndNoRefresh() {
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.deleteViewById(viewKey())).thenReturn(false);
    DeleteCollaborators collaborators = new DeleteCollaborators(houseTableRepository);

    com.linkedin.openhouse.tables.exception.ViewApiException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            com.linkedin.openhouse.tables.exception.ViewApiException.class,
            () ->
                collaborators.repository.deleteById(
                    ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID));

    assertEquals(
        com.linkedin.openhouse.tables.exception.ViewErrorCode.NO_SUCH_VIEW, thrown.getErrorCode());
    assertEquals(org.springframework.http.HttpStatus.NOT_FOUND, thrown.getHttpStatus());
    org.junit.jupiter.api.Assertions.assertNull(
        thrown.getCause(), "An absent view is an ordinary outcome, not a wrapped failure.");
    verify(houseTableRepository, times(1)).deleteViewById(viewKey());
    Mockito.verifyNoMoreInteractions(houseTableRepository);
    collaborators.assertNoEngineMetadataOrStorageWork();
  }

  /** An unacknowledged DELETE may have landed: unknown state carrying the identical cause. */
  @Test
  public void ambiguousDeleteIsCommitStateUnknownWithTheIdenticalCauseAndOneAttempt() {
    HouseTableRepositoryStateUnknownException ambiguous =
        new HouseTableRepositoryStateUnknownException(
            "Cannot determine if HTS has persisted the delete", new RuntimeException("504"));
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.deleteViewById(viewKey())).thenThrow(ambiguous);
    DeleteCollaborators collaborators = new DeleteCollaborators(houseTableRepository);

    CommitStateUnknownException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            CommitStateUnknownException.class,
            () ->
                collaborators.repository.deleteById(
                    ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID));

    assertSame(ambiguous, thrown.getCause());
    verify(houseTableRepository, times(1)).deleteViewById(viewKey());
    Mockito.verifyNoMoreInteractions(houseTableRepository);
    collaborators.assertNoEngineMetadataOrStorageWork();
  }

  /**
   * Only repository-state-unknown from the DELETE becomes unknown state: a caller fault or an
   * unexpected failure is the same instance, unwrapped, after the one attempt.
   */
  @ParameterizedTest
  @MethodSource("nonAmbiguousDeleteFailures")
  public void nonAmbiguousDeleteFailuresPropagateUnchangedAfterOneAttempt(
      RuntimeException failure) {
    HouseTableRepository houseTableRepository = Mockito.mock(HouseTableRepository.class);
    when(houseTableRepository.deleteViewById(viewKey())).thenThrow(failure);
    DeleteCollaborators collaborators = new DeleteCollaborators(houseTableRepository);

    RuntimeException thrown =
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () ->
                collaborators.repository.deleteById(
                    ViewModelConstants.DATABASE_ID, ViewModelConstants.VIEW_ID));

    assertSame(failure, thrown);
    verify(houseTableRepository, times(1)).deleteViewById(viewKey());
    Mockito.verifyNoMoreInteractions(houseTableRepository);
    collaborators.assertNoEngineMetadataOrStorageWork();
  }

  private static Stream<Arguments> nonAmbiguousDeleteFailures() {
    return Stream.of(
        Arguments.of(
            new HouseTableCallerException(
                "[Client side failure]Error status code for HTS:400", new RuntimeException("400"))),
        Arguments.of(new IllegalStateException("unexpected delete failure")));
  }

  private static HouseTablePrimaryKey viewKey() {
    return HouseTablePrimaryKey.builder()
        .databaseId(ViewModelConstants.DATABASE_ID)
        .tableId(ViewModelConstants.VIEW_ID)
        .build();
  }

  /** The real bridge over mocks that a drop must never reach. */
  private static final class DeleteCollaborators {
    private final ViewCommitEngine engine = Mockito.mock(ViewCommitEngine.class);
    private final FileIOManager fileIOManager = Mockito.mock(FileIOManager.class);
    private final ViewMetadataCodec metadataCodec = Mockito.mock(ViewMetadataCodec.class);
    private final StorageSelector storageSelector = Mockito.mock(StorageSelector.class);
    private final OpenHouseInternalViewRepositoryImpl repository;

    private DeleteCollaborators(HouseTableRepository houseTableRepository) {
      repository =
          newRepository(
              houseTableRepository,
              engine,
              fileIOManager,
              metadataCodec,
              new StorageType(),
              storageSelector);
    }

    private void assertNoEngineMetadataOrStorageWork() {
      Mockito.verifyNoInteractions(engine, fileIOManager, metadataCodec, storageSelector);
    }
  }

  private static ViewCommitResult committedResult() {
    return ViewCommitResult.builder()
        .pointer(
            ViewPointer.builder()
                .databaseId(ViewModelConstants.DATABASE_ID)
                .viewId(ViewModelConstants.VIEW_ID)
                .metadataLocation(COMMITTED_POINTER)
                .storageType("local")
                .creationTime(ViewModelConstants.CREATION_TIME)
                .build())
        .viewUuid("view-uuid")
        .viewCreator(ViewModelConstants.VIEW_CREATOR)
        .lastModifiedTime(ViewModelConstants.LAST_MODIFIED_TIME)
        .created(false)
        .metadataChanged(true)
        .build();
  }

  private static HouseTable capturedViewRow() {
    return HouseTable.builder()
        .databaseId(ViewModelConstants.DATABASE_ID)
        .tableId(ViewModelConstants.VIEW_ID)
        .tableUUID("view-uuid")
        .tableLocation(CAPTURED_POINTER)
        .storageType("local")
        .entityType("VIEW")
        .build();
  }

  private static ViewCommitResult createdResult() {
    return ViewCommitResult.builder()
        .pointer(
            ViewPointer.builder()
                .databaseId(ViewModelConstants.DATABASE_ID)
                .viewId(ViewModelConstants.VIEW_ID)
                .metadataLocation(COMMITTED_POINTER)
                .storageType("local")
                .creationTime(ViewModelConstants.CREATION_TIME)
                .build())
        .viewUuid(CREATED_RESULT_UUID)
        .viewCreator("alice")
        .lastModifiedTime(ViewModelConstants.LAST_MODIFIED_TIME)
        .created(true)
        .metadataChanged(true)
        .build();
  }

  private static OpenHouseInternalViewRepositoryImpl newRepository(
      HouseTableRepository houseTableRepository,
      ViewCommitEngine engine,
      StorageSelector storageSelector) {
    return newRepository(
        houseTableRepository,
        engine,
        Mockito.mock(FileIOManager.class),
        Mockito.mock(ViewMetadataCodec.class),
        new StorageType(),
        storageSelector);
  }

  private static OpenHouseInternalViewRepositoryImpl newRepository(
      HouseTableRepository houseTableRepository,
      ViewCommitEngine engine,
      FileIOManager fileIOManager,
      ViewMetadataCodec metadataCodec,
      StorageType storageType,
      StorageSelector storageSelector) {
    return new OpenHouseInternalViewRepositoryImpl(
        houseTableRepository,
        engine,
        fileIOManager,
        metadataCodec,
        storageType,
        storageSelector,
        Mockito.mock(Storage.class));
  }
}
