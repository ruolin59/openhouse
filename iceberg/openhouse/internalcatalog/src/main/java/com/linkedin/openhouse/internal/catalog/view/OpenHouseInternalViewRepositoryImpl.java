package com.linkedin.openhouse.internal.catalog.view;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.getCanonicalFieldName;

import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.CatalogConstants;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableConcurrentUpdateException;
import com.linkedin.openhouse.internal.catalog.repository.exception.HouseTableRepositoryStateUnknownException;
import com.linkedin.openhouse.internal.catalog.view.model.LoadedView;
import com.linkedin.openhouse.internal.catalog.view.model.SqlViewRepresentationIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.exceptions.AlreadyExistsException;
import org.apache.iceberg.exceptions.BadRequestException;
import org.apache.iceberg.exceptions.CommitFailedException;
import org.apache.iceberg.exceptions.CommitStateUnknownException;
import org.apache.iceberg.exceptions.NoSuchViewException;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.ImmutableSQLViewRepresentation;
import org.apache.iceberg.view.ImmutableViewVersion;
import org.apache.iceberg.view.SQLViewRepresentation;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewProperties;
import org.apache.iceberg.view.ViewRepresentation;
import org.apache.iceberg.view.ViewVersion;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Iceberg-1.5 implementation of {@link OpenHouseInternalViewRepository}.
 *
 * <p>Registered only from {@link OpenHouseInternalViewConfiguration}, never as an unconditional
 * component, so an Iceberg-1.2 runtime never introspects it.
 *
 * <p>Every commit has the same shape: capture the base, build the metadata, write the immutable
 * file, then perform exactly one House Table compare-and-swap carrying the exact captured token.
 * Nothing is re-read or rebuilt between the capture and the swap, and the swap is never retried,
 * because the swap is the sole arbiter of a race and a second attempt could double-apply.
 */
@AllArgsConstructor
@Slf4j
public class OpenHouseInternalViewRepositoryImpl implements OpenHouseInternalViewRepository {

  /** The dialect the caller authored in, recorded with the version rather than as a property. */
  private static final String SOURCE_DIALECT_SUMMARY_KEY = "sourceDialect";

  private static final String OPERATION_SUMMARY_KEY = "operation";
  private static final String CREATE_OPERATION = "create";
  private static final String REPLACE_OPERATION = "replace";

  private static final String ENTITY_TYPE_VIEW = "VIEW";
  private static final String ENTITY_TYPE_TABLE = "TABLE";

  private static final String METADATA_FILE_EXTENSION = ".metadata.json";

  private final HouseTableRepository houseTableRepository;

  private final FileIOManager fileIOManager;

  private final ViewMetadataCodec viewMetadataCodec;

  private final StorageSelector storageSelector;

  private final StorageType storageType;

  private final HouseTableMapper houseTableMapper;

  @Override
  public ViewCommitResult commit(ViewCommitIntent intent) {
    rejectServerOwnedProperties(intent);
    rejectDuplicateDialects(intent);
    return intent.getBaseViewVersion() == null ? create(intent) : replace(intent);
  }

  private ViewCommitResult create(ViewCommitIntent intent) {
    // Advisory only: it selects which collision the caller is told about. The swap below, not this
    // read, is what actually prevents two creates from both succeeding.
    houseTableRepository
        .findEntityById(keyOf(intent.getDatabaseId(), intent.getViewId()))
        .ifPresent(occupant -> rejectOccupiedName(intent, occupant));

    String viewUuid = UUID.randomUUID().toString();
    Map<String, String> userProperties = userPropertiesOf(intent);
    Storage storage = storageSelector.selectStorage(intent.getDatabaseId(), intent.getViewId());
    // UUID first, so the allocated directory embeds the identity the metadata will carry.
    String viewLocation =
        storage.allocateTableLocation(
            intent.getDatabaseId(),
            intent.getViewId(),
            viewUuid,
            intent.getCreator(),
            userProperties);
    FileIO fileIO = fileIOManager.getFileIO(storage.getType());

    String newMetadataLocation = metadataFileLocation(viewLocation, 1);
    String now = String.valueOf(nowMillis());

    Map<String, String> properties = new LinkedHashMap<>(userProperties);
    properties.put(ViewProperties.REPLACE_DROP_DIALECT_ALLOWED, "false");
    properties.put(getCanonicalFieldName("tableUUID"), viewUuid);
    properties.put(getCanonicalFieldName("tableId"), intent.getViewId());
    properties.put(getCanonicalFieldName("databaseId"), intent.getDatabaseId());
    properties.put(getCanonicalFieldName("tableCreator"), intent.getCreator());
    properties.put(getCanonicalFieldName("tableVersion"), CatalogConstants.INITIAL_VERSION);
    properties.put(getCanonicalFieldName("tableLocation"), newMetadataLocation);
    properties.put(getCanonicalFieldName("creationTime"), now);
    properties.put(getCanonicalFieldName("lastModifiedTime"), now);

    ViewMetadata metadata =
        ViewMetadata.builder()
            .assignUUID(viewUuid)
            .setLocation(viewLocation)
            .setCurrentVersion(
                candidateVersion(intent, 1, CREATE_OPERATION, Long.parseLong(now)),
                intent.getSchema())
            .setProperties(properties)
            .build();

    return writeThenPublish(
        intent, metadata, fileIO, newMetadataLocation, viewUuid, Long.parseLong(now), true);
  }

  private void rejectOccupiedName(ViewCommitIntent intent, HouseTable occupant) {
    if (isView(occupant.getEntityType())) {
      throw new AlreadyExistsException(
          "View already exists: %s.%s", intent.getDatabaseId(), intent.getViewId());
    }
    // A legacy row carries no discriminator and means TABLE. Anything else, including a value that
    // merely looks like a known type in the wrong case, is reported as-is: failing closed is the
    // only safe answer when we cannot tell what owns the name.
    String occupantType =
        occupant.getEntityType() == null ? ENTITY_TYPE_TABLE : occupant.getEntityType();
    throw new ViewNameOccupiedException(intent.getDatabaseId(), intent.getViewId(), occupantType);
  }

  /**
   * House Table's discriminator contract is the canonical constant name, written by {@code
   * EntityType.name()} and read back through a converter that turns a legacy NULL into TABLE.
   * Matching case-insensitively would accept a value the contract never produces, so a corrupted
   * row would be trusted instead of rejected. Only the exact spelling counts.
   */
  private static boolean isView(String entityType) {
    return ENTITY_TYPE_VIEW.equals(entityType);
  }

  private ViewCommitResult replace(ViewCommitIntent intent) {
    HouseTable row = requireViewRow(intent.getDatabaseId(), intent.getViewId());
    FileIO fileIO = fileIOManager.getFileIO(storageType.fromString(row.getStorageType()));
    ViewMetadata current = viewMetadataCodec.read(fileIO.newInputFile(row.getTableLocation()));

    String capturedBase = intent.getBaseViewVersion();
    if (!capturedBase.equals(row.getTableLocation())) {
      throw new CommitFailedException(
          "Cannot replace view %s.%s: base version %s is not the current version %s",
          intent.getDatabaseId(), intent.getViewId(), capturedBase, row.getTableLocation());
    }

    Map<String, String> currentUserProperties = userPropertiesOf(current);
    Map<String, String> userProperties = new LinkedHashMap<>(currentUserProperties);
    userProperties.putAll(userPropertiesOf(intent));

    if (isUnchanged(intent, current, userProperties, currentUserProperties)) {
      // Nothing observable would change, so writing a file and moving the pointer would manufacture
      // a new version and a new last-modified time out of a request that asked for neither.
      return ViewCommitResult.builder()
          .pointer(pointerOf(row))
          .viewUuid(current.uuid())
          .lastModifiedTime(longProperty(current, "lastModifiedTime"))
          .created(false)
          .metadataChanged(false)
          .build();
    }

    String newMetadataLocation =
        metadataFileLocation(current.location(), current.history().size() + 1);
    String now = String.valueOf(advanceLastModified(longProperty(current, "lastModifiedTime")));

    Map<String, String> properties = new LinkedHashMap<>(userProperties);
    properties.put(ViewProperties.REPLACE_DROP_DIALECT_ALLOWED, "false");
    properties.put(getCanonicalFieldName("tableUUID"), current.uuid());
    properties.put(getCanonicalFieldName("tableId"), intent.getViewId());
    properties.put(getCanonicalFieldName("databaseId"), intent.getDatabaseId());
    properties.put(
        getCanonicalFieldName("tableCreator"),
        current
            .properties()
            .getOrDefault(getCanonicalFieldName("tableCreator"), intent.getCreator()));
    properties.put(getCanonicalFieldName("tableVersion"), capturedBase);
    properties.put(getCanonicalFieldName("tableLocation"), newMetadataLocation);
    properties.put(
        getCanonicalFieldName("creationTime"),
        current.properties().getOrDefault(getCanonicalFieldName("creationTime"), now));
    properties.put(getCanonicalFieldName("lastModifiedTime"), now);

    // buildFrom preserves UUID, location, schemas, versions, and history; Iceberg assigns the
    // resulting version id and rejects a replacement that drops a stored dialect.
    ViewMetadata metadata =
        ViewMetadata.buildFrom(current)
            .setCurrentVersion(
                candidateVersion(
                    intent, current.currentVersionId() + 1, REPLACE_OPERATION, Long.parseLong(now)),
                intent.getSchema())
            .setProperties(properties)
            .build();

    return writeThenPublish(
        intent, metadata, fileIO, newMetadataLocation, current.uuid(), Long.parseLong(now), false);
  }

  /**
   * The repository owns this comparison. Iceberg 1.5.2.21 cannot answer it for us: {@code
   * sameViewVersion} compares the whole summary map, and every submission carries a fresh timestamp
   * and operation, so Iceberg treats every resubmission as new.
   */
  private boolean isUnchanged(
      ViewCommitIntent intent,
      ViewMetadata current,
      Map<String, String> mergedUserProperties,
      Map<String, String> currentUserProperties) {
    ViewVersion version = current.currentVersion();
    // sameSchema, not asStruct: the struct alone ignores identifier-field ids, so a change that
    // only moves the identifier fields would read as identical and be silently dropped.
    return current.schema().sameSchema(intent.getSchema())
        && representationsOf(version).equals(representationsOf(intent.getRepresentations()))
        && Objects.equals(
            version.summary().get(SOURCE_DIALECT_SUMMARY_KEY), intent.getSourceDialect())
        && Objects.equals(version.defaultCatalog(), intent.getDefaultCatalog())
        && Objects.equals(version.defaultNamespace(), intent.getDefaultNamespace())
        && mergedUserProperties.equals(currentUserProperties);
  }

  /**
   * Writes the immutable metadata file and then publishes once. The order matters: publishing first
   * would advertise a pointer to a file that may never exist, and the loser of a swap must leave
   * behind an unreachable file rather than a broken pointer.
   *
   * <p>The expected version is not passed separately because it is already stamped into the
   * metadata as {@code openhouse.tableVersion} and travels to the pointer row from there;
   * re-deriving it here is exactly the mistake that would turn a conditional write into a blind
   * one.
   */
  private ViewCommitResult writeThenPublish(
      ViewCommitIntent intent,
      ViewMetadata metadata,
      FileIO fileIO,
      String newMetadataLocation,
      String viewUuid,
      long lastModifiedTime,
      boolean created) {
    viewMetadataCodec.write(metadata, fileIO.newOutputFile(newMetadataLocation));

    HouseTable pointer = houseTableMapper.toHouseTable(htsFieldsOf(metadata.properties()), fileIO);

    HouseTable saved;
    try {
      saved = houseTableRepository.saveView(pointer);
    } catch (HouseTableConcurrentUpdateException e) {
      // The same 409 means different things by operation: a create lost a name, a replace lost a
      // commit. Collapsing them would hide which one the caller can act on and how.
      if (created) {
        throw new AlreadyExistsException(
            e, "View already exists: %s.%s", intent.getDatabaseId(), intent.getViewId());
      }
      throw new CommitFailedException(
          e,
          "Cannot replace view %s.%s: it was modified concurrently",
          intent.getDatabaseId(),
          intent.getViewId());
    } catch (HouseTableRepositoryStateUnknownException e) {
      // Deliberately not retried and not re-read: the write may well have landed.
      throw new CommitStateUnknownException(e);
    }

    return ViewCommitResult.builder()
        .pointer(pointerOf(saved))
        .viewUuid(viewUuid)
        .lastModifiedTime(lastModifiedTime)
        .created(created)
        .metadataChanged(true)
        .build();
  }

  @Override
  public LoadedView loadView(String databaseId, String viewId) {
    HouseTable row = requireViewRow(databaseId, viewId);
    FileIO fileIO = fileIOManager.getFileIO(storageType.fromString(row.getStorageType()));
    ViewMetadata metadata = viewMetadataCodec.read(fileIO.newInputFile(row.getTableLocation()));
    ViewVersion version = metadata.currentVersion();

    return LoadedView.builder()
        .pointer(pointerOf(row))
        .viewUuid(metadata.uuid())
        .schema(metadata.schema())
        .representations(representationIntentsOf(version))
        .sourceDialect(version.summary().get(SOURCE_DIALECT_SUMMARY_KEY))
        .defaultCatalog(version.defaultCatalog())
        .defaultNamespace(version.defaultNamespace())
        .properties(metadata.properties())
        .lastModifiedTime(longProperty(metadata, "lastModifiedTime"))
        .currentVersionId(metadata.currentVersionId())
        .build();
  }

  @Override
  public Page<ViewPointer> listViews(String databaseId, Pageable pageable) {
    return houseTableRepository
        .findAllViewsByDatabaseId(databaseId, pageable)
        .map(OpenHouseInternalViewRepositoryImpl::pointerOf);
  }

  @Override
  public boolean dropView(String databaseId, String viewId) {
    try {
      return houseTableRepository.deleteViewById(keyOf(databaseId, viewId));
    } catch (HouseTableRepositoryStateUnknownException e) {
      throw new CommitStateUnknownException(e);
    }
  }

  @Override
  public void renameView(String databaseId, String fromViewId, String toViewId) {
    throw new UnsupportedOperationException(
        "Renaming a view is not supported: " + databaseId + "." + fromViewId);
  }

  /**
   * A missing row and a row holding something other than a view are the same answer to the caller:
   * there is no such view. Reporting a table as absent would be worse than useless, because a later
   * create would then be told the name is free.
   */
  private HouseTable requireViewRow(String databaseId, String viewId) {
    HouseTable row =
        houseTableRepository
            .findViewById(keyOf(databaseId, viewId))
            .orElseThrow(
                () -> new NoSuchViewException("View does not exist: %s.%s", databaseId, viewId));
    if (!isView(row.getEntityType())) {
      throw new NoSuchViewException("View does not exist: %s.%s", databaseId, viewId);
    }
    return row;
  }

  /**
   * Iceberg stores at most one query per dialect and compares dialects case-insensitively. Checking
   * here, before no-op detection, means a duplicate is always answered as a caller error rather
   * than being able to short-circuit into a no-op that never reaches Iceberg's own check.
   */
  private void rejectDuplicateDialects(ViewCommitIntent intent) {
    if (intent.getRepresentations() == null) {
      return;
    }
    Set<String> dialects = new HashSet<>();
    for (SqlViewRepresentationIntent representation : intent.getRepresentations()) {
      String dialect = representation.getDialect();
      if (dialect != null && !dialects.add(dialect.toLowerCase(Locale.ROOT))) {
        throw new BadRequestException("Cannot add multiple queries for dialect %s", dialect);
      }
    }
  }

  /**
   * Wall-clock milliseconds. Overridable so a test can pin it; the monotonic guard in {@link
   * #advanceLastModified} is what keeps a changed commit observably newer regardless.
   */
  protected long nowMillis() {
    return Instant.now(Clock.systemUTC()).toEpochMilli();
  }

  /**
   * A changed commit must be observably newer than the version it replaced. Two commits inside one
   * millisecond, or a backward clock adjustment, would otherwise leave last-modified equal or lower
   * while {@code metadataChanged} says something changed.
   */
  private long advanceLastModified(long previousLastModified) {
    long now = nowMillis();
    if (previousLastModified == Long.MAX_VALUE) {
      return Long.MAX_VALUE;
    }
    return Math.max(now, previousLastModified + 1);
  }

  private void rejectServerOwnedProperties(ViewCommitIntent intent) {
    for (String key : userPropertiesOf(intent).keySet()) {
      if (HouseTableSerdeUtils.IS_OH_PREFIXED.test(key)
          || ViewProperties.REPLACE_DROP_DIALECT_ALLOWED.equals(key)) {
        throw new BadRequestException(
            "Property %s is owned by OpenHouse and cannot be set by a caller", key);
      }
    }
  }

  private static HouseTablePrimaryKey keyOf(String databaseId, String viewId) {
    return HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(viewId).build();
  }

  private static ViewPointer pointerOf(HouseTable row) {
    return ViewPointer.builder()
        .databaseId(row.getDatabaseId())
        .viewId(row.getTableId())
        .metadataLocation(row.getTableLocation())
        .storageType(row.getStorageType())
        .creationTime(row.getCreationTime())
        .build();
  }

  private static Map<String, String> userPropertiesOf(ViewCommitIntent intent) {
    return intent.getViewProperties() == null ? Collections.emptyMap() : intent.getViewProperties();
  }

  /** Everything the server did not stamp, which is exactly what structural equality compares. */
  private static Map<String, String> userPropertiesOf(ViewMetadata metadata) {
    Map<String, String> userProperties = new LinkedHashMap<>();
    metadata
        .properties()
        .forEach(
            (key, value) -> {
              if (!HouseTableSerdeUtils.IS_OH_PREFIXED.test(key)
                  && !ViewProperties.REPLACE_DROP_DIALECT_ALLOWED.equals(key)) {
                userProperties.put(key, value);
              }
            });
    return userProperties;
  }

  /** Only HTS-resident fields reach the pointer row; everything else stays in the metadata file. */
  private static Map<String, String> htsFieldsOf(Map<String, String> properties) {
    Map<String, String> htsFields = new LinkedHashMap<>();
    properties.forEach(
        (key, value) -> {
          if (HouseTableSerdeUtils.IS_OH_PREFIXED.test(key)) {
            String field = key.substring(HouseTableSerdeUtils.OPENHOUSE_NAMESPACE.length());
            if (HouseTableSerdeUtils.HTS_FIELD_NAMES.contains(field)) {
              htsFields.put(field, value);
            }
          }
        });
    return htsFields;
  }

  private static long longProperty(ViewMetadata metadata, String htsField) {
    String value = metadata.properties().get(getCanonicalFieldName(htsField));
    return value == null ? 0L : Long.parseLong(value);
  }

  /**
   * The submitted version id is a placeholder. Iceberg reassigns it, so asserting on it anywhere
   * would be asserting on our own input rather than on what was stored.
   */
  private static ViewVersion candidateVersion(
      ViewCommitIntent intent,
      int candidateVersionId,
      String operation,
      long candidateTimestampMillis) {
    List<ViewRepresentation> representations = new ArrayList<>();
    if (intent.getRepresentations() != null) {
      for (SqlViewRepresentationIntent representation : intent.getRepresentations()) {
        representations.add(
            ImmutableSQLViewRepresentation.builder()
                .sql(representation.getSql())
                .dialect(representation.getDialect())
                .build());
      }
    }

    ImmutableViewVersion.Builder builder =
        ImmutableViewVersion.builder()
            .versionId(candidateVersionId)
            .timestampMillis(candidateTimestampMillis)
            .schemaId(Optional.ofNullable(intent.getSchema()).map(Schema::schemaId).orElse(0))
            .defaultNamespace(
                intent.getDefaultNamespace() == null
                    ? Namespace.empty()
                    : intent.getDefaultNamespace())
            .putSummary(OPERATION_SUMMARY_KEY, operation)
            .addAllRepresentations(representations);
    if (intent.getDefaultCatalog() != null) {
      builder.defaultCatalog(intent.getDefaultCatalog());
    }
    if (intent.getSourceDialect() != null) {
      builder.putSummary(SOURCE_DIALECT_SUMMARY_KEY, intent.getSourceDialect());
    }
    return builder.build();
  }

  /**
   * A sorted list of dialect/SQL pairs rather than a map keyed by dialect. A map silently drops
   * every entry but the last for a repeated dialect, which would let an invalid submission compare
   * equal to a valid stored definition and be answered as a no-op. Order is normalized because a
   * view's representations are looked up by dialect, so their order carries no meaning.
   */
  private static List<String> representationsOf(ViewVersion version) {
    List<String> pairs = new ArrayList<>();
    for (ViewRepresentation representation : version.representations()) {
      if (representation instanceof SQLViewRepresentation) {
        SQLViewRepresentation sql = (SQLViewRepresentation) representation;
        pairs.add(representationKey(sql.dialect(), sql.sql()));
      }
    }
    Collections.sort(pairs);
    return pairs;
  }

  private static List<String> representationsOf(List<SqlViewRepresentationIntent> representations) {
    List<String> pairs = new ArrayList<>();
    if (representations != null) {
      for (SqlViewRepresentationIntent representation : representations) {
        pairs.add(representationKey(representation.getDialect(), representation.getSql()));
      }
    }
    Collections.sort(pairs);
    return pairs;
  }

  private static String representationKey(String dialect, String sql) {
    return dialect + '\u0000' + sql;
  }

  private static List<SqlViewRepresentationIntent> representationIntentsOf(ViewVersion version) {
    List<SqlViewRepresentationIntent> representations = new ArrayList<>();
    for (ViewRepresentation representation : version.representations()) {
      if (representation instanceof SQLViewRepresentation) {
        SQLViewRepresentation sql = (SQLViewRepresentation) representation;
        representations.add(
            SqlViewRepresentationIntent.builder().sql(sql.sql()).dialect(sql.dialect()).build());
      }
    }
    return representations;
  }

  /**
   * Metadata files live directly under the allocated base, mirroring the table convention. The
   * embedded UUID is what lets two writers at the same version each write their own candidate
   * without colliding, which is a precondition for the swap being the only arbiter.
   */
  private static String metadataFileLocation(String viewLocation, int version) {
    return String.format(
        "%s/%05d-%s%s", viewLocation, version, UUID.randomUUID(), METADATA_FILE_EXTENSION);
  }
}
