package com.linkedin.openhouse.internal.catalog;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.metrics.micrometer.MetricsReporter;
import com.linkedin.openhouse.internal.catalog.cache.TableMetadataCache;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.compress.utils.Lists;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.exceptions.NoSuchTableException;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableMap;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Defence in depth for the table refresh path: a typed read should never return a view, but view
 * metadata is a different document and must not reach {@code TableMetadataParser}.
 *
 * <p>The cache is mocked because "the parser was never reached" is the claim, and the cache is the
 * single seam every metadata load goes through.
 */
public class OpenHouseInternalTableOperationsViewDefenseTest {

  private static final TableIdentifier IDENTIFIER = TableIdentifier.of("test_db", "test_table");

  private final TableMetadataCache tableMetadataCache = mock(TableMetadataCache.class);

  private OpenHouseInternalTableOperations operations(HouseTableRepository repository) {
    return new OpenHouseInternalTableOperations(
        repository,
        new HadoopFileIO(new Configuration()),
        mock(HouseTableMapper.class),
        IDENTIFIER,
        new MetricsReporter(new SimpleMeterRegistry(), "TEST_CATALOG", Lists.newArrayList()),
        mock(FileIOManager.class),
        tableMetadataCache);
  }

  @Test
  void refreshRejectsANonTableRowBeforeParsingItAsTableMetadata() {
    HouseTableRepository repository = mock(HouseTableRepository.class);
    HouseTable viewRow =
        HouseTable.builder()
            .databaseId(IDENTIFIER.namespace().toString())
            .tableId(IDENTIFIER.name())
            .tableLocation("/data/openhouse/test_db/test_table-uuid/00001-abc.metadata.json")
            .entityType("VIEW")
            .build();
    when(repository.findById(any(HouseTablePrimaryKey.class))).thenReturn(Optional.of(viewRow));

    OpenHouseInternalTableOperations operations = operations(repository);

    Assertions.assertThrows(NoSuchTableException.class, operations::refresh);
    Assertions.assertNull(operations.currentMetadataLocation());
    verifyNoInteractions(tableMetadataCache);
  }

  @Test
  void refreshRejectsAnUnknownEntityTypeBeforeParsingItAsTableMetadata() {
    HouseTableRepository repository = mock(HouseTableRepository.class);
    HouseTable unknownRow =
        HouseTable.builder()
            .databaseId(IDENTIFIER.namespace().toString())
            .tableId(IDENTIFIER.name())
            .tableLocation("/data/openhouse/test_db/test_table-uuid/00001-abc.metadata.json")
            .entityType("MATERIALIZED_VIEW")
            .build();
    when(repository.findById(any(HouseTablePrimaryKey.class))).thenReturn(Optional.of(unknownRow));

    OpenHouseInternalTableOperations operations = operations(repository);

    Assertions.assertThrows(NoSuchTableException.class, operations::refresh);
    verifyNoInteractions(tableMetadataCache);
  }

  @Test
  void refreshRejectsANonCanonicalTableDiscriminatorBeforeParsing() {
    HouseTableRepository repository = mock(HouseTableRepository.class);
    HouseTable malformedRow =
        HouseTable.builder()
            .databaseId(IDENTIFIER.namespace().toString())
            .tableId(IDENTIFIER.name())
            .tableLocation("/data/openhouse/test_db/test_table-uuid/00001-abc.metadata.json")
            .entityType("table")
            .build();
    when(repository.findById(any(HouseTablePrimaryKey.class)))
        .thenReturn(Optional.of(malformedRow));

    OpenHouseInternalTableOperations operations = operations(repository);

    Assertions.assertThrows(NoSuchTableException.class, operations::refresh);
    verifyNoInteractions(tableMetadataCache);
  }

  /** The guard must reject non-tables, not everything it cannot positively identify. */
  @Test
  void refreshLoadsALegacyRowWithNoEntityTypeAsANormalTable(@TempDir Path tempDir)
      throws IOException {
    TableMetadata metadata =
        TableMetadata.newTableMetadata(
            new Schema(Types.NestedField.required(1, "data", Types.StringType.get())),
            PartitionSpec.unpartitioned(),
            tempDir.toString(),
            ImmutableMap.of("format-version", "2"));
    Path metadataFile = tempDir.resolve("00001-" + UUID.randomUUID() + ".metadata.json");
    Files.write(metadataFile, TableMetadataParser.toJson(metadata).getBytes());

    HouseTableRepository repository = mock(HouseTableRepository.class);
    HouseTable legacyRow =
        HouseTable.builder()
            .databaseId(IDENTIFIER.namespace().toString())
            .tableId(IDENTIFIER.name())
            .tableLocation(metadataFile.toString())
            .build();
    when(repository.findById(any(HouseTablePrimaryKey.class))).thenReturn(Optional.of(legacyRow));
    when(tableMetadataCache.load(anyString(), any()))
        .thenAnswer(
            invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());

    OpenHouseInternalTableOperations operations = operations(repository);

    Assertions.assertDoesNotThrow(operations::refresh);
    Assertions.assertEquals(metadataFile.toString(), operations.currentMetadataLocation());
    Assertions.assertNotNull(operations.current());
    verify(tableMetadataCache).load(anyString(), any());
    verify(repository, never()).save(any());
  }

  /** The ordinary pre-create refresh, not a type error. */
  @Test
  void refreshTreatsAnAbsentRowAsAPreCreateRefresh() {
    HouseTableRepository repository = mock(HouseTableRepository.class);
    when(repository.findById(any(HouseTablePrimaryKey.class))).thenReturn(Optional.empty());

    OpenHouseInternalTableOperations operations = operations(repository);

    Assertions.assertDoesNotThrow(operations::refresh);
    Assertions.assertNull(operations.currentMetadataLocation());
    verifyNoInteractions(tableMetadataCache);
  }
}
