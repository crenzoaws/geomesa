/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.index.utils.DistributedLocking
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
import software.amazon.awssdk.services.dynamodb.model._

import java.io.Closeable
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.{ScheduledExecutorService, ScheduledThreadPoolExecutor, TimeUnit}
import scala.collection.JavaConverters._
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

/**
  * Distributed locking implementation using DynamoDB conditional writes
  */
trait DynamoDbLocking extends DistributedLocking with LazyLogging {

  this: DynamoDbDataStore =>

  private val lockTableName = s"${config.tablePrefix}${config.catalog}_locks"
  private val lockTimeout = Duration(30, TimeUnit.SECONDS) // Lock timeout
  private val lockRefreshInterval = Duration(10, TimeUnit.SECONDS) // Refresh interval
  
  private lazy val executor: ScheduledExecutorService = new ScheduledThreadPoolExecutor(2)
  
  // Ensure lock table exists
  createLockTableIfNotExists()

  override def acquireDistributedLock(key: String): Closeable = {
    acquireDistributedLock(key, lockTimeout.toMillis).getOrElse {
      throw new RuntimeException(s"Could not acquire distributed lock for key: $key")
    }
  }

  override def acquireDistributedLock(key: String, timeOut: Long): Option[Closeable] = {
    val lockId = UUID.randomUUID().toString
    val expirationTime = System.currentTimeMillis() + lockTimeout.toMillis
    
    if (tryAcquireLock(key, lockId, expirationTime)) {
      // Schedule lock refresh
      val refreshTask = executor.scheduleAtFixedRate(
        () => refreshLock(key, lockId),
        lockRefreshInterval.toSeconds,
        lockRefreshInterval.toSeconds,
        TimeUnit.SECONDS
      )
      
      Some(new DynamoDbLockReleasable(key, lockId, refreshTask))
    } else {
      None
    }
  }

  private def tryAcquireLock(key: String, lockId: String, expirationTime: Long): Boolean = {
    try {
      // Try to acquire lock with conditional write
      val request = PutItemRequest.builder()
        .tableName(lockTableName)
        .item(Map(
          "lock_key" -> AttributeValue.builder().s(key).build(),
          "lock_id" -> AttributeValue.builder().s(lockId).build(),
          "expiration_time" -> AttributeValue.builder().n(expirationTime.toString).build(),
          "created_time" -> AttributeValue.builder().n(System.currentTimeMillis().toString).build()
        ).asJava)
        .conditionExpression("attribute_not_exists(lock_key) OR expiration_time < :now")
        .expressionAttributeValues(Map(
          ":now" -> AttributeValue.builder().n(System.currentTimeMillis().toString).build()
        ).asJava)
        .build()

      client.putItem(request)
      logger.debug(s"Successfully acquired lock for key: $key with lockId: $lockId")
      true
    } catch {
      case _: ConditionalCheckFailedException =>
        logger.debug(s"Failed to acquire lock for key: $key - lock already exists")
        false
      case NonFatal(e) =>
        logger.error(s"Error acquiring lock for key: $key", e)
        false
    }
  }

  private def refreshLock(key: String, lockId: String): Unit = {
    try {
      val newExpirationTime = System.currentTimeMillis() + lockTimeout.toMillis
      
      val request = UpdateItemRequest.builder()
        .tableName(lockTableName)
        .key(Map(
          "lock_key" -> AttributeValue.builder().s(key).build()
        ).asJava)
        .updateExpression("SET expiration_time = :exp")
        .conditionExpression("lock_id = :lockId")
        .expressionAttributeValues(Map(
          ":exp" -> AttributeValue.builder().n(newExpirationTime.toString).build(),
          ":lockId" -> AttributeValue.builder().s(lockId).build()
        ).asJava)
        .build()

      client.updateItem(request)
      logger.trace(s"Refreshed lock for key: $key with lockId: $lockId")
    } catch {
      case _: ConditionalCheckFailedException =>
        logger.warn(s"Failed to refresh lock for key: $key - lock may have been taken by another process")
      case NonFatal(e) =>
        logger.error(s"Error refreshing lock for key: $key", e)
    }
  }

  private def releaseLock(key: String, lockId: String): Unit = {
    try {
      val request = DeleteItemRequest.builder()
        .tableName(lockTableName)
        .key(Map(
          "lock_key" -> AttributeValue.builder().s(key).build()
        ).asJava)
        .conditionExpression("lock_id = :lockId")
        .expressionAttributeValues(Map(
          ":lockId" -> AttributeValue.builder().s(lockId).build()
        ).asJava)
        .build()

      client.deleteItem(request)
      logger.debug(s"Successfully released lock for key: $key with lockId: $lockId")
    } catch {
      case _: ConditionalCheckFailedException =>
        logger.warn(s"Failed to release lock for key: $key - lock may have already been released or expired")
      case NonFatal(e) =>
        logger.error(s"Error releasing lock for key: $key", e)
    }
  }

  private def createLockTableIfNotExists(): Unit = {
    try {
      // Check if table exists
      val describeRequest = DescribeTableRequest.builder()
        .tableName(lockTableName)
        .build()
      
      try {
        client.describeTable(describeRequest)
        logger.debug(s"DynamoDB lock table $lockTableName already exists")
      } catch {
        case _: ResourceNotFoundException =>
          // Table doesn't exist, create it
          createLockTable()
      }
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error checking/creating DynamoDB lock table $lockTableName", e)
        throw e
    }
  }

  private def createLockTable(): Unit = {
    logger.info(s"Creating DynamoDB lock table: $lockTableName")
    
    val request = CreateTableRequest.builder()
      .tableName(lockTableName)
      .keySchema(
        KeySchemaElement.builder()
          .attributeName("lock_key")
          .keyType(KeyType.HASH)
          .build()
      )
      .attributeDefinitions(
        AttributeDefinition.builder()
          .attributeName("lock_key")
          .attributeType(ScalarAttributeType.S)
          .build()
      )
      .billingMode(BillingMode.PAY_PER_REQUEST)
      .build()

    client.createTable(request)
    
    // Wait for table to be active
    val waiter = client.waiter()
    waiter.waitUntilTableExists(DescribeTableRequest.builder().tableName(lockTableName).build())
    
    logger.info(s"Successfully created DynamoDB lock table: $lockTableName")
  }

  private class DynamoDbLockReleasable(
      key: String,
      lockId: String,
      refreshTask: java.util.concurrent.ScheduledFuture[_]
    ) extends Closeable {
    
    override def close(): Unit = {
      refreshTask.cancel(false)
      releaseLock(key, lockId)
    }
  }
}
