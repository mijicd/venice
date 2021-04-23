package com.linkedin.davinci.ingestion.handler;

import com.linkedin.d2.balancer.D2Client;
import com.linkedin.d2.balancer.D2ClientBuilder;
import com.linkedin.davinci.config.VeniceConfigLoader;
import com.linkedin.davinci.config.VeniceStoreConfig;
import com.linkedin.davinci.ingestion.IngestionRequestClient;
import com.linkedin.davinci.ingestion.IngestionService;
import com.linkedin.davinci.ingestion.IngestionUtils;
import com.linkedin.davinci.kafka.consumer.KafkaStoreIngestionService;
import com.linkedin.davinci.notifier.VeniceNotifier;
import com.linkedin.davinci.repository.VeniceMetadataRepositoryBuilder;
import com.linkedin.davinci.stats.AggVersionedStorageEngineStats;
import com.linkedin.davinci.stats.RocksDBMemoryStats;
import com.linkedin.davinci.storage.StorageEngineMetadataService;
import com.linkedin.davinci.storage.StorageMetadataService;
import com.linkedin.davinci.storage.StorageService;
import com.linkedin.venice.CommonConfigKeys;
import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.client.schema.SchemaReader;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixReadOnlyZKSharedSchemaRepository;
import com.linkedin.venice.ingestion.protocol.IngestionMetricsReport;
import com.linkedin.venice.ingestion.protocol.IngestionStorageMetadata;
import com.linkedin.venice.ingestion.protocol.IngestionTaskCommand;
import com.linkedin.venice.ingestion.protocol.IngestionTaskReport;
import com.linkedin.venice.ingestion.protocol.InitializationConfigs;
import com.linkedin.venice.ingestion.protocol.ProcessShutdownCommand;
import com.linkedin.venice.ingestion.protocol.enums.IngestionAction;
import com.linkedin.venice.ingestion.protocol.enums.IngestionCommandType;
import com.linkedin.venice.ingestion.protocol.enums.IngestionComponentType;
import com.linkedin.venice.ingestion.protocol.enums.IngestionReportType;
import com.linkedin.venice.kafka.protocol.state.PartitionState;
import com.linkedin.venice.kafka.protocol.state.StoreVersionState;
import com.linkedin.venice.meta.ClusterInfoProvider;
import com.linkedin.venice.meta.IngestionMetadataUpdateType;
import com.linkedin.venice.meta.IngestionMode;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.SubscriptionBasedReadOnlyStoreRepository;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.offsets.OffsetRecord;
import com.linkedin.venice.security.DefaultSSLFactory;
import com.linkedin.venice.security.SSLFactory;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.InternalAvroSpecificSerializer;
import com.linkedin.venice.stats.AbstractVeniceStats;
import com.linkedin.venice.utils.PropertyBuilder;
import com.linkedin.venice.utils.ReflectUtils;
import com.linkedin.venice.utils.VeniceProperties;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.tehuti.metrics.MetricsRepository;
import java.net.URI;
import java.util.HashMap;
import java.util.Optional;
import org.apache.log4j.Logger;

import static com.linkedin.davinci.ingestion.IngestionUtils.*;
import static com.linkedin.venice.ConfigKeys.*;
import static com.linkedin.venice.client.store.ClientFactory.*;


public class IngestionServiceTaskHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
  private static final Logger logger = Logger.getLogger(IngestionServiceTaskHandler.class);

  private final IngestionService ingestionService;

  public IngestionServiceTaskHandler(IngestionService ingestionService) {
    super();
    this.ingestionService = ingestionService;
    if (logger.isDebugEnabled()) {
      logger.debug("IngestionServiceTaskHandler created for listener service.");
    }
  }

  @Override
  public void channelReadComplete(ChannelHandlerContext ctx) {
    ctx.flush();
  }

  @Override
  protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {
    try {
      IngestionAction action = getIngestionActionFromRequest(msg);
      byte[] result = getDummyContent();
      switch (action) {
        case INIT:
          if (logger.isDebugEnabled()) {
            logger.debug("Received INIT message: " + msg.toString());
          }
          InitializationConfigs initializationConfigs = deserializeIngestionActionRequest(action, readHttpRequestContent(msg));
          handleIngestionInitialization(initializationConfigs);
          break;
        case COMMAND:
          if (logger.isDebugEnabled()) {
            logger.debug("Received COMMAND message " + msg.toString());
          }
          IngestionTaskCommand ingestionTaskCommand = deserializeIngestionActionRequest(action, readHttpRequestContent(msg));
          IngestionTaskReport report = handleIngestionTaskCommand(ingestionTaskCommand);
          result = serializeIngestionActionResponse(action, report);
          break;
        case METRIC:
          if (logger.isDebugEnabled()) {
            logger.debug("Received METRIC message.");
          }
          IngestionMetricsReport metricsReport = handleMetricsRequest();
          result = serializeIngestionActionResponse(action, metricsReport);
          break;
        case HEARTBEAT:
          if (logger.isDebugEnabled()) {
            logger.debug("Received HEARTBEAT message.");
          }
          ingestionService.updateHeartbeatTime();
          break;
        case UPDATE_METADATA:
          if (logger.isDebugEnabled()) {
            logger.debug("Received UPDATE_METADATA message.");
          }
          IngestionStorageMetadata ingestionStorageMetadata = deserializeIngestionActionRequest(action, readHttpRequestContent(msg));
          IngestionTaskReport metadataUpdateReport = handleIngestionStorageMetadataUpdate(ingestionStorageMetadata);
          result = serializeIngestionActionResponse(action, metadataUpdateReport);
          break;
        case SHUTDOWN_COMPONENT:
          logger.info("Received SHUTDOWN_COMPONENT message.");
          ProcessShutdownCommand processShutdownCommand = deserializeIngestionActionRequest(action, readHttpRequestContent(msg));
          IngestionTaskReport shutdownTaskReport = handleProcessShutdownCommand(processShutdownCommand);
          result = serializeIngestionActionResponse(action, shutdownTaskReport);
          break;
        default:
          throw new UnsupportedOperationException("Unrecognized ingestion action: " + action);
      }
      ctx.writeAndFlush(buildHttpResponse(HttpResponseStatus.OK, result));
    } catch (UnsupportedOperationException e) {
      // Here we only handles the bad requests exception. Other errors are handled in exceptionCaught() method.
      logger.error("Caught unrecognized request action:", e);
      ctx.writeAndFlush(buildHttpResponse(HttpResponseStatus.BAD_REQUEST, e.getMessage()));
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
    logger.error("Encounter exception " + cause.getMessage(), cause);
    ctx.writeAndFlush(buildHttpResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, cause.getMessage()));
    ctx.close();
  }

  private void handleIngestionInitialization(InitializationConfigs initializationConfigs) {
    logger.info("Received aggregated configs: " + initializationConfigs.aggregatedConfigs);

    // Put all configs in aggregated configs into the VeniceConfigLoader.
    PropertyBuilder propertyBuilder = new PropertyBuilder();
    initializationConfigs.aggregatedConfigs.forEach((key, value) -> propertyBuilder.put(key.toString(), value));
    /**
     * The reason of not to restore the data partitions during initialization of storage service is:
     * 1. During first fresh start up with no data on disk, we don't need to restore anything
     * 2. During fresh start up with data on disk (aka bootstrap), we will receive messages to subscribe to the partition
     * and it will re-open the partition on demand.
     * 3. During crash recovery restart, partitions that are already ingestion will be opened by parent process and we
     * should not try to open it. The remaining ingestion tasks will open the storage engines.
     */
    propertyBuilder.put(ConfigKeys.SERVER_RESTORE_DATA_PARTITIONS_ENABLED, "false");
    VeniceProperties veniceProperties = propertyBuilder.build();
    VeniceConfigLoader configLoader = new VeniceConfigLoader(veniceProperties, veniceProperties);
    ingestionService.setConfigLoader(configLoader);

    // Initialize D2Client.
    SSLFactory sslFactory;
    D2Client d2Client;
    String d2ZkHosts = veniceProperties.getString(D2_CLIENT_ZK_HOSTS_ADDRESS);
    if (veniceProperties.getBoolean(CommonConfigKeys.SSL_ENABLED, false)) {
      try {
        /**
         * TODO: DefaultSSLFactory is a copy of the ssl factory implementation in a version of container lib,
         * we should construct the same SSL Factory being used in the main process with help of ReflectionUtils.
         */
        sslFactory = new DefaultSSLFactory(veniceProperties.toProperties());
      } catch (Exception e) {
        throw new VeniceException("Encounter exception in constructing DefaultSSLFactory", e);
      }
      d2Client = new D2ClientBuilder()
          .setZkHosts(d2ZkHosts)
          .setIsSSLEnabled(true)
          .setSSLParameters(sslFactory.getSSLParameters())
          .setSSLContext(sslFactory.getSSLContext())
          .build();
    } else {
      d2Client = new D2ClientBuilder().setZkHosts(d2ZkHosts).build();
    }
    startD2Client(d2Client);

    // Create the client config.
    ClientConfig clientConfig = new ClientConfig()
        .setD2Client(d2Client)
        .setD2ServiceName(ClientConfig.DEFAULT_D2_SERVICE_NAME);
    String clusterName = configLoader.getVeniceClusterConfig().getClusterName();

    // Create MetricsRepository
    MetricsRepository metricsRepository = new MetricsRepository();
    ingestionService.setMetricsRepository(metricsRepository);

    // Initialize store/schema repositories.
    VeniceMetadataRepositoryBuilder veniceMetadataRepositoryBuilder = new VeniceMetadataRepositoryBuilder(
        configLoader,
        clientConfig,
        metricsRepository,
        null,
        true);
    ReadOnlyStoreRepository storeRepository = veniceMetadataRepositoryBuilder.getStoreRepo();
    ReadOnlySchemaRepository schemaRepository = veniceMetadataRepositoryBuilder.getSchemaRepo();
    Optional<HelixReadOnlyZKSharedSchemaRepository> helixReadOnlyZKSharedSchemaRepository = veniceMetadataRepositoryBuilder.getReadOnlyZKSharedSchemaRepository();
    ClusterInfoProvider clusterInfoProvider = veniceMetadataRepositoryBuilder.getClusterInfoProvider();
    ingestionService.setStoreRepository(storeRepository);

    SchemaReader partitionStateSchemaReader = ClientFactory.getSchemaReader(
        ClientConfig.cloneConfig(clientConfig).setStoreName(AvroProtocolDefinition.PARTITION_STATE.getSystemStoreName()));
    SchemaReader storeVersionStateSchemaReader = ClientFactory.getSchemaReader(
        ClientConfig.cloneConfig(clientConfig).setStoreName(AvroProtocolDefinition.STORE_VERSION_STATE.getSystemStoreName()));
    InternalAvroSpecificSerializer<PartitionState> partitionStateSerializer = AvroProtocolDefinition.PARTITION_STATE.getSerializer();
    partitionStateSerializer.setSchemaReader(partitionStateSchemaReader);
    ingestionService.setPartitionStateSerializer(partitionStateSerializer);
    InternalAvroSpecificSerializer<StoreVersionState> storeVersionStateSerializer = AvroProtocolDefinition.STORE_VERSION_STATE.getSerializer();
    storeVersionStateSerializer.setSchemaReader(storeVersionStateSchemaReader);
    ingestionService.setStoreVersionStateSerializer(storeVersionStateSerializer);

    // Create RocksDBMemoryStats
    RocksDBMemoryStats rocksDBMemoryStats = configLoader.getVeniceServerConfig().isDatabaseMemoryStatsEnabled() ?
        new RocksDBMemoryStats(metricsRepository, "RocksDBMemoryStats", configLoader.getVeniceServerConfig().getRocksDBServerConfig().isRocksDBPlainTableFormatEnabled()) : null;

    /**
     * Using reflection to create all the stats classes related to ingestion isolation. All these classes extends
     * {@link AbstractVeniceStats} class and takes {@link MetricsRepository} as the only parameter in its constructor.
     */
    for (String ingestionIsolationStatsClassName : veniceProperties.getString(SERVER_INGESTION_ISOLATION_STATS_CLASS_LIST, "").split(",")) {
      if (ingestionIsolationStatsClassName.length() != 0) {
        Class<? extends AbstractVeniceStats> ingestionIsolationStatsClass = ReflectUtils.loadClass(ingestionIsolationStatsClassName);
        if (!ingestionIsolationStatsClass.isAssignableFrom(AbstractVeniceStats.class)) {
          throw new VeniceException("Class: " + ingestionIsolationStatsClassName + " does not extends AbstractVeniceStats");
        }
        AbstractVeniceStats ingestionIsolationStats =
            ReflectUtils.callConstructor(ingestionIsolationStatsClass, new Class<?>[]{MetricsRepository.class}, new Object[]{metricsRepository});
        logger.info("Created Ingestion Isolation stats: " + ingestionIsolationStats.getName());
      } else {
        logger.info("Ingestion isolation stats class name is empty, will skip it.");
      }
    }

    // Create StorageService
    AggVersionedStorageEngineStats storageEngineStats = new AggVersionedStorageEngineStats(metricsRepository, storeRepository);
    StorageService storageService = new StorageService(configLoader, storageEngineStats, rocksDBMemoryStats, storeVersionStateSerializer, partitionStateSerializer);
    storageService.start();
    ingestionService.setStorageService(storageService);

    // Create SchemaReader
    SchemaReader kafkaMessageEnvelopeSchemaReader = getSchemaReader(
        ClientConfig.cloneConfig(clientConfig).setStoreName(AvroProtocolDefinition.KAFKA_MESSAGE_ENVELOPE.getSystemStoreName())
    );

    StorageMetadataService storageMetadataService = new StorageEngineMetadataService(storageService.getStorageEngineRepository(), partitionStateSerializer);
    ingestionService.setStorageMetadataService(storageMetadataService);

    // Create KafkaStoreIngestionService
    KafkaStoreIngestionService storeIngestionService = new KafkaStoreIngestionService(
        storageService.getStorageEngineRepository(),
        configLoader,
        storageMetadataService,
        clusterInfoProvider,
        storeRepository,
        schemaRepository,
        metricsRepository,
        rocksDBMemoryStats,
        Optional.of(kafkaMessageEnvelopeSchemaReader),
        veniceMetadataRepositoryBuilder.isDaVinciClient() ? Optional.empty() : Optional.of(clientConfig),
        partitionStateSerializer,
        helixReadOnlyZKSharedSchemaRepository,
        null);
    storeIngestionService.start();
    storeIngestionService.addCommonNotifier(ingestionListener);
    ingestionService.setStoreIngestionService(storeIngestionService);

    logger.info("Starting report client with target application port: " + configLoader.getVeniceServerConfig().getIngestionApplicationPort());
    // Create Netty client to report status back to application.
    IngestionRequestClient reportClient = new IngestionRequestClient(configLoader.getVeniceServerConfig().getIngestionApplicationPort());
    ingestionService.setReportClient(reportClient);

    // Mark the IngestionService as initiated.
    ingestionService.setInitiated(true);
  }

  private IngestionTaskReport handleIngestionTaskCommand(IngestionTaskCommand ingestionTaskCommand) {
    String topicName = ingestionTaskCommand.topicName.toString();
    int partitionId = ingestionTaskCommand.partitionId;
    String storeName = Version.parseStoreFromKafkaTopicName(topicName);

    IngestionTaskReport report = new IngestionTaskReport();
    report.isPositive = true;
    report.message = "";
    report.topicName = topicName;
    report.partitionId = partitionId;
    try {
      if (!ingestionService.isInitiated()) {
        throw new VeniceException("IngestionService has not been initiated.");
      }
      VeniceStoreConfig storeConfig = ingestionService.getConfigLoader().getStoreConfig(topicName);
      StorageService storageService = ingestionService.getStorageService();
      KafkaStoreIngestionService storeIngestionService = ingestionService.getStoreIngestionService();

      switch (IngestionCommandType.valueOf(ingestionTaskCommand.commandType)) {
        case START_CONSUMPTION:
          IngestionMode ingestionMode = ingestionService.getConfigLoader().getVeniceServerConfig().getIngestionMode();
          if (!ingestionMode.equals(IngestionMode.ISOLATED)) {
            throw new VeniceException("Ingestion isolation is not enabled.");
          }
          ReadOnlyStoreRepository storeRepository = ingestionService.getStoreRepository();
          // For subscription based store repository, we will need to subscribe to the store explicitly.
          if (storeRepository instanceof SubscriptionBasedReadOnlyStoreRepository) {
            logger.info("Ingestion Service subscribing to store: " + storeName);
            ((SubscriptionBasedReadOnlyStoreRepository)storeRepository).subscribe(storeName);
          }
          logger.info("Start ingesting partition: " + partitionId + " of topic: " + topicName);

          storageService.openStoreForNewPartition(storeConfig, partitionId);
          storeIngestionService.startConsumption(storeConfig, partitionId);
          break;
        case STOP_CONSUMPTION:
          storeIngestionService.stopConsumption(storeConfig, partitionId);
          break;
        case KILL_CONSUMPTION:
          storeIngestionService.killConsumptionTask(topicName);
          break;
        case RESET_CONSUMPTION:
          storeIngestionService.resetConsumptionOffset(storeConfig, partitionId);
          break;
        case IS_PARTITION_CONSUMING:
          report.isPositive = storeIngestionService.isPartitionConsuming(storeConfig, partitionId);
          break;
        case REMOVE_STORAGE_ENGINE:
          ingestionService.getStorageService().removeStorageEngine(ingestionTaskCommand.topicName.toString());
          logger.info("Remaining storage engines after dropping: " + ingestionService.getStorageService().getStorageEngineRepository().getAllLocalStorageEngines().toString());
          break;
        case REMOVE_PARTITION:
          if (storeIngestionService.isPartitionConsuming(storeConfig, partitionId)) {
            storeIngestionService.stopConsumptionAndWait(storeConfig, partitionId, 1, 30);
            logger.info("Partition: " + partitionId + " of topic: " + topicName + " has stopped consumption.");
          }
          ingestionService.getStorageService().dropStorePartition(storeConfig, partitionId);
          logger.info("Partition: " + partitionId + " of topic: " + topicName + " has been removed.");
          break;
        case OPEN_STORAGE_ENGINE:
          // Open metadata partition of the store engine.
          storeConfig.setRestoreDataPartitions(false);
          storeConfig.setRestoreMetadataPartition(true);
          ingestionService.getStorageService().openStore(storeConfig);
          logger.info("Metadata partition of topic: " + ingestionTaskCommand.topicName.toString() + " restored.");
          break;
        case PROMOTE_TO_LEADER:
          // This is to avoid the race condition. When partition is being unsubscribed, we should not add it to the action queue, but instead fail the command fast.
          if (ingestionService.isPartitionBeingUnsubscribed(topicName, partitionId)) {
            report.isPositive = false;
          } else {
            storeIngestionService.promoteToLeader(storeConfig, partitionId, ingestionService.getLeaderSectionIdChecker(topicName, partitionId));
            logger.info("Promoting partition: " + partitionId + " of topic: " + topicName + " to leader.");
          }
          break;
        case DEMOTE_TO_STANDBY:
          if (ingestionService.isPartitionBeingUnsubscribed(topicName, partitionId)) {
            report.isPositive = false;
          } else {
            storeIngestionService.demoteToStandby(storeConfig, partitionId, ingestionService.getLeaderSectionIdChecker(topicName, partitionId));
            logger.info("Demoting partition: " + partitionId + " of topic: " + topicName + " to standby.");
          }
          break;
        default:
          break;
      }
    } catch (Exception e) {
      logger.error("Encounter exception while handling ingestion command", e);
      report.isPositive = false;
      report.message = e.getClass().getSimpleName() + "_" + e.getMessage();
    }
    return report;
  }

  private IngestionMetricsReport handleMetricsRequest() {
    IngestionMetricsReport report = new IngestionMetricsReport();
    report.aggregatedMetrics = new HashMap<>();
    if (ingestionService.getMetricsRepository() != null) {
      ingestionService.getMetricsRepository().metrics().forEach((name, metric) -> {
        if (metric != null) {
          try {
            report.aggregatedMetrics.put(name, metric.value());
          } catch (Exception e) {
            String exceptionLogMessage = "Encounter exception when retrieving value of metric: " + name;
            if (!ingestionService.getRedundantExceptionFilter().isRedundantException(exceptionLogMessage)) {
              logger.error(exceptionLogMessage, e);
            }
          }
        }
      });
    }
    return report;
  }

  private IngestionTaskReport handleIngestionStorageMetadataUpdate(IngestionStorageMetadata ingestionStorageMetadata) {
    String topicName = ingestionStorageMetadata.topicName.toString();
    int partitionId = ingestionStorageMetadata.partitionId;

    IngestionTaskReport report = new IngestionTaskReport();
    report.isPositive = true;
    report.message = "";
    report.topicName = topicName;
    report.partitionId = partitionId;
    try {
      if (!ingestionService.isInitiated()) {
        // Short circuit here when ingestion service is not initiated.
        String errorMessage = "IngestionService has not been initiated.";
        logger.error(errorMessage);
        report.isPositive = false;
        report.message = errorMessage;
        return report;
      }
      switch (IngestionMetadataUpdateType.valueOf(ingestionStorageMetadata.metadataUpdateType)) {
        case PUT_OFFSET_RECORD:
          ingestionService.getStorageMetadataService().put(topicName, partitionId, new OffsetRecord(ingestionStorageMetadata.payload.array(), ingestionService.getPartitionStateSerializer()));
          break;
        case CLEAR_OFFSET_RECORD:
          ingestionService.getStorageMetadataService().clearOffset(topicName, partitionId);
          break;
        case PUT_STORE_VERSION_STATE:
          ingestionService.getStorageMetadataService().put(topicName, IngestionUtils.deserializeStoreVersionState(topicName, ingestionStorageMetadata.payload.array()));
          break;
        case CLEAR_STORE_VERSION_STATE:
          ingestionService.getStorageMetadataService().clearStoreVersionState(topicName);
          break;
        default:
          break;
      }
    } catch (Exception e) {
      logger.error("Encounter exception while updating storage metadata", e);
      report.isPositive = false;
      report.message = e.getClass().getSimpleName() + "_" + e.getMessage();
    }
    return report;
  }

  private IngestionTaskReport handleProcessShutdownCommand(ProcessShutdownCommand processShutdownCommand) {
    IngestionTaskReport report = new IngestionTaskReport();
    report.isPositive = true;
    report.message = "";
    report.topicName = "";
    try {
      if (!ingestionService.isInitiated()) {
        throw new VeniceException("IngestionService has not been initiated.");
      }
      switch (IngestionComponentType.valueOf(processShutdownCommand.componentType)) {
        case KAFKA_INGESTION_SERVICE:
          ingestionService.getStoreIngestionService().stop();
          break;
        case STORAGE_SERVICE:
          ingestionService.getStorageService().stop();
          break;
        default:
          break;
      }
    } catch (Exception e) {
      logger.error("Encounter exception while shutting down ingestion components in forked process", e);
      report.isPositive = false;
      report.message = e.getClass().getSimpleName() + "_" + e.getMessage();
    }
    return report;
  }

  private IngestionAction getIngestionActionFromRequest(HttpRequest req){
    // Sometimes req.uri() gives a full uri (eg https://host:port/path) and sometimes it only gives a path
    // Generating a URI lets us always take just the path.
    String[] requestParts = URI.create(req.uri()).getPath().split("/");
    HttpMethod reqMethod = req.method();
    if (!reqMethod.equals(HttpMethod.POST) || requestParts.length < 2) {
      throw new VeniceException("Only able to parse POST requests for actions: init, command, report.  Cannot parse request for: " + req.uri());
    }

    try {
      return IngestionAction.valueOf(requestParts[1].toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new VeniceException("Only able to parse POST requests for actions: init, command, report.  Cannot support action: " + requestParts[1], e);
    }
  }

  private final VeniceNotifier ingestionListener = new VeniceNotifier() {
    @Override
    public void completed(String kafkaTopic, int partitionId, long offset, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.COMPLETED.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);

    }

    @Override
    public void error(String kafkaTopic, int partitionId, String message, Exception e) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.ERROR.getValue();
      report.message = e.getClass().getSimpleName() + "_" + e.getMessage();
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void started(String kafkaTopic, int partitionId, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.STARTED.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void restarted(String kafkaTopic, int partitionId, long offset, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.RESTARTED.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void endOfPushReceived(String kafkaTopic, int partitionId, long offset, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.END_OF_PUSH_RECEIVED.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void startOfBufferReplayReceived(String kafkaTopic, int partitionId, long offset, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.START_OF_BUFFER_REPLAY_RECEIVED.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void startOfIncrementalPushReceived(String kafkaTopic, int partitionId, long offset, String incrementalPushVersion) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.START_OF_INCREMENTAL_PUSH_RECEIVED.getValue();
      report.message = incrementalPushVersion;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void endOfIncrementalPushReceived(String kafkaTopic, int partitionId, long offset, String incrementalPushVersion) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.END_OF_INCREMENTAL_PUSH_RECEIVED.getValue();
      report.message = incrementalPushVersion;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void topicSwitchReceived(String kafkaTopic, int partitionId, long offset, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.TOPIC_SWITCH_RECEIVED.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }

    @Override
    public void progress(String kafkaTopic, int partitionId, long offset, String message) {
      IngestionTaskReport report = new IngestionTaskReport();
      report.reportType = IngestionReportType.PROGRESS.getValue();
      report.message = message;
      report.topicName = kafkaTopic;
      report.partitionId = partitionId;
      report.offset = offset;
      ingestionService.reportIngestionStatus(report);
    }
  };
}
