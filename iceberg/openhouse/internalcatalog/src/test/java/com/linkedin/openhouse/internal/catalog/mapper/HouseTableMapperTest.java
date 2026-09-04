package com.linkedin.openhouse.internal.catalog.mapper;

import static org.mockito.Mockito.*;

import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.local.LocalStorage;
import com.linkedin.openhouse.housetables.client.api.ToggleStatusApi;
import com.linkedin.openhouse.housetables.client.api.UserTableApi;
import com.linkedin.openhouse.housetables.client.invoker.ApiClient;
import com.linkedin.openhouse.housetables.client.model.UserTable;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.model.HouseTable;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepositoryImpl;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.shaded.com.google.common.collect.ImmutableMap;

@SpringBootTest
public class HouseTableMapperTest {

  /**
   * Tests that doesn't care on HTS server should import this test configuration as
   *
   * @import(classes = MockConfiguration.class)
   */
  @TestConfiguration
  public static class MockConfiguration {
    @Bean
    public UserTableApi provideMockHtsApiInstance() {
      // Routing the client to access port from Mock server so that Mock server can respond with
      // stub response.
      ApiClient apiClient = new ApiClient();
      return new UserTableApi(apiClient);
    }

    @Bean
    public ToggleStatusApi provideMockHtsApiInstanceForToggle() {
      // Routing the client to access port from Mock server so that Mock server can respond with
      // stub response.
      ApiClient apiClient = new ApiClient();
      return new ToggleStatusApi(apiClient);
    }

    @Bean
    public HouseTableRepository provideRealHtsRepository() {
      return new HouseTableRepositoryImpl();
    }
  }

  @Autowired protected HouseTableMapper houseTableMapper;

  @Autowired FileIOManager fileIOManager;

  /**
   * The discriminator is required now, but {@code tableVersion} and {@code storageType} are not,
   * and MapStruct routes every String property through the same helper. A read that legitimately
   * omits those two must still map, with the nulls carried through rather than turned into a
   * failure.
   */
  @Test
  public void toHouseTableMapsARowWhoseOptionalStringFieldsAreAbsent() {
    UserTable userTable = new UserTable();
    userTable.setDatabaseId("d1");
    userTable.setTableId("t1");
    userTable.setMetadataLocation("/openhouse/d1/t1/v0.metadata.json");
    userTable.setEntityType("TABLE");
    userTable.setTableVersion(null);
    userTable.setStorageType(null);

    HouseTable houseTable = houseTableMapper.toHouseTable(userTable);

    Assertions.assertEquals("TABLE", houseTable.getEntityType());
    Assertions.assertEquals("d1", houseTable.getDatabaseId());
    Assertions.assertEquals("t1", houseTable.getTableId());
    Assertions.assertEquals("/openhouse/d1/t1/v0.metadata.json", houseTable.getTableLocation());
    Assertions.assertNull(houseTable.getTableVersion());
    Assertions.assertNull(houseTable.getStorageType());
  }

  /**
   * The shared outgoing mapping is the table write path, so it declares TABLE; the view write path
   * gets its own mapping so neither caller can inherit the wrong discriminator by omission.
   */
  @Test
  public void outgoingMappingsDeclareTheirOwnEntityType() {
    HouseTable pointer =
        HouseTable.builder()
            .databaseId("d1")
            .tableId("t1")
            .tableLocation("/openhouse/d1/t1/v0.metadata.json")
            .tableVersion("INITIAL_VERSION")
            .storageType("local")
            .build();

    Assertions.assertEquals("TABLE", houseTableMapper.toUserTable(pointer).getEntityType());
    Assertions.assertEquals("VIEW", houseTableMapper.toUserView(pointer).getEntityType());
    Assertions.assertEquals(
        "/openhouse/d1/t1/v0.metadata.json",
        houseTableMapper.toUserView(pointer).getMetadataLocation());
    Assertions.assertEquals(
        "INITIAL_VERSION", houseTableMapper.toUserView(pointer).getTableVersion());
  }

  @Test
  public void simpleMapperTest() {
    HadoopFileIO fileIO = new HadoopFileIO(new Configuration());
    LocalStorage localStorage = mock(LocalStorage.class);
    when(fileIOManager.getStorage(fileIO)).thenReturn(localStorage);
    when(localStorage.getType()).thenReturn(StorageType.LOCAL);
    HouseTable houseTable =
        houseTableMapper.toHouseTable(
            ImmutableMap.of("databaseId", "openhouse.database", "tableId", "table"), fileIO);
    Assertions.assertEquals("database", houseTable.getDatabaseId());
    Assertions.assertEquals("table", houseTable.getTableId());
    Assertions.assertEquals("local", houseTable.getStorageType());
  }
}
