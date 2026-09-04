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
 * A fully resolved view: its pointer plus the fields parsed out of the metadata file.
 *
 * <p>Version-neutral by construction: it exposes no {@code org.apache.iceberg.view.*} type, so the
 * shared repository interface that returns it stays loadable under Iceberg 1.2.
 */
@Builder(toBuilder = true)
@Getter
@EqualsAndHashCode
@ToString
public class LoadedView {

  private final ViewPointer pointer;

  private final String viewUuid;

  private final Schema schema;

  private final List<SqlViewRepresentationIntent> representations;

  private final String sourceDialect;

  private final String defaultCatalog;

  private final Namespace defaultNamespace;

  private final Map<String, String> properties;

  private final long lastModifiedTime;

  /** The resulting current version id, as assigned by Iceberg, never computed by OpenHouse. */
  private final int currentVersionId;
}
