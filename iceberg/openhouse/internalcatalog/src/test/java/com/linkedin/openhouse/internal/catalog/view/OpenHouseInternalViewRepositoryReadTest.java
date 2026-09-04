package com.linkedin.openhouse.internal.catalog.view;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;
import static com.linkedin.openhouse.internal.catalog.view.ViewTestFixtures.DB;
import static com.linkedin.openhouse.internal.catalog.view.ViewTestFixtures.LOCAL_STORAGE_TYPE;
import static com.linkedin.openhouse.internal.catalog.view.ViewTestFixtures.VIEW;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.view.model.LoadedView;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.exceptions.NotFoundException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.view.ViewMetadata;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Read-side behaviour: load probes, storage selection, listing, dropping, unsupported rename. Every
 * collaborator that could touch storage is a mock, so "this cost nothing" is asserted, not
 * inferred.
 */
public class OpenHouseInternalViewRepositoryReadTest {

  private static final String VIEW_BASE = "/tmp/openhouse/viewdb/v1-1111";
  private static final String METADATA_PATH = VIEW_BASE + "/00001-abcd.metadata.json";

  private HouseTableRepository houseTableRepository;
  private FileIOManager fileIOManager;
  private ViewMetadataCodec viewMetadataCodec;
  private StorageSelector storageSelector;
  private StorageType storageType;
  private HouseTableMapper houseTableMapper;
  private OpenHouseInternalViewRepository viewRepository;

  @BeforeEach
  void setUp() {
    houseTableRepository = mock(HouseTableRepository.class);
    fileIOManager = mock(FileIOManager.class);
    viewMetadataCodec = mock(ViewMetadataCodec.class);
    storageSelector = mock(StorageSelector.class);
    storageType = mock(StorageType.class);
    houseTableMapper = mock(HouseTableMapper.class);
    viewRepository =
        new OpenHouseInternalViewRepositoryImpl(
            houseTableRepository,
            fileIOManager,
            viewMetadataCodec,
            storageSelector,
            storageType,
            houseTableMapper);
  }

  /** The common case, so it must not cost a storage selection, a FileIO, or a parse. */
  @Test
  void loadViewOnAbsentPointerCostsOneTypedLookupAndNothingElse() {
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.empty());

    Assertions.assertThrows(NoSuchViewException.class, () -> viewRepository.loadView(DB, VIEW));

    verify(houseTableRepository, times(1))
        .findViewById(eq(HouseTablePrimaryKey.builder().databaseId(DB).tableId(VIEW).build()));
    verify(houseTableRepository, never()).findById(any(HouseTablePrimaryKey.class));
    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
    verifyNoInteractions(storageSelector);
  }

  /** The typed endpoint should never return a table; if it does, the answer is still no. */
  @Test
  void loadViewOnDefensiveTablePayloadCostsOneTypedLookupAndNothingElse() {
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(ViewTestFixtures.tableRow(METADATA_PATH)));

    Assertions.assertThrows(NoSuchViewException.class, () -> viewRepository.loadView(DB, VIEW));

    verify(houseTableRepository, times(1)).findViewById(any(HouseTablePrimaryKey.class));
    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
    verifyNoInteractions(storageSelector);
  }

  /** FileIO comes from the row's own storage, never the cluster-wide selector. */
  @Test
  void loadViewSelectsFileIoFromPointerRowStorageAndParsesExactlyThatPath() {
    HouseTable row = ViewTestFixtures.viewRow(METADATA_PATH);
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(row));
    when(storageType.fromString(LOCAL_STORAGE_TYPE)).thenReturn(StorageType.LOCAL);
    FileIO fileIO = mock(FileIO.class);
    when(fileIOManager.getFileIO(StorageType.LOCAL)).thenReturn(fileIO);
    InputFile inputFile = mock(InputFile.class);
    when(fileIO.newInputFile(METADATA_PATH)).thenReturn(inputFile);
    Map<String, String> persistedProperties = new LinkedHashMap<>();
    persistedProperties.put("user-key", "user-value");
    persistedProperties.put(getCanonicalFieldName("lastModifiedTime"), "1700000000000");
    ViewMetadata metadata =
        ViewMetadataTestUtil.metadata(
            VIEW_BASE, METADATA_PATH, ViewTestFixtures.schemaV1(), persistedProperties);
    when(viewMetadataCodec.read(inputFile)).thenReturn(metadata);

    LoadedView loaded = viewRepository.loadView(DB, VIEW);

    verify(fileIOManager, times(1)).getFileIO(StorageType.LOCAL);
    verify(fileIO, times(1)).newInputFile(METADATA_PATH);
    verify(viewMetadataCodec, times(1)).read(inputFile);
    verify(storageSelector, never()).selectStorage(anyString(), anyString());

    Assertions.assertEquals(METADATA_PATH, loaded.getPointer().getMetadataLocation());
    Assertions.assertEquals(LOCAL_STORAGE_TYPE, loaded.getPointer().getStorageType());
    Assertions.assertEquals(DB, loaded.getPointer().getDatabaseId());
    Assertions.assertEquals(VIEW, loaded.getPointer().getViewId());

    // The whole conversion, not a sample of it: these fields are what the later layer answers reads
    // with, and they are also what structural equality is computed over on a replace.
    Assertions.assertEquals(metadata.uuid(), loaded.getViewUuid());
    Assertions.assertEquals(metadata.currentVersionId(), loaded.getCurrentVersionId());
    Assertions.assertEquals(metadata.schema().asStruct(), loaded.getSchema().asStruct());
    Assertions.assertEquals(metadata.currentVersion().defaultCatalog(), loaded.getDefaultCatalog());
    Assertions.assertEquals(
        metadata.currentVersion().defaultNamespace(), loaded.getDefaultNamespace());
    Assertions.assertEquals(1, loaded.getRepresentations().size());
    Assertions.assertEquals(
        ViewTestFixtures.SPARK_DIALECT, loaded.getRepresentations().get(0).getDialect());
    Assertions.assertEquals(ViewTestFixtures.SQL_V1, loaded.getRepresentations().get(0).getSql());
    Assertions.assertEquals(ViewTestFixtures.SPARK_DIALECT, loaded.getSourceDialect());
    Assertions.assertEquals("user-value", loaded.getProperties().get("user-key"));
    Assertions.assertEquals(
        Long.parseLong(metadata.properties().get(getCanonicalFieldName("lastModifiedTime"))),
        loaded.getLastModifiedTime(),
        "last-modified must come from the parsed metadata, not from the clock");
  }

  /** Broken, not absent: collapsing this would let a later create overwrite a live pointer. */
  @Test
  void loadViewPropagatesCorruptMetadataInsteadOfReportingAbsence() {
    HouseTable row = ViewTestFixtures.viewRow(METADATA_PATH);
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(row));
    when(storageType.fromString(LOCAL_STORAGE_TYPE)).thenReturn(StorageType.LOCAL);
    FileIO fileIO = mock(FileIO.class);
    when(fileIOManager.getFileIO(StorageType.LOCAL)).thenReturn(fileIO);
    InputFile inputFile = mock(InputFile.class);
    when(fileIO.newInputFile(METADATA_PATH)).thenReturn(inputFile);
    when(viewMetadataCodec.read(inputFile))
        .thenThrow(new NotFoundException("metadata file is gone: %s", METADATA_PATH));

    Assertions.assertThrows(NotFoundException.class, () -> viewRepository.loadView(DB, VIEW));
  }

  /** Listing is a pointer-row operation: no metadata file is opened for any row on the page. */
  @Test
  void listViewsReturnsPointersWithoutParsingAnyMetadata() {
    Pageable pageable = PageRequest.of(0, 2);
    HouseTable first = ViewTestFixtures.viewRow(METADATA_PATH);
    HouseTable second = ViewTestFixtures.viewRow(METADATA_PATH).toBuilder().tableId("v2").build();
    when(houseTableRepository.findAllViewsByDatabaseId(DB, pageable))
        .thenReturn(new PageImpl<>(Arrays.asList(first, second), pageable, 5L));

    Page<ViewPointer> page = viewRepository.listViews(DB, pageable);

    Assertions.assertEquals(2, page.getContent().size());
    Assertions.assertEquals(5L, page.getTotalElements());
    Assertions.assertEquals(3, page.getTotalPages());
    Assertions.assertEquals(VIEW, page.getContent().get(0).getViewId());
    Assertions.assertEquals("v2", page.getContent().get(1).getViewId());
    Assertions.assertEquals(METADATA_PATH, page.getContent().get(0).getMetadataLocation());
    verify(houseTableRepository, times(1)).findAllViewsByDatabaseId(DB, pageable);
    verifyNoInteractions(viewMetadataCodec);
    verifyNoInteractions(fileIOManager);
  }

  /** Never falls back to the table delete path, which would purge storage. */
  @Test
  void dropViewIsATypedHardPointerDeleteWithNoStorageOrParserWork() {
    when(houseTableRepository.deleteViewById(any(HouseTablePrimaryKey.class))).thenReturn(true);

    Assertions.assertTrue(viewRepository.dropView(DB, VIEW));

    verify(houseTableRepository, times(1)).deleteViewById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never()).deleteById(any(HouseTablePrimaryKey.class));
    verify(houseTableRepository, never())
        .deleteById(any(HouseTablePrimaryKey.class), any(Boolean.class));
    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
    verifyNoInteractions(storageSelector);
  }

  /** A key that is absent, or occupied by a table, is not a view this repository can drop. */
  @Test
  void dropViewReturnsFalseWhenTheKeyIsNotAView() {
    when(houseTableRepository.deleteViewById(any(HouseTablePrimaryKey.class))).thenReturn(false);

    Assertions.assertFalse(viewRepository.dropView(DB, VIEW));

    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
  }

  /** A differently-cased value is a corrupted row, not a view. */
  @Test
  void loadViewRejectsANonCanonicalViewDiscriminator() {
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(ViewTestFixtures.row("view", METADATA_PATH)));

    Assertions.assertThrows(NoSuchViewException.class, () -> viewRepository.loadView(DB, VIEW));

    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
    verifyNoInteractions(storageSelector);
  }

  @Test
  void loadViewRejectsAMixedCaseViewDiscriminator() {
    when(houseTableRepository.findViewById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(ViewTestFixtures.row("View", METADATA_PATH)));

    Assertions.assertThrows(NoSuchViewException.class, () -> viewRepository.loadView(DB, VIEW));

    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
  }

  /** Rename is rejected before it can touch House Table or storage at all. */
  @Test
  void renameViewIsUnsupportedAndTouchesNothing() {
    Assertions.assertThrows(
        UnsupportedOperationException.class, () -> viewRepository.renameView(DB, VIEW, "v2"));

    verifyNoInteractions(houseTableRepository);
    verifyNoInteractions(fileIOManager);
    verifyNoInteractions(viewMetadataCodec);
    verifyNoInteractions(storageSelector);
  }
}
