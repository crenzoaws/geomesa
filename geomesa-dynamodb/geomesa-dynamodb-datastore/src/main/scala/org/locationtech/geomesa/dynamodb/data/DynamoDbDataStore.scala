/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.data.Query
import org.geotools.api.feature.simple.SimpleFeatureType
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.index.metadata.{GeoMesaMetadata, MetadataStringSerializer}
import org.locationtech.geomesa.index.stats.GeoMesaStats
import org.locationtech.geomesa.index.utils._
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
import org.locationtech.geomesa.dynamodb.data.index.{DynamoDbAgeOff, DynamoDbIndexAdapter, DynamoDbQueryPlan}
import org.locationtech.geomesa.dynamodb.data.util.{DynamoDbBackedMetadata, DynamoDbGeoMesaStats, DynamoDbLocking}
import org.locationtech.geomesa.utils.index.VisibilityLevel
import org.locationtech.geomesa.utils.io.CloseWithLogging
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException

import scala.util.control.NonFatal

/**
  * Data store backed by DynamoDB. Uses DynamoDB's native indexing and query capabilities
  * for efficient geospatial operations
  *
  * @param client DynamoDB client
  * @param config datastore configuration
  */
class DynamoDbDataStore(val client: DynamoDbClient, override val config: DynamoDbDataStoreConfig)
    extends GeoMesaDataStore[DynamoDbDataStore](config) with DynamoDbLocking with LazyLogging {

  import org.locationtech.geomesa.utils.geotools.RichAttributeDescriptors.RichAttributeDescriptor
  import org.locationtech.geomesa.utils.geotools.RichSimpleFeatureType.RichSimpleFeatureType

  import scala.collection.JavaConverters._

  override val metadata: GeoMesaMetadata[String] =
    new DynamoDbBackedMetadata(client, config.catalog, config.tablePrefix, MetadataStringSerializer)

  override val adapter: DynamoDbIndexAdapter = new DynamoDbIndexAdapter(this)

  override val stats: GeoMesaStats = DynamoDbGeoMesaStats(this)

  private [dynamodb] val aging = new DynamoDbAgeOff(this)

  @throws(classOf[IllegalArgumentException])
  override protected def preSchemaCreate(sft: SimpleFeatureType): Unit = {
    if (sft.getVisibilityLevel == VisibilityLevel.Attribute) {
      throw new IllegalArgumentException("Attribute level visibility is not supported in this store")
    }

    sft.getAttributeDescriptors.asScala.foreach { descriptor =>
      if (descriptor.getColumnGroups().nonEmpty) {
        throw new IllegalArgumentException("Column groups are not supported in this store")
      }
    }

    // disable shards for DynamoDB - we'll use DynamoDB's native partitioning
    sft.setZ2Shards(0)
    sft.setZ3Shards(0)
    sft.setIdShards(0)
    sft.setAttributeShards(0)

    super.preSchemaCreate(sft)
  }

  override protected def onSchemaCreated(sft: SimpleFeatureType): Unit = {
    super.onSchemaCreated(sft)
    
    // Create DynamoDB table for the feature type
    createFeatureTable(sft)
    
    aging.add(sft)
  }
  
  private def createFeatureTable(sft: SimpleFeatureType): Unit = {
    import org.locationtech.geomesa.dynamodb.data.util.DynamoDbFeatureSerializer
    import software.amazon.awssdk.services.dynamodb.model._
    import scala.collection.JavaConverters._
    
    val tableName = DynamoDbFeatureSerializer.getTableName(config.catalog, config.tablePrefix, sft.getTypeName)
    
    try {
      // Check if table already exists
      val describeRequest = DescribeTableRequest.builder()
        .tableName(tableName)
        .build()
      
      try {
        client.describeTable(describeRequest)
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
        .readCapacityUnits(config.readCapacity.toLong)
        .writeCapacityUnits(config.writeCapacity.toLong)
        .build()
      
      val createRequest = CreateTableRequest.builder()
        .tableName(tableName)
        .keySchema(keySchema)
        .attributeDefinitions(attributeDefinitions)
        .provisionedThroughput(provisionedThroughput)
        .build()
      
      client.createTable(createRequest)
      
      // Wait for table to be active
      val waiter = client.waiter()
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

  override protected def onSchemaUpdated(sft: SimpleFeatureType, previous: SimpleFeatureType): Unit = {
    super.onSchemaUpdated(sft, previous)
    aging.update(sft, previous)
  }

  override protected def onSchemaDeleted(sft: SimpleFeatureType): Unit = {
    super.onSchemaDeleted(sft)
    aging.remove(sft)
  }

  override def getQueryPlan(query: Query, index: Option[String], explainer: Explainer): Seq[DynamoDbQueryPlan] =
    super.getQueryPlan(query, index, explainer).asInstanceOf[Seq[DynamoDbQueryPlan]]

  override def dispose(): Unit = {
    CloseWithLogging(client)
    CloseWithLogging(aging)
    super.dispose()
  }
}
