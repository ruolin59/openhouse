package com.linkedin.openhouse.internal.catalog.view;

import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.model.HouseTablePrimaryKey;
import com.linkedin.openhouse.internal.catalog.view.model.SqlViewRepresentationIntent;
import com.linkedin.openhouse.internal.catalog.view.model.ViewCommitIntent;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.Schema;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.types.Types;

/** Shared constants and builders for the view commit repository tests. */
public final class ViewTestFixtures {

  public static final String DB = "viewdb";
  public static final String VIEW = "v1";
  public static final String CREATOR = "test_user";
  public static final String SPARK_DIALECT = "spark";
  public static final String TRINO_DIALECT = "trino";
  public static final String LOCAL_STORAGE_TYPE = "local";

  /** Exact text House Table stores and exchanges on the wire. */
  public static final String ENTITY_TYPE_VIEW = "VIEW";

  public static final String ENTITY_TYPE_TABLE = "TABLE";

  /** Unrecognized: a create colliding with one must fail closed. */
  public static final String ENTITY_TYPE_UNKNOWN = "MATERIALIZED_VIEW";

  /** Recorded inside the current version summary. */
  public static final String SOURCE_DIALECT_SUMMARY_KEY = "sourceDialect";

  public static final String SQL_V1 = "SELECT id, name FROM viewdb.base_table";
  public static final String SQL_V2 = "SELECT id, name, region FROM viewdb.base_table WHERE id > 0";
  public static final String SQL_V3 = "SELECT id FROM viewdb.other_table";

  private ViewTestFixtures() {}

  public static Schema schemaV1() {
    return new Schema(
        Types.NestedField.required(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "name", Types.StringType.get()));
  }

  /** Materially different from {@link #schemaV1()}, so no structural de-dup. */
  public static Schema schemaV2() {
    return new Schema(
        Types.NestedField.required(1, "id", Types.LongType.get()),
        Types.NestedField.optional(2, "name", Types.StringType.get()),
        Types.NestedField.optional(3, "region", Types.StringType.get()));
  }

  public static SqlViewRepresentationIntent sql(String sqlText, String dialect) {
    return SqlViewRepresentationIntent.builder().sql(sqlText).dialect(dialect).build();
  }

  /** CREATE intent: null base version. */
  public static ViewCommitIntent createIntent() {
    return baseIntent().build();
  }

  /** REPLACE intent against the exact current metadata path. */
  public static ViewCommitIntent replaceIntent(String baseViewVersion) {
    return baseIntent().baseViewVersion(baseViewVersion).build();
  }

  public static ViewCommitIntent.ViewCommitIntentBuilder baseIntent() {
    return ViewCommitIntent.builder()
        .databaseId(DB)
        .viewId(VIEW)
        .schema(schemaV1())
        .representations(Collections.singletonList(sql(SQL_V1, SPARK_DIALECT)))
        .sourceDialect(SPARK_DIALECT)
        .defaultCatalog("openhouse")
        .defaultNamespace(Namespace.of(DB))
        .viewProperties(userProperties("a", "1"))
        .creator(CREATOR);
  }

  public static Map<String, String> userProperties(String key, String value) {
    Map<String, String> properties = new LinkedHashMap<>();
    properties.put(key, value);
    return properties;
  }

  public static List<SqlViewRepresentationIntent> sparkAndTrino(String sqlText) {
    return Arrays.asList(sql(sqlText, SPARK_DIALECT), sql(sqlText, TRINO_DIALECT));
  }

  public static HouseTablePrimaryKey key(String databaseId, String viewId) {
    return HouseTablePrimaryKey.builder().databaseId(databaseId).tableId(viewId).build();
  }

  public static HouseTable row(String entityType, String metadataLocation) {
    return HouseTable.builder()
        .databaseId(DB)
        .tableId(VIEW)
        .tableLocation(metadataLocation)
        .tableVersion("INITIAL_VERSION")
        .storageType(LOCAL_STORAGE_TYPE)
        .entityType(entityType)
        .build();
  }

  public static HouseTable viewRow(String metadataLocation) {
    return row(ENTITY_TYPE_VIEW, metadataLocation);
  }

  public static HouseTable tableRow(String metadataLocation) {
    return row(ENTITY_TYPE_TABLE, metadataLocation);
  }

  /** Written before the discriminator existed: null means TABLE. */
  public static HouseTable legacyRow(String metadataLocation) {
    return row(null, metadataLocation);
  }
}
