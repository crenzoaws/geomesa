/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.data.DataAccessFactory.Param
import org.geotools.api.data.{DataStore, DataStoreFactorySpi}
import org.locationtech.geomesa.index.audit.AuditWriter
import org.locationtech.geomesa.index.audit.AuditWriter.AuditLogger
import org.locationtech.geomesa.index.geotools.GeoMesaDataStore
import org.locationtech.geomesa.index.geotools.GeoMesaDataStoreFactory.{DataStoreQueryConfig, GeoMesaDataStoreConfig, GeoMesaDataStoreInfo}
import org.locationtech.geomesa.dynamodb.data.index.DynamoDbAgeOff
import org.locationtech.geomesa.security.{AuthUtils, AuthorizationsProvider}
import org.locationtech.geomesa.utils.audit.AuditProvider
import org.locationtech.geomesa.utils.geotools.GeoMesaParam
import software.amazon.awssdk.auth.credentials.{AwsCredentialsProvider, DefaultCredentialsProvider, ProfileCredentialsProvider}
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration
import software.amazon.awssdk.core.retry.RetryPolicy
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.{DynamoDbClient, DynamoDbClientBuilder}
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest

import java.awt.RenderingHints
import java.net.URI
import java.time.Duration
import scala.util.{Failure, Success, Try}

class DynamoDbDataStoreFactory extends DataStoreFactorySpi with LazyLogging {

  import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreParams._

  override def createNewDataStore(params: java.util.Map[String, _]): DataStore = createDataStore(params)

  override def createDataStore(params: java.util.Map[String, _]): DataStore = {
    val client = DynamoDbDataStoreFactory.buildClient(params)
    val config = DynamoDbDataStoreFactory.buildConfig(params)
    val ds = new DynamoDbDataStore(client, config)
    GeoMesaDataStore.initRemoteVersion(ds)
    DynamoDbAgeOff.init(ds)
    ds
  }

  override def isAvailable = true

  override def getDisplayName: String = DynamoDbDataStoreFactory.DisplayName

  override def getDescription: String = DynamoDbDataStoreFactory.Description

  override def getParametersInfo: Array[Param] = Array(DynamoDbDataStoreFactory.ParameterInfo :+ NamespaceParam: _*)

  override def canProcess(params: java.util.Map[String, _]): Boolean =
    DynamoDbDataStoreFactory.canProcess(params)

  override def getImplementationHints: java.util.Map[RenderingHints.Key, _] = null
}

object DynamoDbDataStoreFactory extends GeoMesaDataStoreInfo with LazyLogging {

  import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreParams._

  override val DisplayName = "DynamoDB (GeoMesa)"
  override val Description = "Amazon DynamoDB\u2122 NoSQL database"

  override val ParameterInfo: Array[GeoMesaParam[_ <: AnyRef]] =
    Array(
      DynamoDbRegionParam,
      DynamoDbCatalogParam,
      DynamoDbEndpointParam,
      DynamoDbTablePrefixParam,
      DynamoDbReadCapacityParam,
      DynamoDbWriteCapacityParam,
      DynamoDbBillingModeParam,
      DynamoDbAssumeRoleParam,
      DynamoDbProfileParam,
      DynamoDbMaxRetriesParam,
      DynamoDbRetryDelayParam,
      DynamoDbBatchSizeParam,
      DynamoDbConnectionTimeoutParam,
      DynamoDbSocketTimeoutParam,
      DynamoDbCreateTablesParam,
      QueryThreadsParam,
      QueryTimeoutParam,
      TestConnectionParam,
      GenerateStatsParam,
      AuditQueriesParam,
      LooseBBoxParam,
      PartitionParallelScansParam,
      AuthsParam,
      ForceEmptyAuthsParam
    )

  override def canProcess(params: java.util.Map[String, _]): Boolean =
    DynamoDbCatalogParam.exists(params) && DynamoDbRegionParam.exists(params)

  /**
    * Builds a DynamoDB client from the data store parameters
    *
    * @param params params
    * @return
    */
  def buildClient(params: java.util.Map[String, _]): DynamoDbClient = {
    DynamoDbClientParam.lookupOpt(params).getOrElse {
      val region = Region.of(DynamoDbRegionParam.lookup(params))
      val builder = DynamoDbClient.builder().region(region)

      // Set endpoint if provided (for local testing)
      DynamoDbEndpointParam.lookupOpt(params).foreach { endpoint =>
        builder.endpointOverride(URI.create(endpoint))
      }

      // Configure credentials
      val credentialsProvider = buildCredentialsProvider(params)
      builder.credentialsProvider(credentialsProvider)

      // Configure HTTP client with timeouts and retries
      val httpClientBuilder = ApacheHttpClient.builder()
        .connectionTimeout(java.time.Duration.ofMillis(DynamoDbConnectionTimeoutParam.lookup(params).toMillis))
        .socketTimeout(java.time.Duration.ofMillis(DynamoDbSocketTimeoutParam.lookup(params).toMillis))

      builder.httpClient(httpClientBuilder.build())

      // Configure retry policy
      val retryPolicy = RetryPolicy.builder()
        .numRetries(DynamoDbMaxRetriesParam.lookup(params))
        .build()

      val clientConfig = ClientOverrideConfiguration.builder()
        .retryPolicy(retryPolicy)
        .build()

      builder.overrideConfiguration(clientConfig)

      builder.build()
    }
  }

  /**
    * Builds credentials provider based on parameters
    *
    * @param params data store parameters
    * @return credentials provider
    */
  private def buildCredentialsProvider(params: java.util.Map[String, _]): AwsCredentialsProvider = {
    val baseProvider = DynamoDbProfileParam.lookupOpt(params) match {
      case Some(profile) => ProfileCredentialsProvider.create(profile)
      case None => DefaultCredentialsProvider.create()
    }

    DynamoDbAssumeRoleParam.lookupOpt(params) match {
      case Some(roleArn) =>
        val stsClient = StsClient.builder()
          .region(Region.of(DynamoDbRegionParam.lookup(params)))
          .credentialsProvider(baseProvider)
          .build()

        val assumeRoleRequest = AssumeRoleRequest.builder()
          .roleArn(roleArn)
          .roleSessionName("geomesa-dynamodb-session")
          .build()

        StsAssumeRoleCredentialsProvider.builder()
          .stsClient(stsClient)
          .refreshRequest(assumeRoleRequest)
          .build()

      case None => baseProvider
    }
  }

  /**
    * Builds configuration from data store parameters
    *
    * @param params params
    * @return
    */
  def buildConfig(params: java.util.Map[String, _]): DynamoDbDataStoreConfig = {
    val catalog = DynamoDbCatalogParam.lookup(params)
    val region = DynamoDbRegionParam.lookup(params)
    val tablePrefix = DynamoDbTablePrefixParam.lookup(params)
    val readCapacity = DynamoDbReadCapacityParam.lookup(params)
    val writeCapacity = DynamoDbWriteCapacityParam.lookup(params)
    val billingMode = DynamoDbBillingModeParam.lookup(params)
    val generateStats = GenerateStatsParam.lookup(params)
    val createTables = DynamoDbCreateTablesParam.lookup(params)
    val batchSize = DynamoDbBatchSizeParam.lookup(params)
    val maxRetries = DynamoDbMaxRetriesParam.lookup(params)
    val retryDelay = DynamoDbRetryDelayParam.lookup(params)

    val audit = if (!AuditQueriesParam.lookup(params)) { None } else {
      Some(new AuditLogger("dynamodb", AuditProvider.Loader.loadOrNone(params)))
    }

    // get the auth params passed in as a comma-delimited string
    val authProvider = AuthUtils.getProvider(params,
      AuthsParam.lookupOpt(params).map(_.split(",").toSeq.filterNot(_.isEmpty)).getOrElse(Seq.empty))

    val queries = DynamoDbQueryConfig(
      threads = QueryThreadsParam.lookup(params),
      timeout = QueryTimeoutParam.lookupOpt(params).map(_.toMillis),
      looseBBox = LooseBBoxParam.lookup(params).booleanValue(),
      parallelPartitionScans = PartitionParallelScansParam.lookup(params)
    )

    val ns = Option(NamespaceParam.lookUp(params).asInstanceOf[String])

    DynamoDbDataStoreConfig(
      catalog = catalog,
      region = region,
      tablePrefix = tablePrefix,
      readCapacity = readCapacity,
      writeCapacity = writeCapacity,
      billingMode = billingMode,
      generateStats = generateStats,
      audit = audit,
      authProvider = authProvider,
      queries = queries,
      namespace = ns,
      createTables = createTables,
      batchSize = batchSize,
      maxRetries = maxRetries,
      retryDelay = retryDelay
    )
  }

  case class DynamoDbDataStoreConfig(
      catalog: String,
      region: String,
      tablePrefix: String,
      readCapacity: Int,
      writeCapacity: Int,
      billingMode: String,
      generateStats: Boolean,
      audit: Option[AuditWriter],
      authProvider: AuthorizationsProvider,
      queries: DynamoDbQueryConfig,
      namespace: Option[String],
      createTables: Boolean,
      batchSize: Int,
      maxRetries: Int,
      retryDelay: scala.concurrent.duration.Duration
    ) extends GeoMesaDataStoreConfig

  case class DynamoDbQueryConfig(
      threads: Int,
      timeout: Option[Long],
      looseBBox: Boolean,
      parallelPartitionScans: Boolean
    ) extends DataStoreQueryConfig
}
