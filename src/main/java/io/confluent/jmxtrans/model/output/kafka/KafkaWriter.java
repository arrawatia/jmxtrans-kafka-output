/**
 * The MIT License
 * Copyright © 2010 JmxTrans team
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package io.confluent.jmxtrans.model.output.kafka;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.googlecode.jmxtrans.model.Query;
import com.googlecode.jmxtrans.model.Result;
import com.googlecode.jmxtrans.model.Server;
import com.googlecode.jmxtrans.model.ValidationException;
import com.googlecode.jmxtrans.model.output.BaseOutputWriter;
import com.googlecode.jmxtrans.model.output.Settings;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import static com.fasterxml.jackson.core.JsonEncoding.UTF8;
import static com.googlecode.jmxtrans.model.naming.KeyUtils.getKeyString;
import static com.googlecode.jmxtrans.util.NumberUtils.isNumeric;
import static java.util.Arrays.asList;

/**
 * This code is a port of https://github.com/jmxtrans/jmxtrans/blob/master/jmxtrans-output/jmxtrans-output-kafka/src/main/java/com/googlecode/jmxtrans/model/output/kafka/KafkaWriter.java
 * It uses new Java based kafka clients.
 */

public class KafkaWriter extends BaseOutputWriter {

  private static final Logger log = LoggerFactory.getLogger(KafkaWriter.class);

  private static final String DEFAULT_ROOT_PREFIX = "servers";
  private final JsonFactory jsonFactory;

  private KafkaProducer<String, String> producer;
  private final Iterable<String> topics;
  private final String rootPrefix;
  private final ImmutableMap<String, String> tags;

  @JsonCreator
  public KafkaWriter(
      @JsonProperty("typeNames") ImmutableList<String> typeNames,
      @JsonProperty("booleanAsNumber") boolean booleanAsNumber,
      @JsonProperty("rootPrefix") String rootPrefix,
      @JsonProperty("debug") Boolean debugEnabled,
      @JsonProperty("topics") String topics,
      @JsonProperty("tags") Map<String, String> tags,
      @JsonProperty("settings") Map<String, Object> settings) {
    super(typeNames, booleanAsNumber, debugEnabled, settings);
    this.rootPrefix = firstNonNull(
        rootPrefix,
        (String) getSettings().get("rootPrefix"),
        DEFAULT_ROOT_PREFIX);
    // Setting all the required Kafka Properties
    Properties kafkaProperties = new Properties();
    kafkaProperties.setProperty("acks", Settings.getStringSetting(settings, "acks", "all"));
    kafkaProperties.setProperty("retries", Settings.getStringSetting(settings, "retries", "0"));
    kafkaProperties.setProperty("batch.size", Settings.getStringSetting(settings, "batch.size", "16384"));
    kafkaProperties.setProperty("linger.ms", Settings.getStringSetting(settings, "linger.ms", "1"));
    kafkaProperties.setProperty("buffer.memory", Settings.getStringSetting(settings, "buffer.size", "33554432"));
    kafkaProperties.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    kafkaProperties.setProperty("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
    kafkaProperties.setProperty("bootstrap.servers", Settings.getStringSetting(settings, "bootstrap.servers", null));
    this.producer = new KafkaProducer<>(kafkaProperties);
    this.topics = asList(Settings.getStringSetting(settings, "topics", "").split(","));
    this.tags = ImmutableMap.copyOf(firstNonNull(tags, (Map<String, String>) getSettings().get("tags"), ImmutableMap.<String, String>of()));
    jsonFactory = new JsonFactory();
  }

  public void validateSetup(Server server, Query query) throws ValidationException {
  }

  @Override
  protected void internalWrite(Server server, Query query, ImmutableList<Result> results) throws Exception {
    List<String> typeNames = this.getTypeNames();

    for (Result result : results) {
      log.debug("Query result: [{}]", result);
      Map<String, Object> resultValues = result.getValues();
      for (Entry<String, Object> values : resultValues.entrySet()) {
        Object value = values.getValue();
        if (isNumeric(value)) {
          String message = createJsonMessage(server, query, typeNames, result, values, value);
          for (String topic : this.topics) {
            log.debug("Topic: [{}] ; Kafka Message: [{}]", topic, message);
            producer.send(new ProducerRecord<String, String>(topic, message));
          }
        } else {
          log.warn("Unable to submit non-numeric value to Kafka: [{}] from result [{}]", value, result);
        }
      }
    }
  }

  private String createJsonMessage(Server server, Query query, List<String> typeNames, Result result, Entry<String, Object> values, Object value) throws IOException {
    String keyString = getKeyString(server, query, result, values, typeNames, this.rootPrefix);
    String cleanKeyString = keyString.replaceAll("[()]", "_");

    try (
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator generator = jsonFactory.createGenerator(out, UTF8)
    ) {
      generator.writeStartObject();
      generator.writeStringField("keyspace", cleanKeyString);
      generator.writeStringField("value", value.toString());
      generator.writeNumberField("timestamp", result.getEpoch() / 1000);
      generator.writeObjectFieldStart("tags");

      for (Entry<String, String> tag : this.tags.entrySet()) {
        generator.writeStringField(tag.getKey(), tag.getValue());
      }

      generator.writeEndObject();
      generator.writeEndObject();
      generator.close();
      return out.toString("UTF-8");
    }
  }

  @VisibleForTesting
  void setProducer(KafkaProducer<String, String> producer) {
    this.producer = producer;
  }

}