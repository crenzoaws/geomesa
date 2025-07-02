/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data

import org.geotools.api.data.{DataStoreFinder, Query, Transaction}
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.geometry.jts.JTSFactoryFinder
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.junit.runner.RunWith
import org.locationtech.jts.geom.{Coordinate, Point}
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName

import java.util.{Date, HashMap => JHashMap}
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class DynamoDbDataStoreIntegrationTest extends Specification with BeforeAfterAll {

  import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreParams._

  sequential

  private var localstack: LocalStackContainer = _
  private val geometryFactory = JTSFactoryFinder.getGeometryFactory

  override def beforeAll(): Unit = {
    // Start LocalStack container with DynamoDB
    localstack = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.0"))
      .withServices(LocalStackContainer.Service.DYNAMODB)
    localstack.start()
  }

  override def afterAll(): Unit = {
    // Give some time for connections to close properly
    Thread.sleep(1000)
    if (localstack != null) {
      localstack.stop()
    }
  }

  "DynamoDbDataStore" should {

    "create and connect to data store" in {
      val params = new JHashMap[String, AnyRef]()
      params.put(DynamoDbCatalogParam.key, "test-catalog")
      params.put(DynamoDbRegionParam.key, "us-east-1")
      params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
      params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
      params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

      val ds = DataStoreFinder.getDataStore(params)
      try {
        ds must not(beNull)
        ds must beAnInstanceOf[DynamoDbDataStore]
        ok
      } finally {
        if (ds != null) ds.dispose()
      }
    }

    "create schema and manage feature types" in {
      val params = new JHashMap[String, AnyRef]()
      params.put(DynamoDbCatalogParam.key, "test-catalog-schema")
      params.put(DynamoDbRegionParam.key, "us-east-1")
      params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
      params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
      params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

      val ds = DataStoreFinder.getDataStore(params)
      
      try {
        // Create feature type
        val sft = createTestFeatureType("test-points")
        ds.createSchema(sft)

        // Verify schema was created
        val retrievedSft = ds.getSchema("test-points")
        retrievedSft must not(beNull)
        retrievedSft.getTypeName must equalTo("test-points")

        // Check type names
        val typeNames = ds.getTypeNames
        typeNames.toSeq must contain("test-points")
        ok

      } finally {
        if (ds != null) ds.dispose()
      }
    }

    "write and read features" in {
      val params = new JHashMap[String, AnyRef]()
      params.put(DynamoDbCatalogParam.key, "test-catalog-2")
      params.put(DynamoDbRegionParam.key, "us-east-1")
      params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
      params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
      params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

      val ds = DataStoreFinder.getDataStore(params)
      
      try {
        // Create feature type
        val sft = createTestFeatureType("test-features")
        ds.createSchema(sft)

        // Write features
        val writer = ds.getFeatureWriterAppend("test-features", Transaction.AUTO_COMMIT)
        
        // Create test feature
        val feature = writer.next()
        feature.setAttribute("name", "Test Point")
        feature.setAttribute("geom", geometryFactory.createPoint(new Coordinate(-77.036, 38.895)))
        feature.setAttribute("dtg", new Date())
        writer.write()
        writer.close()

        // Read features back
        val query = new Query("test-features")
        val reader = ds.getFeatureReader(query, Transaction.AUTO_COMMIT)
        
        var featureCount = 0
        while (reader.hasNext) {
          val readFeature = reader.next()
          readFeature must not(beNull)
          readFeature.getAttribute("name") must equalTo("Test Point")
          featureCount += 1
        }
        reader.close()
        
        featureCount must equalTo(1)
        ok

      } finally {
        ds.dispose()
      }
    }

    "handle batch operations" in {
      val params = new JHashMap[String, AnyRef]()
      params.put(DynamoDbCatalogParam.key, "test-catalog-batch")
      params.put(DynamoDbRegionParam.key, "us-east-1")
      params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
      params.put(DynamoDbBatchSizeParam.key, Integer.valueOf(10))
      params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
      params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

      val ds = DataStoreFinder.getDataStore(params)
      
      try {
        // Create feature type
        val sft = createTestFeatureType("batch-features")
        ds.createSchema(sft)

        // Write multiple features
        val writer = ds.getFeatureWriterAppend("batch-features", Transaction.AUTO_COMMIT)
        
        for (i <- 1 to 25) {
          val feature = writer.next()
          feature.setAttribute("name", s"Batch Point $i")
          feature.setAttribute("geom", geometryFactory.createPoint(new Coordinate(-77.0 + i * 0.001, 38.9 + i * 0.001)))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
        writer.close()

        // Verify all features were written
        val query = new Query("batch-features")
        val reader = ds.getFeatureReader(query, Transaction.AUTO_COMMIT)
        
        var featureCount = 0
        while (reader.hasNext) {
          reader.next()
          featureCount += 1
        }
        reader.close()
        
        featureCount must equalTo(25)
        ok

      } finally {
        ds.dispose()
      }
    }
  }

  private def createTestFeatureType(typeName: String): SimpleFeatureType = {
    val builder = new SimpleFeatureTypeBuilder()
    builder.setName(typeName)
    builder.add("name", classOf[String])
    builder.add("geom", classOf[Point], DefaultGeographicCRS.WGS84)
    builder.add("dtg", classOf[Date])
    builder.buildFeatureType()
  }
}
