package com.linkedin.openhouse.internal.catalog.view;

import org.apache.iceberg.exceptions.BadRequestException;

/**
 * A REPLACE whose source dialect differs from the current view's captured source dialect. This is a
 * client-owned rejection that only the engine can detect, since it depends on the captured
 * metadata. It stays a {@link BadRequestException} so existing callers that catch that type keep
 * working.
 */
public class ViewSourceDialectMismatchException extends BadRequestException {

  public ViewSourceDialectMismatchException(String databaseId, String viewId) {
    super(
        "Cannot replace view %s.%s: sourceDialect must match the current view", databaseId, viewId);
  }
}
