/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.index.metadata.{GeoMesaMetadata, MetadataSerializer}
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
  * DynamoDB-backed metadata implementation
  *
  * @param client DynamoDB client
  * @param catalog catalog name
  * @param tablePrefix table prefix
  * @param serializer metadata serializer
  * @tparam T metadata type
  */
class DynamoDbBackedMetadata[T](
    client: DynamoDbClient,
    catalog: String,
    tablePrefix: String,
    serializer: MetadataSerializer[T]
  ) extends GeoMesaMetadata[T] with LazyLogging {

  private val tableName = s"${tablePrefix}${catalog}_metadata"
  private val cache = new ConcurrentHashMap[String, T]()

  // Ensure metadata table exists
  createTableIfNotExists()

  override def getFeatureTypes: Array[String] = {
    try {
      val request = ScanRequest.builder()
        .tableName(tableName)
        .projectionExpression("feature_type")
        .build()

      val response = client.scan(request)
      response.items().asScala
        .map(_.get("feature_type").s())
        .toSet
        .toArray
    } catch {
      case NonFatal(e) =>
        logger.error("Error getting feature types from DynamoDB", e)
        Array.empty[String]
    }
  }

  override def read(typeName: String, key: String, cache: Boolean = true): Option[T] = {
    val cacheKey = s"$typeName:$key"
    
    if (cache) {
      Option(this.cache.get(cacheKey)) match {
        case some @ Some(_) => some
        case None => readFromDynamoDB(typeName, key).map { value =>
          this.cache.put(cacheKey, value)
          value
        }
      }
    } else {
      readFromDynamoDB(typeName, key)
    }
  }

  private def readFromDynamoDB(typeName: String, key: String): Option[T] = {
    try {
      val request = GetItemRequest.builder()
        .tableName(tableName)
        .key(Map(
          "feature_type" -> AttributeValue.builder().s(typeName).build(),
          "key" -> AttributeValue.builder().s(key).build()
        ).asJava)
        .build()

      val response = client.getItem(request)
      if (response.hasItem) {
        val valueStr = response.item().get("value").s()
        val valueBytes = java.util.Base64.getDecoder.decode(valueStr)
        Some(serializer.deserialize(typeName, valueBytes))
      } else {
        None
      }
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error reading metadata from DynamoDB: $typeName:$key", e)
        None
    }
  }

  override def insert(typeName: String, key: String, value: T): Unit = {
    try {
      val serializedValue = java.util.Base64.getEncoder.encodeToString(serializer.serialize(typeName, value))
      val request = PutItemRequest.builder()
        .tableName(tableName)
        .item(Map(
          "feature_type" -> AttributeValue.builder().s(typeName).build(),
          "key" -> AttributeValue.builder().s(key).build(),
          "value" -> AttributeValue.builder().s(serializedValue).build()
        ).asJava)
        .build()

      client.putItem(request)
      cache.put(s"$typeName:$key", value)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error writing metadata to DynamoDB: $typeName:$key", e)
        throw e
    }
  }

  override def insert(typeName: String, kvPairs: Map[String, T]): Unit = {
    kvPairs.foreach { case (key, value) =>
      insert(typeName, key, value)
    }
  }

  override def remove(typeName: String, key: String): Unit = {
    try {
      val request = DeleteItemRequest.builder()
        .tableName(tableName)
        .key(Map(
          "feature_type" -> AttributeValue.builder().s(typeName).build(),
          "key" -> AttributeValue.builder().s(key).build()
        ).asJava)
        .build()

      client.deleteItem(request)
      cache.remove(s"$typeName:$key")
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error removing metadata from DynamoDB: $typeName:$key", e)
        throw e
    }
  }

  override def remove(typeName: String, keys: Seq[String]): Unit = {
    keys.foreach(key => remove(typeName, key))
  }

  override def delete(typeName: String): Unit = {
    try {
      // First, scan for all items with the given feature type
      val scanRequest = ScanRequest.builder()
        .tableName(tableName)
        .filterExpression("feature_type = :ft")
        .expressionAttributeValues(Map(
          ":ft" -> AttributeValue.builder().s(typeName).build()
        ).asJava)
        .build()

      val response = client.scan(scanRequest)
      
      // Delete each item
      response.items().asScala.foreach { item =>
        val key = item.get("key").s()
        remove(typeName, key)
      }
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error deleting all metadata for type: $typeName", e)
        throw e
    }
  }

  override def scan(typeName: String, prefix: String, cache: Boolean = true): Seq[(String, T)] = {
    try {
      val scanRequest = ScanRequest.builder()
        .tableName(tableName)
        .filterExpression("feature_type = :ft AND begins_with(#k, :prefix)")
        .expressionAttributeNames(Map("#k" -> "key").asJava)
        .expressionAttributeValues(Map(
          ":ft" -> AttributeValue.builder().s(typeName).build(),
          ":prefix" -> AttributeValue.builder().s(prefix).build()
        ).asJava)
        .build()

      val response = client.scan(scanRequest)
      response.items().asScala.map { item =>
        val key = item.get("key").s()
        val valueStr = item.get("value").s()
        val valueBytes = java.util.Base64.getDecoder.decode(valueStr)
        val value = serializer.deserialize(typeName, valueBytes)
        
        if (cache) {
          this.cache.put(s"$typeName:$key", value)
        }
        
        (key, value)
      }.toSeq
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error scanning metadata from DynamoDB: $typeName:$prefix", e)
        Seq.empty
    }
  }

  override def invalidateCache(typeName: String, key: String): Unit = {
    cache.remove(s"$typeName:$key")
  }

  override def backup(typeName: String): Unit = {
    logger.warn(s"Backup not implemented for DynamoDB metadata store: $typeName")
  }

  override def resetCache(): Unit = {
    cache.clear()
  }

  override def close(): Unit = {
    // DynamoDB client should be closed by the caller
    cache.clear()
  }

  private def createTableIfNotExists(): Unit = {
    try {
      // Check if table exists
      val describeRequest = DescribeTableRequest.builder()
        .tableName(tableName)
        .build()
      
      try {
        client.describeTable(describeRequest)
        logger.debug(s"DynamoDB metadata table $tableName already exists")
      } catch {
        case _: ResourceNotFoundException =>
          // Table doesn't exist, create it
          createTable()
      }
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error checking/creating DynamoDB metadata table $tableName", e)
        throw e
    }
  }

  private def createTable(): Unit = {
    logger.info(s"Creating DynamoDB metadata table: $tableName")
    
    val request = CreateTableRequest.builder()
      .tableName(tableName)
      .keySchema(
        KeySchemaElement.builder()
          .attributeName("feature_type")
          .keyType(KeyType.HASH)
          .build(),
        KeySchemaElement.builder()
          .attributeName("key")
          .keyType(KeyType.RANGE)
          .build()
      )
      .attributeDefinitions(
        AttributeDefinition.builder()
          .attributeName("feature_type")
          .attributeType(ScalarAttributeType.S)
          .build(),
        AttributeDefinition.builder()
          .attributeName("key")
          .attributeType(ScalarAttributeType.S)
          .build()
      )
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build()

    client.createTable(request)
    
    // Wait for table to be active
    val waiter = client.waiter()
    waiter.waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build())
    
    logger.info(s"Successfully created DynamoDB metadata table: $tableName")
  }
}
