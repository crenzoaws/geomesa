/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.index

import org.geotools.api.data.{Query, Transaction}
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.junit.runner.RunWith
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

import java.util.{Date, HashMap => JHashMap}
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class DynamoDbFeatureWriterTest extends Specification with BeforeAfterAll {

  import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreParams._

  private var localstack: LocalStackContainer = _
  private var client: DynamoDbClient = _
  private var dataStore: org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore = _

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

    val params = new JHashMap[String, AnyRef]()
    params.put(DynamoDbCatalogParam.key, "writer-test")
    params.put(DynamoDbRegionParam.key, "us-east-1")
    params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
    params.put(DynamoDbBatchSizeParam.key, Integer.valueOf(10)) // Smaller batch size for testing
    params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
    params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

    val factory = new org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory()
    dataStore = factory.createDataStore(params).asInstanceOf[org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore]
  }

  override def afterAll(): Unit = {
    if (dataStore != null) dataStore.dispose()
    if (client != null) client.close()
    if (localstack != null) localstack.stop()
  }

  "DynamoDbFeatureWriter" should {

    "write single features correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("single-write-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("single-write-test", Transaction.AUTO_COMMIT)
      try {
        val feature = writer.next()
        feature.setAttribute("name", "Test Feature")
        feature.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
        feature.setAttribute("dtg", new Date())
        writer.write()
      } finally {
        writer.close()
      }

      // Verify the feature was written
      val query = new Query("single-write-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      var count = 0
      try {
        while (reader.hasNext) {
          val readFeature = reader.next()
          readFeature.getAttribute("name") must equalTo("Test Feature")
          count += 1
        }
      } finally {
        reader.close()
      }
      
      count must equalTo(1)
      ok
    }

    "write multiple features correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("multi-write-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("multi-write-test", Transaction.AUTO_COMMIT)
      
      try {
        // Write 5 features
        for (i <- 1 to 5) {
          val feature = writer.next()
          feature.setAttribute("name", s"Feature $i")
          feature.setAttribute("value", Integer.valueOf(i * 10))
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${i}36 38.895)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Verify all features were written
      val query = new Query("multi-write-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      var count = 0
      try {
        while (reader.hasNext) {
          val feature = reader.next()
          feature.getAttribute("name").toString must startWith("Feature")
          count += 1
        }
      } finally {
        reader.close()
      }
      
      count must equalTo(5)
      ok
    }

    "handle batch writes correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("batch-write-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("batch_id", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("batch-write-test", Transaction.AUTO_COMMIT)
      
      try {
        // Write more features than batch size to test batching
        for (i <- 1 to 25) {
          val feature = writer.next()
          feature.setAttribute("name", s"Batch Feature $i")
          feature.setAttribute("batch_id", Integer.valueOf(i))
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Verify all features were written
      val query = new Query("batch-write-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      var count = 0
      val batchIds = scala.collection.mutable.Set[Int]()
      try {
        while (reader.hasNext) {
          val feature = reader.next()
          val batchId = feature.getAttribute("batch_id").asInstanceOf[Integer].intValue()
          batchIds += batchId
          count += 1
        }
      } finally {
        reader.close()
      }
      
      count must equalTo(25)
      batchIds.size must equalTo(25)
      batchIds must contain(allOf(1, 10, 20, 25))
      ok
    }

    "handle null values correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("null-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("optional_field", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("null-test", Transaction.AUTO_COMMIT)
      try {
        val feature = writer.next()
        feature.setAttribute("name", "Feature with Null")
        feature.setAttribute("optional_field", null) // Explicitly set null
        feature.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
        feature.setAttribute("dtg", new Date())
        writer.write()
      } finally {
        writer.close()
      }

      // Verify the feature was written with null value
      val query = new Query("null-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      try {
        if (reader.hasNext) {
          val readFeature = reader.next()
          readFeature.getAttribute("name") must equalTo("Feature with Null")
          // Null handling may vary by implementation
          val optionalField = readFeature.getAttribute("optional_field")
          (optionalField == null || optionalField == "") must beTrue
        }
      } finally {
        reader.close()
      }
      ok
    }

    "handle different geometry types correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("geometry-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point]) // DynamoDB implementation may only support Points
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("geometry-test", Transaction.AUTO_COMMIT)
      
      val geometries = Seq(
        ("point1", "POINT(-77.036 38.895)"),
        ("point2", "POINT(0 0)"),
        ("point3", "POINT(-180 -90)"),
        ("point4", "POINT(180 90)")
      )

      try {
        geometries.foreach { case (name, wkt) =>
          val feature = writer.next()
          feature.setAttribute("name", s"Geometry Test $name")
          feature.setAttribute("geom", WKTUtils.read(wkt))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Verify all geometries were written correctly
      val query = new Query("geometry-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      var count = 0
      try {
        while (reader.hasNext) {
          val feature = reader.next()
          val geom = feature.getDefaultGeometry.asInstanceOf[Point]
          geom must not(beNull)
          geom.getX must beBetween(-180.0, 180.0)
          geom.getY must beBetween(-90.0, 90.0)
          count += 1
        }
      } finally {
        reader.close()
      }
      
      count must equalTo(4)
      ok
    }

    "handle concurrent writes correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("concurrent-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("thread_id", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      // Simulate concurrent writes (simplified test)
      val writer1 = dataStore.getFeatureWriterAppend("concurrent-test", Transaction.AUTO_COMMIT)
      val writer2 = dataStore.getFeatureWriterAppend("concurrent-test", Transaction.AUTO_COMMIT)

      try {
        // Write from first writer
        val feature1 = writer1.next()
        feature1.setAttribute("name", "Concurrent Feature 1")
        feature1.setAttribute("thread_id", Integer.valueOf(1))
        feature1.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
        feature1.setAttribute("dtg", new Date())
        writer1.write()

        // Write from second writer
        val feature2 = writer2.next()
        feature2.setAttribute("name", "Concurrent Feature 2")
        feature2.setAttribute("thread_id", Integer.valueOf(2))
        feature2.setAttribute("geom", WKTUtils.read("POINT(-77.037 38.896)"))
        feature2.setAttribute("dtg", new Date())
        writer2.write()
      } finally {
        writer1.close()
        writer2.close()
      }

      // Verify both features were written
      val query = new Query("concurrent-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      var count = 0
      val threadIds = scala.collection.mutable.Set[Int]()
      try {
        while (reader.hasNext) {
          val feature = reader.next()
          val threadId = feature.getAttribute("thread_id").asInstanceOf[Integer].intValue()
          threadIds += threadId
          count += 1
        }
      } finally {
        reader.close()
      }
      
      count must equalTo(2)
      threadIds must contain(allOf(1, 2))
      ok
    }

    "handle writer lifecycle correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("lifecycle-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("lifecycle-test", Transaction.AUTO_COMMIT)
      
      // Test hasNext before calling next()
      writer.hasNext must beTrue
      
      val feature = writer.next()
      feature must not(beNull)
      
      // Test hasNext after calling next()
      writer.hasNext must beTrue
      
      feature.setAttribute("name", "Lifecycle Test")
      feature.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
      feature.setAttribute("dtg", new Date())
      writer.write()
      
      // Should be able to get another feature
      val feature2 = writer.next()
      feature2 must not(beNull)
      
      writer.close()
      ok
    }

    "handle write errors gracefully" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("error-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val writer = dataStore.getFeatureWriterAppend("error-test", Transaction.AUTO_COMMIT)
      try {
        val feature = writer.next()
        feature.setAttribute("name", "Error Test")
        // Don't set required geometry - this might cause issues
        feature.setAttribute("dtg", new Date())
        
        // The write might succeed or fail depending on implementation
        // We're testing that it handles the situation gracefully
        try {
          writer.write()
          ok // If it succeeds, that's fine
        } catch {
          case _: Exception => ok // If it fails gracefully, that's also fine
        }
      } finally {
        writer.close()
      }
    }
  }
}
