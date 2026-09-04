package com.linkedin.openhouse.internal.catalog.view;

import com.linkedin.openhouse.internal.catalog.view.model.LoadedView;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitResult;
import com.linkedin.openhouse.internal.catalog.view.model.ViewPointer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Commit and lifecycle repository for OpenHouse views: typed pointer read, Iceberg metadata build,
 * immutable-file write, and exactly one House Table compare-and-swap. Every signature is
 * version-neutral so the interface stays loadable under Iceberg 1.2, where no bean is registered.
 */
public interface OpenHouseInternalViewRepository {

  /**
   * A null {@link ViewCommitIntent#getBaseViewVersion()} is a CREATE; a non-null value is a REPLACE
   * against that exact current metadata path.
   *
   * @throws org.apache.iceberg.exceptions.AlreadyExistsException create collided with a view, or
   *     lost the create compare-and-swap
   * @throws ViewNameOccupiedException create collided with a non-view occupant
   * @throws org.apache.iceberg.exceptions.CommitFailedException replace token was stale, or lost
   *     the replace compare-and-swap
   * @throws org.apache.iceberg.exceptions.CommitStateUnknownException the single publish attempt
   *     was ambiguous; never retried
   */
  ViewCommitResult commit(ViewCommitIntent intent);

  /**
   * @throws org.apache.iceberg.exceptions.NoSuchViewException the key is absent or holds a non-view
   */
  LoadedView loadView(String databaseId, String viewId);

  /** Lists view pointers for a database without reading any metadata file. */
  Page<ViewPointer> listViews(String databaseId, Pageable pageable);

  /**
   * Hard delete; no metadata parse and no storage deletion.
   *
   * @return false when the key is absent or holds a non-view
   */
  boolean dropView(String databaseId, String viewId);

  /** @throws UnsupportedOperationException always, before any repository or storage interaction */
  void renameView(String databaseId, String fromViewId, String toViewId);
}
