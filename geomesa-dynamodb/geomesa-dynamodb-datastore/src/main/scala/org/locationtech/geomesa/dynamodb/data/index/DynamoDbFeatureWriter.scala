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
import org.locationtech.geomesa.features.{FastSettableFeature, ScalaSimpleFeature}
import org.locationtech.geomesa.index.api.{GeoMesaFeatureIndex, IndexAdapter, WritableFeature}
import org.locationtech.geomesa.index.geotools.GeoMesaFeatureWriter
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
import org.locationtech.geomesa.dynamodb.data.util.DynamoDbFeatureSerializer
import software.amazon.awssdk.services.dynamodb.model._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
  * Feature writer for DynamoDB with single-item operations
  */
class DynamoDbFeatureWriter(
    override val ds: DynamoDbDataStore,
    override val sft: SimpleFeatureType,
    override val indices: Seq[GeoMesaFeatureIndex[_, _]],
    partition: Option[String],
    atomic: Boolean
  ) extends GeoMesaFeatureWriter[DynamoDbDataStore] with LazyLogging {

  private var currentFeature: FastSettableFeature = _
  private var hasNextFeature = false
  private val wrapper = WritableFeature.wrapper(sft, ds.adapter.groups)
  private val tableName = DynamoDbFeatureSerializer.getTableName(ds.config.catalog, ds.config.tablePrefix, sft.getTypeName)

  override def next(): FastSettableFeature = {
    currentFeature = new ScalaSimpleFeature(sft, "")
    hasNextFeature = true
    currentFeature
  }

  override def hasNext(): Boolean = hasNextFeature

  override def write(): Unit = {
    if (currentFeature != null) {
      val writable = wrapper.wrap(currentFeature, delete = false)
      writeFeature(writable)
      hasNextFeature = false
      currentFeature = null
    }
  }

  override def remove(): Unit = {
    if (currentFeature != null) {
      val writable = wrapper.wrap(currentFeature, delete = true)
      deleteFeature(writable)
      hasNextFeature = false
      currentFeature = null
    }
  }

  override protected def getWriter(feature: SimpleFeature): IndexAdapter.IndexWriter = {
    new DynamoDbIndexWriter(ds, sft, tableName)
  }

  private def writeFeature(feature: WritableFeature): Unit = {
    try {
      val writer = getWriter(feature.feature)
      writer.append(feature.feature)
      writer.close()
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error writing feature: ${feature.feature.getID}", e)
        throw e
    }
  }

  private def deleteFeature(feature: WritableFeature): Unit = {
    try {
      val writer = getWriter(feature.feature)
      writer.delete(feature.feature)
      writer.close()
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error deleting feature: ${feature.feature.getID}", e)
        throw e
    }
  }

  override def close(): Unit = {
    // Cleanup resources if needed
  }

  override def flush(): Unit = {
    // No buffering in single-item writer, so nothing to flush
  }
}

/**
  * Batch feature writer for DynamoDB with batch operations
  */
class DynamoDbBatchFeatureWriter(
    override val ds: DynamoDbDataStore,
    override val sft: SimpleFeatureType,
    override val indices: Seq[GeoMesaFeatureIndex[_, _]],
    partition: Option[String]
  ) extends GeoMesaFeatureWriter[DynamoDbDataStore] with LazyLogging {

  private var currentFeature: FastSettableFeature = _
  private var hasNextFeature = false
  private val pendingWrites = scala.collection.mutable.ListBuffer[WritableFeature]()
  private val wrapper = WritableFeature.wrapper(sft, ds.adapter.groups)
  private val tableName = DynamoDbFeatureSerializer.getTableName(ds.config.catalog, ds.config.tablePrefix, sft.getTypeName)
  private val batchSize = ds.config.batchSize

  override def next(): FastSettableFeature = {
    currentFeature = new ScalaSimpleFeature(sft, "")
    hasNextFeature = true
    currentFeature
  }

  override def hasNext(): Boolean = hasNextFeature

  override def write(): Unit = {
    if (currentFeature != null) {
      val writable = wrapper.wrap(currentFeature, delete = false)
      pendingWrites += writable
      hasNextFeature = false
      currentFeature = null
      
      // Flush if batch size reached
      if (pendingWrites.size >= batchSize) {
        flush()
      }
    }
  }

  override def remove(): Unit = {
    if (currentFeature != null) {
      val writable = wrapper.wrap(currentFeature, delete = true)
      pendingWrites += writable
      hasNextFeature = false
      currentFeature = null
      
      // Flush if batch size reached
      if (pendingWrites.size >= batchSize) {
        flush()
      }
    }
  }

  override protected def getWriter(feature: SimpleFeature): IndexAdapter.IndexWriter = {
    new DynamoDbBatchIndexWriter(ds, sft, tableName, batchSize)
  }

  override def flush(): Unit = {
    if (pendingWrites.nonEmpty) {
      try {
        val writer = getWriter(pendingWrites.head.feature).asInstanceOf[DynamoDbBatchIndexWriter]
        pendingWrites.foreach { feature =>
          writer.append(feature.feature)
        }
        writer.flush()
        writer.close()
        pendingWrites.clear()
      } catch {
        case NonFatal(e) =>
          logger.error("Error flushing batch writes", e)
          throw e
      }
    }
  }

  override def close(): Unit = {
    flush()
  }
}

/**
  * Index writer for single DynamoDB operations
  */
class DynamoDbIndexWriter(
    ds: DynamoDbDataStore,
    sft: SimpleFeatureType,
    tableName: String
  ) extends IndexAdapter.IndexWriter with LazyLogging {

  override def append(feature: SimpleFeature): Unit = {
    try {
      val item = DynamoDbFeatureSerializer.featureToItem(feature)
      val request = PutItemRequest.builder()
        .tableName(tableName)
        .item(item)
        .build()
      
      ds.client.putItem(request)
      logger.debug(s"Successfully wrote feature: ${feature.getID}")
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to write feature: ${feature.getID}", e)
        throw e
    }
  }

  override def delete(feature: SimpleFeature): Unit = {
    try {
      val key = DynamoDbFeatureSerializer.getPrimaryKey(feature.getID)
      val request = DeleteItemRequest.builder()
        .tableName(tableName)
        .key(key)
        .build()
      
      ds.client.deleteItem(request)
      logger.debug(s"Successfully deleted feature: ${feature.getID}")
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to delete feature: ${feature.getID}", e)
        throw e
    }
  }

  override def update(updated: SimpleFeature, previous: SimpleFeature): Unit = {
    try {
      // For DynamoDB, update is essentially a put operation
      val item = DynamoDbFeatureSerializer.featureToItem(updated)
      val request = PutItemRequest.builder()
        .tableName(tableName)
        .item(item)
        .build()
      
      ds.client.putItem(request)
      logger.debug(s"Successfully updated feature: ${updated.getID}")
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to update feature: ${updated.getID}", e)
        throw e
    }
  }

  override def flush(): Unit = {
    // Single operations don't need flushing
  }

  override def close(): Unit = {
    // No resources to clean up for single operations
  }
}

/**
  * Index writer for batch DynamoDB operations
  */
class DynamoDbBatchIndexWriter(
    ds: DynamoDbDataStore,
    sft: SimpleFeatureType,
    tableName: String,
    batchSize: Int
  ) extends IndexAdapter.IndexWriter with LazyLogging {

  private val pendingPuts = scala.collection.mutable.ListBuffer[WriteRequest]()
  private val pendingDeletes = scala.collection.mutable.ListBuffer[WriteRequest]()

  override def append(feature: SimpleFeature): Unit = {
    val item = DynamoDbFeatureSerializer.featureToItem(feature)
    val putRequest = PutRequest.builder().item(item).build()
    val writeRequest = WriteRequest.builder().putRequest(putRequest).build()
    
    pendingPuts += writeRequest
    
    if (pendingPuts.size >= batchSize) {
      flushPuts()
    }
  }

  override def delete(feature: SimpleFeature): Unit = {
    val key = DynamoDbFeatureSerializer.getPrimaryKey(feature.getID)
    val deleteRequest = DeleteRequest.builder().key(key).build()
    val writeRequest = WriteRequest.builder().deleteRequest(deleteRequest).build()
    
    pendingDeletes += writeRequest
    
    if (pendingDeletes.size >= batchSize) {
      flushDeletes()
    }
  }

  override def update(updated: SimpleFeature, previous: SimpleFeature): Unit = {
    // For DynamoDB, update is essentially a put operation
    append(updated)
  }

  override def flush(): Unit = {
    flushPuts()
    flushDeletes()
  }

  private def flushPuts(): Unit = {
    if (pendingPuts.nonEmpty) {
      try {
        val requestItems = Map(tableName -> pendingPuts.toList.asJava).asJava
        val request = BatchWriteItemRequest.builder()
          .requestItems(requestItems)
          .build()
        
        ds.client.batchWriteItem(request)
        logger.debug(s"Successfully batch wrote ${pendingPuts.size} features")
        pendingPuts.clear()
      } catch {
        case NonFatal(e) =>
          logger.error(s"Failed to batch write ${pendingPuts.size} features", e)
          throw e
      }
    }
  }

  private def flushDeletes(): Unit = {
    if (pendingDeletes.nonEmpty) {
      try {
        val requestItems = Map(tableName -> pendingDeletes.toList.asJava).asJava
        val request = BatchWriteItemRequest.builder()
          .requestItems(requestItems)
          .build()
        
        ds.client.batchWriteItem(request)
        logger.debug(s"Successfully batch deleted ${pendingDeletes.size} features")
        pendingDeletes.clear()
      } catch {
        case NonFatal(e) =>
          logger.error(s"Failed to batch delete ${pendingDeletes.size} features", e)
          throw e
      }
    }
  }

  override def close(): Unit = {
    flush()
  }
}
