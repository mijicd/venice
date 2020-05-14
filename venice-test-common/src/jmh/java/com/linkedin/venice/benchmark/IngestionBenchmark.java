package com.linkedin.venice.benchmark;

import com.linkedin.davinci.client.DaVinciClient;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceClusterWrapper;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.io.FileUtils;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import static com.linkedin.venice.integration.utils.ServiceFactory.*;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Fork(value = 1)
@Warmup(iterations = 1)
@Measurement(iterations = 1)
public class IngestionBenchmark {
  private static final int numRecords = 100_000;
  private static final int numIngestions = 10;
  private static final String FLOAT_VECTOR_VALUE_SCHEMA = "{" +
      "  \"namespace\" : \"example.avro\",  " +
      "  \"type\": \"record\",   " +
      "  \"name\": \"FloatVector\",     " +
      "  \"fields\": [           " +
      "       { \"name\": \"value\", \"type\": {\"type\": \"array\", \"items\": \"float\"} }  " +
      "  ] " +
      " } ";

  private VeniceClusterWrapper cluster;
  private String storeName;

  @Param({"100"})
  protected String valueLength;

  @Param({"FLOAT_VECTOR"})
  protected String valueType;

  @Setup
  public void setup() throws Exception {
    Utils.thisIsLocalhost();
    cluster = ServiceFactory.getVeniceCluster(1, 1, 1);

    if (valueType.equals("FLOAT_VECTOR")) {
      storeName = buildFloatVectorStore(cluster, Integer.parseInt(valueLength));
    } else {
      throw new VeniceException("Value type " + valueType + " is not supported in benchmark.");
    }
  }

  @TearDown
  public void teardown() throws InterruptedException {
    cluster.close();
  }

  @Benchmark
  @OperationsPerInvocation(numIngestions)
  public void ingestionBenchmarkTest(Blackhole bh) {
    File dataBasePath = TestUtils.getTempDataDirectory();
    for (int i = 0; i < numIngestions; i++) {
      try {
        // Delete and recreate data base folder.
        FileUtils.deleteDirectory(dataBasePath);
        Files.createDirectories(dataBasePath.toPath());
        DaVinciClient<Long, GenericRecord> client =
            getGenericAvroDaVinciClient(storeName, cluster, dataBasePath.toString());
        // Ingest data to local folder.
        client.subscribeToAllPartitions().get(60, TimeUnit.SECONDS);
        client.close();
      } catch (IOException | InterruptedException | ExecutionException | TimeoutException e) {
        throw new VeniceException(e);
      }
    }
  }


  public static void main(String[] args) throws RunnerException {
    org.openjdk.jmh.runner.options.Options opt = new OptionsBuilder()
        .include(IngestionBenchmark.class.getSimpleName())
        .addProfiler(GCProfiler.class)
        .build();
    new Runner(opt).run();
  }

  private String buildFloatVectorStore(VeniceClusterWrapper cluster, int valueSize) throws Exception {
    Schema schema = Schema.parse(FLOAT_VECTOR_VALUE_SCHEMA);
    GenericRecord record = new GenericData.Record(schema);
    List<Float> floatVector = new ArrayList<>();
    for (int i = 0; i < valueSize; i++) {
      floatVector.add((float)(i * 1.0));
    }
    record.put("value", floatVector);
    return cluster.createStore(numRecords, record);
  }
}