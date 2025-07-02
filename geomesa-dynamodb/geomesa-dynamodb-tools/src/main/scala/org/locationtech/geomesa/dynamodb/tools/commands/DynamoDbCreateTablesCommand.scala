/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.tools.commands

import com.beust.jcommander.{JCommander, Parameter, Parameters}
import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory
import org.locationtech.geomesa.dynamodb.tools.DynamoDbDataStoreCommand
import org.locationtech.geomesa.dynamodb.tools.commands.DynamoDbCreateTablesCommand.DynamoDbCreateTablesParams
import org.locationtech.geomesa.tools.{Command, RequiredTypeNameParam}
import software.amazon.awssdk.services.dynamodb.model._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

class DynamoDbCreateTablesCommand extends DynamoDbDataStoreCommand with LazyLogging {

  override val name = "create-tables"
  override val params = new DynamoDbCreateTablesParams

  override def execute(): Unit = {
    val ds = new DynamoDbDataStoreFactory().createDataStore(dsParams.asJava)
    
    try {
      if (params.typeName != null) {
        // Create tables for specific feature type
        createTablesForFeatureType(ds, params.typeName)
      } else {
        // Create tables for all feature types
        ds.getTypeNames.foreach(createTablesForFeatureType(ds, _))
      }
    } finally {
      ds.dispose()
    }
  }

  private def createTablesForFeatureType(ds: org.geotools.api.data.DataStore, typeName: String): Unit = {
    try {
      val sft = ds.getSchema(typeName)
      if (sft == null) {
        Command.user.error(s"Feature type '$typeName' does not exist")
        return
      }

      Command.user.info(s"Creating tables for feature type: $typeName")
      
      // Get the DynamoDB data store to access the client
      val dynamoDs = ds.asInstanceOf[org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore]
      
      // Create metadata table
      createMetadataTable(dynamoDs)
      
      // Create lock table
      createLockTable(dynamoDs)
      
      // Create index tables based on the feature type
      createIndexTables(dynamoDs, sft)
      
      Command.user.info(s"Successfully created tables for feature type: $typeName")
      
    } catch {
      case NonFatal(e) =>
        Command.user.error(s"Error creating tables for feature type '$typeName': ${e.getMessage}")
        logger.error(s"Error creating tables for feature type '$typeName'", e)
    }
  }

  private def createMetadataTable(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore): Unit = {
    val tableName = s"${ds.config.tablePrefix}${ds.config.catalog}_metadata"
    
    if (!tableExists(ds, tableName)) {
      Command.user.info(s"Creating metadata table: $tableName")
      
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
        .billingMode(getBillingMode(ds))
      
      if (ds.config.billingMode == "PROVISIONED") {
        request.provisionedThroughput(
          ProvisionedThroughput.builder()
            .readCapacityUnits(ds.config.readCapacity.toLong)
            .writeCapacityUnits(ds.config.writeCapacity.toLong)
            .build()
        )
      }
      
      ds.client.createTable(request.build())
      waitForTableActive(ds, tableName)
    }
  }

  private def createLockTable(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore): Unit = {
    val tableName = s"${ds.config.tablePrefix}${ds.config.catalog}_locks"
    
    if (!tableExists(ds, tableName)) {
      Command.user.info(s"Creating lock table: $tableName")
      
      val request = CreateTableRequest.builder()
        .tableName(tableName)
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
        .billingMode(BillingMode.PAY_PER_REQUEST) // Always use pay-per-request for locks
        .build()
      
      ds.client.createTable(request)
      waitForTableActive(ds, tableName)
    }
  }

  private def createIndexTables(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore, sft: org.geotools.api.feature.simple.SimpleFeatureType): Unit = {
    // Create tables for different index types
    val typeName = sft.getTypeName
    val indexTypes = Seq("z2", "z3", "id", "attr") // Common GeoMesa index types
    
    indexTypes.foreach { indexType =>
      val tableName = s"${ds.config.tablePrefix}${ds.config.catalog}_${typeName}_$indexType"
      
      if (!tableExists(ds, tableName)) {
        Command.user.info(s"Creating index table: $tableName")
        createIndexTable(ds, tableName)
      }
    }
  }

  private def createIndexTable(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore, tableName: String): Unit = {
    val request = CreateTableRequest.builder()
      .tableName(tableName)
      .keySchema(
        KeySchemaElement.builder()
          .attributeName("pk")
          .keyType(KeyType.HASH)
          .build(),
        KeySchemaElement.builder()
          .attributeName("sk")
          .keyType(KeyType.RANGE)
          .build()
      )
      .attributeDefinitions(
        AttributeDefinition.builder()
          .attributeName("pk")
          .attributeType(ScalarAttributeType.S)
          .build(),
        AttributeDefinition.builder()
          .attributeName("sk")
          .attributeType(ScalarAttributeType.S)
          .build()
      )
      .billingMode(getBillingMode(ds))
    
    if (ds.config.billingMode == "PROVISIONED") {
      request.provisionedThroughput(
        ProvisionedThroughput.builder()
          .readCapacityUnits(ds.config.readCapacity.toLong)
          .writeCapacityUnits(ds.config.writeCapacity.toLong)
          .build()
      )
    }
    
    ds.client.createTable(request.build())
    waitForTableActive(ds, tableName)
  }

  private def tableExists(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore, tableName: String): Boolean = {
    try {
      val request = DescribeTableRequest.builder().tableName(tableName).build()
      ds.client.describeTable(request)
      true
    } catch {
      case _: ResourceNotFoundException => false
      case NonFatal(e) =>
        logger.warn(s"Error checking if table $tableName exists", e)
        false
    }
  }

  private def waitForTableActive(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore, tableName: String): Unit = {
    Command.user.info(s"Waiting for table $tableName to become active...")
    val waiter = ds.client.waiter()
    waiter.waitUntilTableExists(DescribeTableRequest.builder().tableName(tableName).build())
    Command.user.info(s"Table $tableName is now active")
  }

  private def getBillingMode(ds: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore): BillingMode = {
    ds.config.billingMode.toUpperCase match {
      case "PROVISIONED" => BillingMode.PROVISIONED
      case _ => BillingMode.PAY_PER_REQUEST
    }
  }
}

object DynamoDbCreateTablesCommand {
  @Parameters(commandDescription = "Create DynamoDB tables for GeoMesa feature types")
  class DynamoDbCreateTablesParams extends RequiredTypeNameParam {
    @Parameter(names = Array("--type-name", "-t"), description = "Feature type name (optional - if not provided, creates tables for all feature types)")
    var typeName: String = _
  }
}
