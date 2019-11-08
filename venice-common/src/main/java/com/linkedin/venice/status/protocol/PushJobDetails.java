/**
 * Autogenerated by Avro
 * 
 * DO NOT EDIT DIRECTLY
 */
package com.linkedin.venice.status.protocol;

@SuppressWarnings("all")
public class PushJobDetails extends org.apache.avro.specific.SpecificRecordBase implements org.apache.avro.specific.SpecificRecord {
  public static final org.apache.avro.Schema SCHEMA$ = org.apache.avro.Schema.parse("{\"type\":\"record\",\"name\":\"PushJobDetails\",\"namespace\":\"com.linkedin.venice.status.protocol\",\"fields\":[{\"name\":\"clusterName\",\"type\":\"string\"},{\"name\":\"reportTimestamp\",\"type\":\"long\",\"doc\":\"timestamp for when the reported details were collected\"},{\"name\":\"overallStatus\",\"type\":{\"type\":\"array\",\"items\":{\"type\":\"record\",\"name\":\"PushJobDetailsStatusTuple\",\"fields\":[{\"name\":\"status\",\"type\":\"int\"},{\"name\":\"timestamp\",\"type\":\"long\"}]}}},{\"name\":\"coloStatus\",\"type\":[\"null\",{\"type\":\"map\",\"values\":{\"type\":\"array\",\"items\":\"PushJobDetailsStatusTuple\"}}],\"default\":null},{\"name\":\"pushId\",\"type\":\"string\",\"default\":\"\"},{\"name\":\"partitionCount\",\"type\":\"int\",\"default\":-1},{\"name\":\"valueCompressionStrategy\",\"type\":\"int\",\"doc\":\"0 => NO_OP, 1 => GZIP\",\"default\":0},{\"name\":\"chunkingEnabled\",\"type\":\"boolean\",\"default\":false},{\"name\":\"jobDurationInMs\",\"type\":\"long\",\"default\":-1},{\"name\":\"totalNumberOfRecords\",\"type\":\"long\",\"doc\":\"total number of key value pairs pushed\",\"default\":-1},{\"name\":\"totalKeyBytes\",\"type\":\"long\",\"doc\":\"total amount of key bytes pushed\",\"default\":-1},{\"name\":\"totalRawValueBytes\",\"type\":\"long\",\"doc\":\"total amount of uncompressed value bytes\",\"default\":-1},{\"name\":\"totalCompressedValueBytes\",\"type\":\"long\",\"doc\":\"total amount of \",\"default\":-1},{\"name\":\"pushJobConfigs\",\"type\":[\"null\",{\"type\":\"map\",\"values\":\"string\"}],\"default\":null},{\"name\":\"producerConfigs\",\"type\":[\"null\",{\"type\":\"map\",\"values\":\"string\"}],\"default\":null}]}");
  public java.lang.CharSequence clusterName;
  /** timestamp for when the reported details were collected */
  public long reportTimestamp;
  public java.util.List<com.linkedin.venice.status.protocol.PushJobDetailsStatusTuple> overallStatus;
  public java.util.Map<java.lang.CharSequence,java.util.List<com.linkedin.venice.status.protocol.PushJobDetailsStatusTuple>> coloStatus;
  public java.lang.CharSequence pushId;
  public int partitionCount;
  /** 0 => NO_OP, 1 => GZIP */
  public int valueCompressionStrategy;
  public boolean chunkingEnabled;
  public long jobDurationInMs;
  /** total number of key value pairs pushed */
  public long totalNumberOfRecords;
  /** total amount of key bytes pushed */
  public long totalKeyBytes;
  /** total amount of uncompressed value bytes */
  public long totalRawValueBytes;
  /** total amount of  */
  public long totalCompressedValueBytes;
  public java.util.Map<java.lang.CharSequence,java.lang.CharSequence> pushJobConfigs;
  public java.util.Map<java.lang.CharSequence,java.lang.CharSequence> producerConfigs;
  public org.apache.avro.Schema getSchema() { return SCHEMA$; }
  // Used by DatumWriter.  Applications should not call. 
  public java.lang.Object get(int field$) {
    switch (field$) {
    case 0: return clusterName;
    case 1: return reportTimestamp;
    case 2: return overallStatus;
    case 3: return coloStatus;
    case 4: return pushId;
    case 5: return partitionCount;
    case 6: return valueCompressionStrategy;
    case 7: return chunkingEnabled;
    case 8: return jobDurationInMs;
    case 9: return totalNumberOfRecords;
    case 10: return totalKeyBytes;
    case 11: return totalRawValueBytes;
    case 12: return totalCompressedValueBytes;
    case 13: return pushJobConfigs;
    case 14: return producerConfigs;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
  // Used by DatumReader.  Applications should not call. 
  @SuppressWarnings(value="unchecked")
  public void put(int field$, java.lang.Object value$) {
    switch (field$) {
    case 0: clusterName = (java.lang.CharSequence)value$; break;
    case 1: reportTimestamp = (java.lang.Long)value$; break;
    case 2: overallStatus = (java.util.List<com.linkedin.venice.status.protocol.PushJobDetailsStatusTuple>)value$; break;
    case 3: coloStatus = (java.util.Map<java.lang.CharSequence,java.util.List<com.linkedin.venice.status.protocol.PushJobDetailsStatusTuple>>)value$; break;
    case 4: pushId = (java.lang.CharSequence)value$; break;
    case 5: partitionCount = (java.lang.Integer)value$; break;
    case 6: valueCompressionStrategy = (java.lang.Integer)value$; break;
    case 7: chunkingEnabled = (java.lang.Boolean)value$; break;
    case 8: jobDurationInMs = (java.lang.Long)value$; break;
    case 9: totalNumberOfRecords = (java.lang.Long)value$; break;
    case 10: totalKeyBytes = (java.lang.Long)value$; break;
    case 11: totalRawValueBytes = (java.lang.Long)value$; break;
    case 12: totalCompressedValueBytes = (java.lang.Long)value$; break;
    case 13: pushJobConfigs = (java.util.Map<java.lang.CharSequence,java.lang.CharSequence>)value$; break;
    case 14: producerConfigs = (java.util.Map<java.lang.CharSequence,java.lang.CharSequence>)value$; break;
    default: throw new org.apache.avro.AvroRuntimeException("Bad index");
    }
  }
}