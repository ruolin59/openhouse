package com.linkedin.openhouse.internal.catalog.view;

import com.linkedin.openhouse.internal.catalog.view.model.LoadedView;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Commit and lifecycle repository for OpenHouse views.
 *
 * <p>This repository owns the whole server-side commit protocol: typed pointer read, Iceberg
 * metadata build, immutable-file write, and exactly one House Table compare-and-swap. It is the top
 * layer of this ticket; a later {@code services/tables} view layer maps REST requests onto it and
 * maps its results and exceptions onto the wire contract.
 *
 * <p>Every signature here is version-neutral so the interface stays loadable under Iceberg 1.2,
 * where no implementation bean is registered.
 */
public interface OpenHouseInternalViewRepository {

  /**
   * Commits a view definition.
   *
   * <p>A null {@link ViewCommitIntent#getBaseViewVersion()} is a CREATE; a non-null value is a
   * REPLACE against that exact current metadata path.
   *
   * @throws org.apache.iceberg.exceptions.AlreadyExistsException create collided with a view, or
   *     lost the create compare-and-swap
   * @throws ViewNameOccupiedException create collided with a non-view occupant
   * @throws org.apache.iceberg.exceptions.CommitFailedException replace token was stale, or lost
   *     the replace compare-and-swap
   * @throws org.apache.iceberg.exceptions.CommitStateUnknownException the single publish attempt
   *     produced an ambiguous outcome; never retried
   */
  ViewCommitResult commit(ViewCommitIntent intent);

  /**
   * Loads a view through one typed pointer read followed by one metadata parse.
   *
   * @throws org.apache.iceberg.exceptions.NoSuchViewException the key is absent or holds a non-view
   */
  LoadedView loadView(String databaseId, String viewId);

  /** Lists view pointers for a database without reading any metadata file. */
  Page<ViewPointer> listViews(String databaseId, Pageable pageable);

  /**
   * Hard-deletes a view pointer. No metadata parse, no storage deletion, no soft-delete row.
   *
   * @return false when the key is absent or holds a non-view
   */
  boolean dropView(String databaseId, String viewId);

  /**
   * Always unsupported in M1, and always before any repository or storage interaction.
   *
   * @throws UnsupportedOperationException always
   */
  void renameView(String databaseId, String fromViewId, String toViewId);
}
