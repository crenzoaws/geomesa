/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb

import com.typesafe.scalalogging.LazyLogging
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.GeoMesaDataStoreParams
import org.locationtech.geomesa.security.SecurityParams
import org.locationtech.geomesa.utils.conf.GeoMesaSystemProperties.SystemProperty
import org.locationtech.geomesa.utils.geotools.GeoMesaParam
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

package object data extends LazyLogging {

  // keep lazy to allow for runtime setting of the underlying sys prop
  lazy val TransactionBackoffs: IndexedSeq[Int] = try { backoffs(DynamoDbSystemProperties.TransactionBackoff.get) } catch {
    case NonFatal(_) =>
      logger.error(s"Invalid backoff for property '${DynamoDbSystemProperties.TransactionBackoff.property}', using defaults")
      backoffs(DynamoDbSystemProperties.TransactionBackoff.default)
  }

  object DynamoDbDataStoreParams extends GeoMesaDataStoreParams with SecurityParams {
    // disable loose bbox by default, since we don't have any z-iterators
    override protected def looseBBoxDefault = false

    val DynamoDbRegionParam =
      new GeoMesaParam[String](
        "dynamodb.region",
        "AWS region for DynamoDB service",
        optional = false,
        supportsNiFiExpressions = true
      )

    val DynamoDbCatalogParam =
      new GeoMesaParam[String](
        "dynamodb.catalog",
        "The name of the GeoMesa catalog table",
        optional = false,
        supportsNiFiExpressions = true
      )

    val DynamoDbEndpointParam =
      new GeoMesaParam[String](
        "dynamodb.endpoint",
        "DynamoDB endpoint URL (for local testing or custom endpoints)",
        optional = true,
        supportsNiFiExpressions = true
      )

    val DynamoDbTablePrefixParam =
      new GeoMesaParam[String](
        "dynamodb.table.prefix",
        "Prefix for DynamoDB table names",
        optional = true,
        default = "",
        supportsNiFiExpressions = true
      )

    val DynamoDbReadCapacityParam =
      new GeoMesaParam[Integer](
        "dynamodb.read.capacity",
        "Read capacity units for DynamoDB tables",
        default = 5,
        supportsNiFiExpressions = true
      )

    val DynamoDbWriteCapacityParam =
      new GeoMesaParam[Integer](
        "dynamodb.write.capacity",
        "Write capacity units for DynamoDB tables",
        default = 5,
        supportsNiFiExpressions = true
      )

    val DynamoDbBillingModeParam =
      new GeoMesaParam[String](
        "dynamodb.billing.mode",
        "DynamoDB billing mode: PAY_PER_REQUEST or PROVISIONED",
        default = "PAY_PER_REQUEST",
        supportsNiFiExpressions = true
      )

    val DynamoDbAssumeRoleParam =
      new GeoMesaParam[String](
        "dynamodb.assume.role.arn",
        "IAM role ARN to assume for DynamoDB access",
        optional = true,
        supportsNiFiExpressions = true
      )

    val DynamoDbProfileParam =
      new GeoMesaParam[String](
        "dynamodb.profile",
        "AWS profile name to use for authentication",
        optional = true,
        supportsNiFiExpressions = true
      )



    val DynamoDbMaxRetriesParam =
      new GeoMesaParam[Integer](
        "dynamodb.max.retries",
        "Maximum number of retries for DynamoDB operations",
        default = 3,
        supportsNiFiExpressions = true
      )

    val DynamoDbRetryDelayParam =
      new GeoMesaParam[Duration](
        "dynamodb.retry.delay",
        "Base delay between retry attempts",
        default = Duration(100, TimeUnit.MILLISECONDS),
        supportsNiFiExpressions = true
      )

    val DynamoDbBatchSizeParam =
      new GeoMesaParam[Integer](
        "dynamodb.batch.size",
        "Batch size for DynamoDB batch operations",
        default = 25, // DynamoDB batch limit
        supportsNiFiExpressions = true
      )

    val DynamoDbConnectionTimeoutParam =
      new GeoMesaParam[Duration](
        "dynamodb.connection.timeout",
        "Connection timeout for DynamoDB client",
        default = Duration(30, TimeUnit.SECONDS),
        supportsNiFiExpressions = true
      )

    val DynamoDbSocketTimeoutParam =
      new GeoMesaParam[Duration](
        "dynamodb.socket.timeout",
        "Socket timeout for DynamoDB client",
        default = Duration(30, TimeUnit.SECONDS),
        supportsNiFiExpressions = true
      )

    val DynamoDbClientParam = new GeoMesaParam[DynamoDbClient]("dynamodb.client", "DynamoDB client") // generally used for testing

    val DynamoDbCreateTablesParam =
      new GeoMesaParam[java.lang.Boolean](
        "dynamodb.create.tables",
        "Automatically create DynamoDB tables if they don't exist",
        default = Boolean.box(true)
      )

    val TestConnectionParam =
      new GeoMesaParam[java.lang.Boolean](
        "dynamodb.test.connection",
        "Test the connection to DynamoDB on startup",
        default = Boolean.box(false)
      )
  }

  object DynamoDbSystemProperties {
    val WriteBatchSize     = SystemProperty("geomesa.dynamodb.write.batch", "25")
    val TransactionRetries = SystemProperty("geomesa.dynamodb.tx.retry", "10")
    val TransactionPause   = SystemProperty("geomesa.dynamodb.tx.pause", "100ms")
    val TransactionBackoff = SystemProperty("geomesa.dynamodb.tx.backoff", "1,1,2,2,5,10,20")
    val AgeOffInterval     = SystemProperty("geomesa.dynamodb.age.off.interval", "10 minutes")
    val MaxBatchSize       = SystemProperty("geomesa.dynamodb.max.batch.size", "25")
    val DefaultRegion      = SystemProperty("geomesa.dynamodb.default.region", "us-east-1")
  }

  /**
    * Parse backoff property
    *
    * @param prop system property value
    * @return
    */
  private def backoffs(prop: String): IndexedSeq[Int] = {
    val seq = prop.split(",").map(_.toInt)
    require(seq.nonEmpty, "No backoff defined")
    seq
  }
}
