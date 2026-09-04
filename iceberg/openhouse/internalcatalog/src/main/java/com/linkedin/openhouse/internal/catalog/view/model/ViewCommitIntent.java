package com.linkedin.openhouse.internal.catalog.view.model;

import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;

/**
 * Caller-supplied description of one view commit.
 *
 * <p>{@code baseViewVersion} discriminates the operation: {@code null} means CREATE, and a non-null
 * value means REPLACE against that exact current metadata path.
 *
 * <p>Version-neutral by construction: {@link Schema} and {@link Namespace} exist in Iceberg 1.2,
 * and no {@code org.apache.iceberg.view.*} type appears here.
 */
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@ToString
public class ViewCommitIntent {

  private final String databaseId;

  private final String viewId;

  private final Schema schema;

  private final List<SqlViewRepresentationIntent> representations;

  /** Dialect the caller authored the view in; recorded in the resulting version summary. */
  private final String sourceDialect;

  private final String defaultCatalog;

  private final Namespace defaultNamespace;

  private final Map<String, String> viewProperties;

  /**
   * Null means CREATE. Non-null is the exact current metadata path this replace is based on, and is
   * passed unchanged as the compare-and-swap token.
   */
  private final String baseViewVersion;

  /**
   * Acting principal recorded on the entity. This repository records identity only; authorization
   * belongs to the later service layer.
   */
  private final String creator;
}
