package com.linkedin.openhouse.internal.catalog.view;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.linkedin.openhouse.cluster.storage.Storage;
import com.linkedin.openhouse.cluster.storage.StorageType;
import com.linkedin.openhouse.cluster.storage.selector.StorageSelector;
import com.linkedin.openhouse.internal.catalog.fileio.FileIOManager;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapper;
import com.linkedin.openhouse.internal.catalog.mapper.HouseTableMapperImpl;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.hadoop.HadoopFileIO;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.view.ViewMetadata;
import org.apache.iceberg.view.ViewMetadataParser;
import org.springframework.test.util.ReflectionTestUtils;

/** Every metadata file under the storage root, candidates included. */
@Getter
public class ViewRepositoryHarness {

  private final Path root;
  private final FileIO fileIO;
  private final Storage storage;
  private final StorageSelector storageSelector;
  private final FileIOManager fileIOManager;
  private final ViewMetadataCodec codec;
  private final ViewMetadataCodec recordingCodec;
  private final List<String> events;
  private final InMemoryViewHouseTableRepository houseTableRepository;
  private final HouseTableMapper houseTableMapper;
  private final OpenHouseInternalViewRepository viewRepository;

  public ViewRepositoryHarness(Path root) {
    this.root = root;
    this.fileIO = new HadoopFileIO(new Configuration());
    this.fileIOManager = mock(FileIOManager.class);
    this.storage = mock(Storage.class);
    this.storageSelector = mock(StorageSelector.class);
    this.codec = spy(new IcebergViewMetadataCodec());
    this.events = Collections.synchronizedList(new ArrayList<>());
    this.recordingCodec = new RecordingViewMetadataCodec(codec, events);
    this.houseTableRepository = new InMemoryViewHouseTableRepository(events);

    when(storage.getType()).thenReturn(StorageType.LOCAL);
    when(storage.allocateTableLocation(
            anyString(), anyString(), anyString(), anyString(), anyMap()))
        .thenAnswer(
            invocation -> {
              String databaseId = invocation.getArgument(0);
              String viewId = invocation.getArgument(1);
              String uuid = invocation.getArgument(2);
              Path allocated = root.resolve(databaseId).resolve(viewId + "-" + uuid);
              try {
                Files.createDirectories(allocated);
              } catch (IOException e) {
                throw new UncheckedIOException(e);
              }
              return allocated.toString();
            });
    when(storageSelector.selectStorage(anyString(), anyString())).thenReturn(storage);
    when(fileIOManager.getFileIO(eq(StorageType.LOCAL))).thenReturn(fileIO);
    when(fileIOManager.getStorage(any(FileIO.class))).thenReturn(storage);

    HouseTableMapperImpl mapper = new HouseTableMapperImpl();
    ReflectionTestUtils.setField(mapper, "fileIOManager", fileIOManager);
    this.houseTableMapper = mapper;

    this.viewRepository = newRepositoryInstance();
  }

  /** A new instance over the same rows and storage, so a load cannot come from process state. */
  public OpenHouseInternalViewRepository newRepositoryInstance() {
    return new OpenHouseInternalViewRepositoryImpl(
        houseTableRepository,
        fileIOManager,
        recordingCodec,
        storageSelector,
        new StorageType(),
        houseTableMapper);
  }

  /** The single ordered log of codec and House Table interactions, in the order they happened. */
  public List<String> events() {
    synchronized (events) {
      return new ArrayList<>(events);
    }
  }

  public void clearEvents() {
    events.clear();
  }

  /** Reads a metadata file back through the real Iceberg parser. */
  public ViewMetadata readMetadata(String metadataLocation) {
    return ViewMetadataParser.read(fileIO.newInputFile(metadataLocation));
  }

  /** Every metadata file that physically exists under the storage root, candidates included. */
  public List<Path> metadataFiles() {
    try (Stream<Path> paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(".metadata.json"))
          .sorted()
          .collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
