/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.aggregators

import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.junit.runner.RunWith
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.utils.text.WKTUtils
import org.locationtech.jts.geom.Point
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model._

import java.util.Date
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class DynamoDbAggregatorsTest extends Specification with BeforeAfterAll {

  private var localstack: LocalStackContainer = _
  private var client: DynamoDbClient = _
  private val tableName = "test_aggregators_table"

  override def beforeAll(): Unit = {
    localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
      .withServices(LocalStackContainer.Service.DYNAMODB)
    localstack.start()

    client = DynamoDbClient.builder()
      .endpointOverride(localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB))
      .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(localstack.getAccessKey, localstack.getSecretKey)
      ))
      .region(Region.US_EAST_1)
      .build()

    // Create test table and populate with test data
    createTestTable()
    populateTestData()
  }

  override def afterAll(): Unit = {
    if (client != null) client.close()
    if (localstack != null) localstack.stop()
  }

  private def createTestTable(): Unit = {
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
      .readCapacityUnits(5L)
      .writeCapacityUnits(5L)
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
  }

  private def populateTestData(): Unit = {
    val sftBuilder = new SimpleFeatureTypeBuilder()
    sftBuilder.setName("test-type")
    sftBuilder.add("name", classOf[String])
    sftBuilder.add("category", classOf[String])
    sftBuilder.add("value", classOf[Integer])
    sftBuilder.add("temperature", classOf[java.lang.Double])
    sftBuilder.add("geom", classOf[Point])
    val sft = sftBuilder.buildFeatureType()

    import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
    val config = DynamoDbDataStoreConfig(
      catalog = "test",
      region = "us-east-1",
      tablePrefix = "",
      readCapacity = 5,
      writeCapacity = 5,
      billingMode = "PROVISIONED",
      generateStats = false,
      audit = None,
      authProvider = null,
      queries = null,
      namespace = None,
      createTables = true,
      batchSize = 25,
      maxRetries = 3,
      retryDelay = scala.concurrent.duration.Duration("1 second")
    )

    import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
    val ds = new DynamoDbDataStore(client, config)
    
    import org.locationtech.geomesa.dynamodb.data.index.DynamoDbIndexWriter
    val writer = new DynamoDbIndexWriter(ds, sft, tableName)

    // Create test features with various data patterns
    val testData = Seq(
      ("agg-test-1", "Restaurant", "Italian", 85, 22.5, "POINT(-122.4194 37.7749)"),
      ("agg-test-2", "Restaurant", "Chinese", 92, 25.0, "POINT(-122.4094 37.7849)"),
      ("agg-test-3", "Shop", "Electronics", 78, 20.0, "POINT(-122.4294 37.7649)"),
      ("agg-test-4", "Restaurant", "Mexican", 88, 23.5, "POINT(-122.4394 37.7549)"),
      ("agg-test-5", "Shop", "Clothing", 65, 18.5, "POINT(-122.4494 37.7449)"),
      ("agg-test-6", "Restaurant", "Thai", 95, 26.0, "POINT(-122.4594 37.7349)"),
      ("agg-test-7", "Shop", "Books", 72, 19.0, "POINT(-122.4694 37.7249)"),
      ("agg-test-8", "Restaurant", "French", 90, 24.5, "POINT(-122.4794 37.7149)"),
      ("agg-test-9", "Shop", "Sports", 80, 21.0, "POINT(-122.4894 37.7049)"),
      ("agg-test-10", "Restaurant", "Indian", 87, 23.0, "POINT(-122.4994 37.6949)")
    )

    testData.foreach { case (id, name, category, value, temp, wkt) =>
      val feature = new ScalaSimpleFeature(sft, id)
      feature.setAttribute("name", name)
      feature.setAttribute("category", category)
      feature.setAttribute("value", Integer.valueOf(value))
      feature.setAttribute("temperature", Double.box(temp))
      feature.setAttribute("geom", WKTUtils.read(wkt))
      writer.append(feature)
    }

    writer.close()
    ds.dispose()
  }

  "DynamoDB Stats Aggregator" should {

    "compute basic statistics correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("test-type")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("category", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("temperature", classOf[java.lang.Double])
      sftBuilder.add("geom", classOf[Point])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
      val config = DynamoDbDataStoreConfig(
        catalog = "test",
        region = "us-east-1",
        tablePrefix = "",
        readCapacity = 5,
        writeCapacity = 5,
        billingMode = "PROVISIONED",
        generateStats = false,
        audit = None,
        authProvider = null,
        queries = null,
        namespace = None,
        createTables = true,
        batchSize = 25,
        maxRetries = 3,
        retryDelay = scala.concurrent.duration.Duration("1 second")
      )

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
      val ds = new DynamoDbDataStore(client, config)

      val statsAggregator = new DynamoDbStatsAggregator()
      val results = statsAggregator.query(ds, sft, tableName, Seq("value", "temperature")).toList

      results must not(beEmpty)
      results.size must equalTo(2) // One result per attribute

      // Check value statistics
      val valueStats = results.find(_.getAttribute("attribute_name") == "value")
      valueStats must beSome
      val valueFeature = valueStats.get
      valueFeature.getAttribute("count").asInstanceOf[Long] must equalTo(10L)
      valueFeature.getAttribute("min_value").asInstanceOf[Double] must equalTo(65.0)
      valueFeature.getAttribute("max_value").asInstanceOf[Double] must equalTo(95.0)

      // Check temperature statistics
      val tempStats = results.find(_.getAttribute("attribute_name") == "temperature")
      tempStats must beSome
      val tempFeature = tempStats.get
      tempFeature.getAttribute("count").asInstanceOf[Long] must equalTo(10L)
      tempFeature.getAttribute("min_value").asInstanceOf[Double] must equalTo(18.5)
      tempFeature.getAttribute("max_value").asInstanceOf[Double] must equalTo(26.0)

      ds.dispose()
      ok
    }

    "handle empty results gracefully" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("empty-type")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
      val config = DynamoDbDataStoreConfig(
        catalog = "empty",
        region = "us-east-1",
        tablePrefix = "",
        readCapacity = 5,
        writeCapacity = 5,
        billingMode = "PROVISIONED",
        generateStats = false,
        audit = None,
        authProvider = null,
        queries = null,
        namespace = None,
        createTables = true,
        batchSize = 25,
        maxRetries = 3,
        retryDelay = scala.concurrent.duration.Duration("1 second")
      )

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
      val ds = new DynamoDbDataStore(client, config)

      val statsAggregator = new DynamoDbStatsAggregator()
      val results = statsAggregator.query(ds, sft, "non_existent_table", Seq("value")).toList

      // Should return empty results for non-existent table
      results must beEmpty

      ds.dispose()
      ok
    }
  }

  "DynamoDB Bin Aggregator" should {

    "create spatial bins correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("test-type")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("category", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("temperature", classOf[java.lang.Double])
      sftBuilder.add("geom", classOf[Point])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
      val config = DynamoDbDataStoreConfig(
        catalog = "test",
        region = "us-east-1",
        tablePrefix = "",
        readCapacity = 5,
        writeCapacity = 5,
        billingMode = "PROVISIONED",
        generateStats = false,
        audit = None,
        authProvider = null,
        queries = null,
        namespace = None,
        createTables = true,
        batchSize = 25,
        maxRetries = 3,
        retryDelay = scala.concurrent.duration.Duration("1 second")
      )

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
      val ds = new DynamoDbDataStore(client, config)

      val binAggregator = new DynamoDbBinAggregator()
      val results = binAggregator.query(ds, sft, tableName, gridSize = 8).toList

      results must not(beEmpty)
      
      // Should have bins with counts > 0
      val nonEmptyBins = results.filter(_.getAttribute("count").asInstanceOf[Integer] > 0)
      nonEmptyBins must not(beEmpty)

      // Check bin structure
      results.foreach { bin =>
        bin.getAttribute("bin_x") must not(beNull)
        bin.getAttribute("bin_y") must not(beNull)
        bin.getAttribute("count") must not(beNull)
        bin.getAttribute("center_x") must not(beNull)
        bin.getAttribute("center_y") must not(beNull)
        bin.getAttribute("grid_size").asInstanceOf[Integer] must equalTo(8)
      }

      ds.dispose()
      ok
    }

    "handle different grid sizes" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("test-type")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
      val config = DynamoDbDataStoreConfig(
        catalog = "test",
        region = "us-east-1",
        tablePrefix = "",
        readCapacity = 5,
        writeCapacity = 5,
        billingMode = "PROVISIONED",
        generateStats = false,
        audit = None,
        authProvider = null,
        queries = null,
        namespace = None,
        createTables = true,
        batchSize = 25,
        maxRetries = 3,
        retryDelay = scala.concurrent.duration.Duration("1 second")
      )

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
      val ds = new DynamoDbDataStore(client, config)

      val binAggregator = new DynamoDbBinAggregator()
      
      // Test with different grid sizes
      val results4x4 = binAggregator.query(ds, sft, tableName, gridSize = 4).toList
      val results16x16 = binAggregator.query(ds, sft, tableName, gridSize = 16).toList

      // Larger grid should potentially have more bins (but depends on data distribution)
      results4x4.foreach(_.getAttribute("grid_size").asInstanceOf[Integer] must equalTo(4))
      results16x16.foreach(_.getAttribute("grid_size").asInstanceOf[Integer] must equalTo(16))

      ds.dispose()
      ok
    }
  }

  "DynamoDB Density Aggregator" should {

    "compute density analysis correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("test-type")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("category", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("temperature", classOf[java.lang.Double])
      sftBuilder.add("geom", classOf[Point])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
      val config = DynamoDbDataStoreConfig(
        catalog = "test",
        region = "us-east-1",
        tablePrefix = "",
        readCapacity = 5,
        writeCapacity = 5,
        billingMode = "PROVISIONED",
        generateStats = false,
        audit = None,
        authProvider = null,
        queries = null,
        namespace = None,
        createTables = true,
        batchSize = 25,
        maxRetries = 3,
        retryDelay = scala.concurrent.duration.Duration("1 second")
      )

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
      val ds = new DynamoDbDataStore(client, config)

      val densityAggregator = new DynamoDbDensityAggregator()
      val results = densityAggregator.query(ds, sft, tableName, gridWidth = 8, gridHeight = 8).toList

      results must not(beEmpty)
      results.size must equalTo(1) // Should return one summary result

      val densityResult = results.head
      densityResult.getAttribute("grid_width").asInstanceOf[Integer] must equalTo(8)
      densityResult.getAttribute("grid_height").asInstanceOf[Integer] must equalTo(8)
      densityResult.getAttribute("total_density").asInstanceOf[Double] must beGreaterThan(0.0)
      densityResult.getAttribute("max_density").asInstanceOf[Double] must beGreaterThan(0.0)
      densityResult.getAttribute("avg_density").asInstanceOf[Double] must beGreaterThan(0.0)

      ds.dispose()
      ok
    }

    "support kernel density estimation" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("test-type")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory.DynamoDbDataStoreConfig
      val config = DynamoDbDataStoreConfig(
        catalog = "test",
        region = "us-east-1",
        tablePrefix = "",
        readCapacity = 5,
        writeCapacity = 5,
        billingMode = "PROVISIONED",
        generateStats = false,
        audit = None,
        authProvider = null,
        queries = null,
        namespace = None,
        createTables = true,
        batchSize = 25,
        maxRetries = 3,
        retryDelay = scala.concurrent.duration.Duration("1 second")
      )

      import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
      val ds = new DynamoDbDataStore(client, config)

      val densityAggregator = new DynamoDbDensityAggregator()
      
      // Test with kernel density (radius specified)
      val kernelResults = densityAggregator.query(ds, sft, tableName, gridWidth = 8, gridHeight = 8, radius = Some(0.01)).toList
      
      // Test with simple point density (no radius)
      val pointResults = densityAggregator.query(ds, sft, tableName, gridWidth = 8, gridHeight = 8, radius = None).toList

      kernelResults must not(beEmpty)
      pointResults must not(beEmpty)

      // Both should return density results
      kernelResults.head.getAttribute("total_density").asInstanceOf[Double] must beGreaterThan(0.0)
      pointResults.head.getAttribute("total_density").asInstanceOf[Double] must beGreaterThan(0.0)

      ds.dispose()
      ok
    }
  }
}
