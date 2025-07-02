/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.index

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.locationtech.geomesa.index.api.{FilterStrategy, GeoMesaFeatureIndex, IndexAdapter, QueryPlan, QueryStrategy}
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
import org.locationtech.geomesa.dynamodb.data.util.DynamoDbFeatureSerializer
import software.amazon.awssdk.services.dynamodb.model._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
  * Index adapter for DynamoDB with aggregation support
  */
class DynamoDbIndexAdapter(ds: DynamoDbDataStore) extends IndexAdapter[DynamoDbDataStore] with LazyLogging {

  override def createWriter(
      sft: SimpleFeatureType,
      indices: Seq[GeoMesaFeatureIndex[_, _]],
      partition: Option[String] = None,
      atomic: Boolean = false): IndexAdapter.IndexWriter = {
    
    new DynamoDbIndexWriter(ds, sft, DynamoDbFeatureSerializer.getTableName(ds.config.catalog, ds.config.tablePrefix, sft.getTypeName))
  }

  override def createQueryPlan(strategy: QueryStrategy): QueryPlan[DynamoDbDataStore] = {
    // Create a DynamoDB-specific query plan with aggregation support
    new DynamoDbQueryPlan(ds, strategy)
  }

  override def renameTable(from: String, to: String): Unit = {
    logger.warn(s"Table renaming not supported for DynamoDB: $from -> $to")
    throw new UnsupportedOperationException("Table renaming is not supported in DynamoDB")
  }

  override def clearTables(tables: Seq[String], prefix: Option[Array[Byte]]): Unit = {
    tables.foreach { tableName =>
      try {
        // Scan and delete all items in the table
        val scanRequest = ScanRequest.builder()
          .tableName(tableName)
          .build()
        
        val response = ds.client.scan(scanRequest)
        val items = response.items().asScala
        
        if (items.nonEmpty) {
          // Batch delete items
          val deleteRequests = items.map { item =>
            val key = Map("feature_id" -> item.get("feature_id")).asJava
            val deleteRequest = DeleteRequest.builder().key(key).build()
            WriteRequest.builder().deleteRequest(deleteRequest).build()
          }.toList
          
          val batchRequest = BatchWriteItemRequest.builder()
            .requestItems(Map(tableName -> deleteRequests.asJava).asJava)
            .build()
          
          ds.client.batchWriteItem(batchRequest)
          logger.info(s"Cleared ${items.size} items from table: $tableName")
        }
      } catch {
        case NonFatal(e) =>
          logger.error(s"Failed to clear table: $tableName", e)
          throw e
      }
    }
  }

  override def createTable(index: GeoMesaFeatureIndex[_, _], partition: Option[String], splits: => Seq[Array[Byte]]): Unit = {
    val tableName = getTableName(index, partition)
    
    try {
      // Check if table already exists
      val describeRequest = DescribeTableRequest.builder()
        .tableName(tableName)
        .build()
      
      try {
        ds.client.describeTable(describeRequest)
        logger.info(s"Table already exists: $tableName")
        return
      } catch {
        case _: ResourceNotFoundException =>
          // Table doesn't exist, create it
      }
      
      // Create table with basic schema
      val keySchema = List(
        KeySchemaElement.builder()
          .attributeName("feature_id")
          .keyType(KeyType.HASH)
          .build()
      ).asJava
      
      val attributeDefinitions = List(
        AttributeDefinition.builder()
          .attributeName("feature_id")
          .attributeType(ScalarAttributeType.S)
          .build()
      ).asJava
      
      val provisionedThroughput = ProvisionedThroughput.builder()
        .readCapacityUnits(ds.config.readCapacity.toLong)
        .writeCapacityUnits(ds.config.writeCapacity.toLong)
        .build()
      
      val createRequest = CreateTableRequest.builder()
        .tableName(tableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .provisionedThroughput(provisionedThroughput)
        .build()
      
      ds.client.createTable(createRequest)
      
      // Wait for table to be active
      val waiter = ds.client.waiter()
      val waiterRequest = DescribeTableRequest.builder()
        .tableName(tableName)
        .build()
      
      waiter.waitUntilTableExists(waiterRequest)
      logger.info(s"Successfully created table: $tableName")
      
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to create table: $tableName", e)
        throw e
    }
  }

  override def deleteTables(tables: Seq[String]): Unit = {
    tables.foreach { tableName =>
      try {
        val deleteRequest = DeleteTableRequest.builder()
          .tableName(tableName)
          .build()
        
        ds.client.deleteTable(deleteRequest)
        
        // Wait for table to be deleted
        val waiter = ds.client.waiter()
        val waiterRequest = DescribeTableRequest.builder()
          .tableName(tableName)
          .build()
        
        waiter.waitUntilTableNotExists(waiterRequest)
        logger.info(s"Successfully deleted table: $tableName")
        
      } catch {
        case _: ResourceNotFoundException =>
          logger.info(s"Table does not exist: $tableName")
        case NonFatal(e) =>
          logger.error(s"Failed to delete table: $tableName", e)
          throw e
      }
    }
  }

  private def getTableName(index: GeoMesaFeatureIndex[_, _], partition: Option[String]): String = {
    val baseName = s"${ds.config.tablePrefix}${ds.config.catalog}_${index.name}"
    partition.map(p => s"${baseName}_$p").getOrElse(baseName)
  }
}
