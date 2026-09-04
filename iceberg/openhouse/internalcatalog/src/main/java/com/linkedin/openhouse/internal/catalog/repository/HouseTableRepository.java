package com.linkedin.openhouse.internal.catalog.repository;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.PagingAndSortingRepository;
import org.springframework.stereotype.Repository;

/**
 * Base interface for repository backed by HouseTableService for storing and retrieving {@link
 * HouseTable} object.
 */
@Repository
public interface HouseTableRepository
    extends PagingAndSortingRepository<HouseTable, HouseTablePrimaryKey> {

  List<HouseTable> findAllByDatabaseId(String databaseId);

  /**
   * Delete a table by its primary key with purge option
   *
   * @param houseTablePrimaryKey the primary key of the table
   * @param purge true if table should be deleted permanently, otherwise retain with soft delete
   */
  void deleteById(HouseTablePrimaryKey houseTablePrimaryKey, boolean purge);

  Page<HouseTable> findAllByDatabaseId(String databaseId, Pageable pageable);

  void rename(
      String fromDatabaseId,
      String fromTableId,
      String toDatabaseId,
      String toTableId,
      String metadataLocation);

  /**
   * Find all soft-deleted tables by database ID with pagination and optional filtering
   *
   * @param databaseId The database ID to filter by
   * @param tableId The table ID to filter by (optional, can be null)
   * @param pageable Pagination information
   * @return List of soft-deleted HouseTable objects matching the criteria
   */
  Page<HouseTable> searchSoftDeletedTables(String databaseId, String tableId, Pageable pageable);

  /**
   * Delete soft-deleted tables that are older than the specified timestamp.
   *
   * @param databaseId
   * @param tableId
   * @param purgeAfterMs timestamp in milliseconds where tables older than this will be permanently
   *     deleted
   */
  void purgeSoftDeletedTables(String databaseId, String tableId, long purgeAfterMs);

  /**
   * Restore a soft deleted table
   *
   * @param databaseId The database ID
   * @param tableId The table ID
   * @param deletedAtMs The timestamp when the table was deleted
   */
  void restoreTable(String databaseId, String tableId, long deletedAtMs);

  /**
   * Neutral occupancy read: returns whichever entity occupies the key, of any type, so a caller can
   * classify a name collision. The returned row's {@code entityType} carries the discriminator, and
   * a null value is a legacy row that means TABLE.
   *
   * <p>This read is advisory only. It never acts as a write precondition; the single House Table
   * compare-and-swap remains the sole race arbiter.
   */
  Optional<HouseTable> findEntityById(HouseTablePrimaryKey houseTablePrimaryKey);

  /** Typed point read that resolves only VIEW rows; a table at the same key reads as absent. */
  Optional<HouseTable> findViewById(HouseTablePrimaryKey houseTablePrimaryKey);

  /** Typed paginated listing of view pointers; House Table filters VIEW before paginating. */
  Page<HouseTable> findAllViewsByDatabaseId(String databaseId, Pageable pageable);

  /**
   * Single, un-retried typed write of a view pointer.
   *
   * <p>Exactly one attempt: an ambiguous 5xx, 504, or block timeout must surface as unknown state
   * rather than a blind second write that could double-apply. The outgoing body leaves entity type
   * unset; House Table stamps VIEW from the route.
   */
  HouseTable saveView(HouseTable houseTable);

  /**
   * Single typed hard delete of a view pointer. No soft-delete row and no retry.
   *
   * @return false when the key is absent or holds a non-view
   */
  boolean deleteViewById(HouseTablePrimaryKey houseTablePrimaryKey);
}
