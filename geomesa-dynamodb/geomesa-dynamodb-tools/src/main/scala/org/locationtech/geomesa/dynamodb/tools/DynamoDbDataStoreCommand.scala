/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.tools

import com.beust.jcommander.Parameter
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreParams
import org.locationtech.geomesa.tools.DataStoreCommand
import org.locationtech.geomesa.tools.utils.ParameterConverters.DurationConverter

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration

/**
  * Abstract class for DynamoDB commands
  */
trait DynamoDbDataStoreCommand extends DataStoreCommand[org.geotools.api.data.DataStore] {

  override def connection: Map[String, String] = {
    val dsParams = Map.newBuilder[String, String]
    
    dsParams += DynamoDbDataStoreParams.DynamoDbCatalogParam.key -> params.catalog
    dsParams += DynamoDbDataStoreParams.DynamoDbRegionParam.key -> params.region
    
    Option(params.endpoint).foreach(dsParams += DynamoDbDataStoreParams.DynamoDbEndpointParam.key -> _)
    Option(params.tablePrefix).foreach(dsParams += DynamoDbDataStoreParams.DynamoDbTablePrefixParam.key -> _)
    Option(params.readCapacity).foreach(rc => dsParams += DynamoDbDataStoreParams.DynamoDbReadCapacityParam.key -> rc.toString)
    Option(params.writeCapacity).foreach(wc => dsParams += DynamoDbDataStoreParams.DynamoDbWriteCapacityParam.key -> wc.toString)
    Option(params.billingMode).foreach(dsParams += DynamoDbDataStoreParams.DynamoDbBillingModeParam.key -> _)
    Option(params.assumeRole).foreach(dsParams += DynamoDbDataStoreParams.DynamoDbAssumeRoleParam.key -> _)
    Option(params.profile).foreach(dsParams += DynamoDbDataStoreParams.DynamoDbProfileParam.key -> _)
    Option(params.maxRetries).foreach(mr => dsParams += DynamoDbDataStoreParams.DynamoDbMaxRetriesParam.key -> mr.toString)
    Option(params.retryDelay).foreach(rd => dsParams += DynamoDbDataStoreParams.DynamoDbRetryDelayParam.key -> rd.toString)
    Option(params.batchSize).foreach(bs => dsParams += DynamoDbDataStoreParams.DynamoDbBatchSizeParam.key -> bs.toString)
    Option(params.connectionTimeout).foreach(ct => dsParams += DynamoDbDataStoreParams.DynamoDbConnectionTimeoutParam.key -> ct.toString)
    Option(params.socketTimeout).foreach(st => dsParams += DynamoDbDataStoreParams.DynamoDbSocketTimeoutParam.key -> st.toString)
    
    if (params.createTables != null) {
      dsParams += DynamoDbDataStoreParams.DynamoDbCreateTablesParam.key -> params.createTables.toString
    }
    
    dsParams.result()
  }

  override def params: DynamoDbParams
}

trait DynamoDbParams {
  @Parameter(names = Array("--catalog", "-c"), description = "Catalog name", required = true)
  var catalog: String = _

  @Parameter(names = Array("--region"), description = "AWS region", required = true)
  var region: String = _

  @Parameter(names = Array("--endpoint"), description = "DynamoDB endpoint URL (for local testing)")
  var endpoint: String = _

  @Parameter(names = Array("--table-prefix"), description = "Prefix for DynamoDB table names")
  var tablePrefix: String = _

  @Parameter(names = Array("--read-capacity"), description = "Read capacity units for DynamoDB tables")
  var readCapacity: Integer = _

  @Parameter(names = Array("--write-capacity"), description = "Write capacity units for DynamoDB tables")
  var writeCapacity: Integer = _

  @Parameter(names = Array("--billing-mode"), description = "DynamoDB billing mode: PAY_PER_REQUEST or PROVISIONED")
  var billingMode: String = _

  @Parameter(names = Array("--assume-role"), description = "IAM role ARN to assume for DynamoDB access")
  var assumeRole: String = _

  @Parameter(names = Array("--profile"), description = "AWS profile name to use for authentication")
  var profile: String = _

  @Parameter(names = Array("--max-retries"), description = "Maximum number of retries for DynamoDB operations")
  var maxRetries: Integer = _

  @Parameter(names = Array("--retry-delay"), description = "Base delay between retry attempts", converter = classOf[DurationConverter])
  var retryDelay: Duration = _

  @Parameter(names = Array("--batch-size"), description = "Batch size for DynamoDB batch operations")
  var batchSize: Integer = _

  @Parameter(names = Array("--connection-timeout"), description = "Connection timeout for DynamoDB client", converter = classOf[DurationConverter])
  var connectionTimeout: Duration = _

  @Parameter(names = Array("--socket-timeout"), description = "Socket timeout for DynamoDB client", converter = classOf[DurationConverter])
  var socketTimeout: Duration = _

  @Parameter(names = Array("--create-tables"), description = "Automatically create DynamoDB tables if they don't exist")
  var createTables: java.lang.Boolean = _
}
