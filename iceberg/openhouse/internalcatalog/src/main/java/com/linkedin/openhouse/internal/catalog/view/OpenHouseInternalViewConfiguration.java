package com.linkedin.openhouse.internal.catalog.view;

import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.repository.HouseTableRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the view repository and codec only when the Iceberg view API is present. The string
 * form of {@link ConditionalOnClass} is required so Spring can evaluate it without resolving a type
 * that is absent under Iceberg 1.2.
 */
@Configuration
@ConditionalOnClass(name = "org.apache.iceberg.view.ViewMetadata")
public class OpenHouseInternalViewConfiguration {

  @Bean
  public ViewMetadataCodec viewMetadataCodec() {
    return new IcebergViewMetadataCodec();
  }

  @Bean
  public OpenHouseInternalViewRepository openHouseInternalViewRepository(
      HouseTableRepository houseTableRepository,
      FileIOManager fileIOManager,
      ViewMetadataCodec viewMetadataCodec,
      StorageSelector storageSelector,
      StorageType storageType,
      HouseTableMapper houseTableMapper) {
    return new OpenHouseInternalViewRepositoryImpl(
        houseTableRepository,
        fileIOManager,
        viewMetadataCodec,
        storageSelector,
        storageType,
        houseTableMapper);
  }
}
