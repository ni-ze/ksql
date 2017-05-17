/**
 * Copyright 2017 Confluent Inc.
 **/
package io.confluent.ksql.rest.server.resources.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.avro.Schema;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KeyValueMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;

import io.confluent.ksql.KSQLEngine;
import io.confluent.ksql.metastore.KSQLTopic;
import io.confluent.ksql.physical.GenericRow;
import io.confluent.ksql.rest.server.resources.KSQLExceptionMapper;
import io.confluent.ksql.serde.avro.KSQLAvroTopicSerDe;
import io.confluent.ksql.serde.avro.KSQLGenericRowAvroDeserializer;
import io.confluent.ksql.serde.avro.KSQLGenericRowAvroSerializer;
import io.confluent.ksql.util.KSQLException;


public class TopicStreamWriter implements StreamingOutput {

  private static final Logger log = LoggerFactory.getLogger(TopicStreamWriter.class);
  private final Serde valueSerde;
  private final KSQLTopic ksqlTopic;
  private final long interval;
  final long disconnectCheckInterval;
  private final Map<String, Object> ksqlEngineCurrentStreamsProperties;
  private final String avroSchemaString;


  public TopicStreamWriter(final KSQLEngine ksqlEngine, final String topicName, final long
      interval, final long disconnectCheckInterval) {

    this.ksqlEngineCurrentStreamsProperties = ksqlEngine.getStreamsProperties();
    this.interval = interval;
    this.disconnectCheckInterval = disconnectCheckInterval;
    ksqlTopic = ksqlEngine.getMetaStore().getTopic(topicName);
    if (ksqlTopic == null) {
      throw new KSQLException(String.format("Topic %s not found. Make sure the name is spelled "
                                            + "correctly!", topicName));
    }

    if (ksqlTopic.getKsqlTopicSerDe() instanceof KSQLAvroTopicSerDe) {
      KSQLAvroTopicSerDe ksqlAvroTopicSerDe = (KSQLAvroTopicSerDe) ksqlTopic.getKsqlTopicSerDe();
      Map<String, Object> serdeProps = new HashMap<>();
      avroSchemaString = ksqlAvroTopicSerDe.getSchemaString();
      serdeProps.put(KSQLGenericRowAvroSerializer.AVRO_SERDE_SCHEMA_CONFIG, avroSchemaString);

      final Serializer<GenericRow> genericRowSerializer = new KSQLGenericRowAvroSerializer(null);
      genericRowSerializer.configure(serdeProps, false);

      final Deserializer<GenericRow>
          genericRowDeserializer = new KSQLGenericRowAvroDeserializer(null);
      genericRowDeserializer.configure(serdeProps, false);

      valueSerde = Serdes.serdeFrom(genericRowSerializer, genericRowDeserializer);

    } else {
      avroSchemaString = null;
      valueSerde = Serdes.String();
    }

  }

  @Override
  public void write(OutputStream out) throws IOException, WebApplicationException {
    KStreamBuilder builder = new KStreamBuilder();
    String applicationId = String.format("KSQL_PRINT_TOPIC_%d", System.currentTimeMillis());
    Properties streamsProperties = new Properties();
    streamsProperties.putAll(ksqlEngineCurrentStreamsProperties);
    streamsProperties.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);


    if (ksqlTopic.getKsqlTopicSerDe() instanceof KSQLAvroTopicSerDe) {
      KStream<String, GenericRow>
          source =
          builder.stream(Serdes.String(), valueSerde, ksqlTopic.getKafkaTopicName());
      source.map(new KSQLPrintAvroKeyValueMapper(out, interval));
    } else {
      KStream<String, String>
          source =
          builder.stream(Serdes.String(), valueSerde, ksqlTopic.getKafkaTopicName());
      source.map(new KSQLPrintKeyValueMapper(out, interval));
    }

    KafkaStreams streams = new KafkaStreams(builder, streamsProperties);
    streams.start();

    try {
      while (true) {
        Thread.sleep(disconnectCheckInterval);
        synchronized (out) {
          out.write("\n".getBytes());
          out.flush();
        }
      }
    } catch (EOFException exception) {
      // The user has terminated the connection; we can stop writing
    } catch (Throwable exception) {
      log.error("Exception occurred while writing to connection stream: ", exception);
      out.write(("\n" + KSQLExceptionMapper.stackTraceJson(exception).toString() + "\n").getBytes());
      out.flush();
    }
  }

  class KSQLPrintKeyValueMapper
      implements KeyValueMapper<String, String, KeyValue<String, String>> {

    long recordIndex = 0;
    long interval;
    OutputStream out;

    public KSQLPrintKeyValueMapper(OutputStream out, long interval) {
      this.out = out;
      this.interval = interval;
    }

    @Override
    public KeyValue<String, String> apply(String key, String row) {
      try {
        if (interval > 0) {
          if (recordIndex % interval == 0) {
            if (row != null) {
              out.write(row.toString().getBytes());
              out.write("\n".getBytes());
            } else {
              out.write("null\n".getBytes());
            }
          }
        } else {
          if (row != null) {
            out.write(row.toString().getBytes());
            out.write("\n".getBytes());
          } else {
            out.write("null\n".getBytes());
          }
        }

        recordIndex++;
      } catch (Exception exception) {
        // Do nothing!
      }
      return new KeyValue<String, String>(key, row);
    }
  }

  class KSQLPrintAvroKeyValueMapper implements KeyValueMapper<String, GenericRow, KeyValue<String,
      String>> {
    long recordIndex = 0;
    long interval;
    OutputStream out;
    Schema.Parser parser;
    Schema avroSchema;
    ObjectMapper objectMapper;

    public KSQLPrintAvroKeyValueMapper(OutputStream out, long interval) {
      this.out = out;
      this.interval = interval;
      this.parser = new Schema.Parser();
      avroSchema = parser.parse(avroSchemaString);
      this.objectMapper = new ObjectMapper();
    }

    @Override
    public KeyValue<String, String> apply(String key, GenericRow row) {
      try {
        if (interval > 0) {
          if (recordIndex % interval == 0) {
            if (row != null) {
              out.write(getKeyValueBytes(key, row));
              out.write("\n".getBytes());
            } else {
              out.write("null\n".getBytes());
            }
          }
        } else {
          if (row != null) {
            out.write(getKeyValueBytes(key, row));
            out.write("\n".getBytes());
          } else {
            out.write("null\n".getBytes());
          }
        }

        recordIndex++;
      } catch (IOException exception) {
        // Do nothing!
      }
      return new KeyValue<String, String>(key, row.toString());
    }

    private byte[] getKeyValueBytes(String key, GenericRow row) {
      Map<String, Object> rowVals = new HashMap<>();
      for (int i = 0; i < avroSchema.getFields().size(); i++) {
        String fieldName = avroSchema.getFields().get(i).name();
        rowVals.put(fieldName, row.getColumns().get(i));
      }
      Map<String, Object> kvMap = new HashMap<>();
      kvMap.put("values", rowVals);
      kvMap.put("key", key);
      try {
        return objectMapper.writeValueAsBytes(kvMap);
      } catch (JsonProcessingException e) {
        return null;
      }
    }
  }
}
