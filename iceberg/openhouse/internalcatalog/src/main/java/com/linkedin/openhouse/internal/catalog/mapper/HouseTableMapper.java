package com.linkedin.openhouse.internal.catalog.mapper;

import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.IS_OH_PREFIXED;
import static com.linkedin.openhouse.internal.catalog.mapper.HouseTableSerdeUtils.OPENHOUSE_NAMESPACE;

import com.linkedin.openhouse.housetables.client.model.UserTable;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.io.FileIO;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.springframework.beans.factory.annotation.Autowired;

@Mapper(componentModel = "spring")
public abstract class HouseTableMapper {

  /** Canonical House Table discriminator values; the column vocabulary is exactly these two. */
  static final String ENTITY_TYPE_TABLE = "TABLE";

  static final String ENTITY_TYPE_VIEW = "VIEW";

  @Autowired FileIOManager fileIOManager;

  @Mapping(target = "lastModifiedTime", ignore = true)
  @Mapping(
      target = "storageType",
      expression = "java(fileIOManager.getStorage(fileIO).getType().getValue())")
  public abstract HouseTable toHouseTable(Map<String, String> properties, FileIO fileIO);

  public HouseTable toHouseTable(TableMetadata tableMetadata, FileIO fileIO) {
    return toHouseTable(extractRawHTSFields(tableMetadata.properties()), fileIO);
  }

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "databaseId", source = "userTable.databaseId")
  public abstract HouseTable toHouseTableWithDatabaseId(UserTable userTable);

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "databaseId", source = "houseTable.databaseId")
  public abstract UserTable toUserTableWithDatabaseId(HouseTable houseTable);

  @Mappings({
    @Mapping(target = "tableLocation", source = "userTable.metadataLocation"),
    @Mapping(target = "entityType", source = "userTable.entityType")
  })
  public abstract HouseTable toHouseTable(UserTable userTable);

  /**
   * The outgoing table write. Entity type is a required field of the House Table contract, so it is
   * stamped here rather than left for the route to infer; this is also the mapping every caller
   * that builds a table-shaped {@code UserTable} gets, so the discriminator can never be omitted by
   * accident.
   */
  @Mappings({
    @Mapping(target = "metadataLocation", source = "houseTable.tableLocation"),
    @Mapping(target = "entityType", constant = ENTITY_TYPE_TABLE)
  })
  public abstract UserTable toUserTable(HouseTable houseTable);

  /**
   * The outgoing view write. Views share the pointer shape with tables, so they share everything
   * but the discriminator, which is declared explicitly rather than overriding the table mapping
   * after the fact.
   */
  @Mappings({
    @Mapping(target = "metadataLocation", source = "houseTable.tableLocation"),
    @Mapping(target = "entityType", constant = ENTITY_TYPE_VIEW)
  })
  public abstract UserTable toUserView(HouseTable houseTable);

  private Map<String, String> extractRawHTSFields(Map<String, String> input) {
    Map<String, String> output = new HashMap<>();
    for (Map.Entry<String, String> entry : input.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (isHtsField(key)) {
        String newKey = stripOhNamespace(key);
        output.put(newKey, value);
      }
    }
    return output;
  }

  private static boolean isHtsField(String key) {
    return IS_OH_PREFIXED.test(key)
        && HouseTableSerdeUtils.HTS_FIELD_NAMES.contains(stripOhNamespace(key));
  }

  /**
   * MapStruct picks this up as the implicit String-to-String mapping for every string property, and
   * {@code toHouseTable} relies on that to strip the namespace from values as well as keys. It must
   * therefore tolerate null, because {@code tableVersion} and {@code storageType} carry no
   * {@code @NotEmpty} and are legitimately absent on a read.
   */
  static String stripOhNamespace(String key) {
    if (key == null) {
      return null;
    }
    return IS_OH_PREFIXED.test(key) ? key.substring(OPENHOUSE_NAMESPACE.length()) : key;
  }
}
