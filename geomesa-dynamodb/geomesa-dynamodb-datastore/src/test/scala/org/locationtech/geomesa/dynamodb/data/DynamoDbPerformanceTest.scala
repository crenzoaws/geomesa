/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data

import org.geotools.api.data.{Query, Transaction}
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.filter.text.cql2.CQL
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
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.JavaConverters._
import scala.util.Random

@RunWith(classOf[JUnitRunner])
class DynamoDbPerformanceTest extends Specification with BeforeAfterAll {

  import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreParams._

  private var localstack: LocalStackContainer = _
  private var client: DynamoDbClient = _
  private var dataStore: DynamoDbDataStore = _

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
    params.put(DynamoDbCatalogParam.key, "performance-test")
    params.put(DynamoDbRegionParam.key, "us-east-1")
    params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
    params.put(DynamoDbBatchSizeParam.key, Integer.valueOf(25)) // Maximum batch size
    params.put(DynamoDbBillingModeParam.key, "PAY_PER_REQUEST") // No capacity limits
    params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
    params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

    val factory = new DynamoDbDataStoreFactory()
    dataStore = factory.createDataStore(params).asInstanceOf[DynamoDbDataStore]
  }

  override def afterAll(): Unit = {
    if (dataStore != null) dataStore.dispose()
    if (client != null) client.close()
    if (localstack != null) localstack.stop()
  }

  "DynamoDbDataStore Performance" should {

    "handle batch write operations efficiently" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("batch-perf-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("category", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val batchSize = 100
      val startTime = System.currentTimeMillis()

      val writer = dataStore.getFeatureWriterAppend("batch-perf-test", Transaction.AUTO_COMMIT)
      
      try {
        for (i <- 1 to batchSize) {
          val feature = writer.next()
          feature.setAttribute("name", s"Batch Feature $i")
          feature.setAttribute("category", if (i % 2 == 0) "even" else "odd")
          feature.setAttribute("value", Integer.valueOf(i))
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      val throughput = (batchSize * 1000.0) / duration // features per second

      println(s"Batch write performance: $batchSize features in ${duration}ms (${throughput.formatted("%.2f")} features/sec)")

      // Verify all features were written
      val query = new Query("batch-perf-test")
      val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
      
      var count = 0
      try {
        while (reader.hasNext) {
          reader.next()
          count += 1
        }
      } finally {
        reader.close()
      }
      
      count must equalTo(batchSize)
      throughput must beGreaterThan(1.0) // At least 1 feature per second
      ok
    }

    "handle large dataset queries efficiently" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("large-dataset-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("region", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      // Insert a larger dataset
      val datasetSize = 500
      val writer = dataStore.getFeatureWriterAppend("large-dataset-test", Transaction.AUTO_COMMIT)
      val random = new Random(42) // Fixed seed for reproducibility
      
      try {
        for (i <- 1 to datasetSize) {
          val feature = writer.next()
          feature.setAttribute("name", s"Dataset Feature $i")
          feature.setAttribute("region", s"region-${i % 10}") // 10 different regions
          feature.setAttribute("value", Integer.valueOf(random.nextInt(1000)))
          feature.setAttribute("geom", WKTUtils.read(f"POINT(${-180 + random.nextDouble() * 360}%.6f ${-90 + random.nextDouble() * 180}%.6f)"))
          feature.setAttribute("dtg", new Date(System.currentTimeMillis() - random.nextInt(86400000)))
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Test full scan performance
      val scanStartTime = System.currentTimeMillis()
      val scanQuery = new Query("large-dataset-test")
      val scanReader = dataStore.getFeatureReader(scanQuery, Transaction.AUTO_COMMIT)
      
      var scanCount = 0
      try {
        while (scanReader.hasNext) {
          scanReader.next()
          scanCount += 1
        }
      } finally {
        scanReader.close()
      }
      
      val scanEndTime = System.currentTimeMillis()
      val scanDuration = scanEndTime - scanStartTime
      val scanThroughput = (scanCount * 1000.0) / scanDuration

      println(s"Full scan performance: $scanCount features in ${scanDuration}ms (${scanThroughput.formatted("%.2f")} features/sec)")

      // Test filtered query performance
      val filterStartTime = System.currentTimeMillis()
      val filterQuery = new Query("large-dataset-test", CQL.toFilter("region = 'region-5'"))
      val filterReader = dataStore.getFeatureReader(filterQuery, Transaction.AUTO_COMMIT)
      
      var filterCount = 0
      try {
        while (filterReader.hasNext) {
          filterReader.next()
          filterCount += 1
        }
      } finally {
        filterReader.close()
      }
      
      val filterEndTime = System.currentTimeMillis()
      val filterDuration = filterEndTime - filterStartTime

      println(s"Filtered query performance: $filterCount features in ${filterDuration}ms")

      scanCount must equalTo(datasetSize)
      filterCount must beGreaterThan(0)
      filterCount must beLessThan(datasetSize)
      scanThroughput must beGreaterThan(1.0)
      ok
    }

    "handle concurrent read operations efficiently" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("concurrent-read-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("thread_id", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      // Setup test data
      val setupSize = 200
      val writer = dataStore.getFeatureWriterAppend("concurrent-read-test", Transaction.AUTO_COMMIT)
      
      try {
        for (i <- 1 to setupSize) {
          val feature = writer.next()
          feature.setAttribute("name", s"Concurrent Feature $i")
          feature.setAttribute("thread_id", Integer.valueOf(i % 10))
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Test concurrent reads
      val numThreads = 5
      val executor = Executors.newFixedThreadPool(numThreads)
      val latch = new CountDownLatch(numThreads)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[(Int, Long)]()

      val startTime = System.currentTimeMillis()

      try {
        for (threadId <- 1 to numThreads) {
          executor.submit(new Runnable {
            override def run(): Unit = {
              try {
                val threadStartTime = System.currentTimeMillis()
                val query = new Query("concurrent-read-test", CQL.toFilter(s"thread_id = ${threadId % 10}"))
                val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
                
                var count = 0
                try {
                  while (reader.hasNext) {
                    reader.next()
                    count += 1
                  }
                } finally {
                  reader.close()
                }
                
                val threadEndTime = System.currentTimeMillis()
                results.add((count, threadEndTime - threadStartTime))
              } finally {
                latch.countDown()
              }
            }
          })
        }

        latch.await(30, TimeUnit.SECONDS) must beTrue
        val endTime = System.currentTimeMillis()
        val totalDuration = endTime - startTime

        val resultList = results.asScala.toList
        val totalFeatures = resultList.map(_._1).sum
        val avgThreadDuration = resultList.map(_._2).sum / resultList.size.toDouble

        println(s"Concurrent read performance: $numThreads threads, $totalFeatures total features in ${totalDuration}ms")
        println(s"Average thread duration: ${avgThreadDuration.formatted("%.2f")}ms")

        resultList.size must equalTo(numThreads)
        totalFeatures must beGreaterThan(0)
        avgThreadDuration must beLessThan(totalDuration.toDouble)
        ok

      } finally {
        executor.shutdown()
      }
    }

    "handle concurrent write operations efficiently" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("concurrent-write-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("writer_id", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val numWriters = 3
      val featuresPerWriter = 50
      val executor = Executors.newFixedThreadPool(numWriters)
      val latch = new CountDownLatch(numWriters)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[(Int, Long)]()

      val startTime = System.currentTimeMillis()

      try {
        for (writerId <- 1 to numWriters) {
          executor.submit(new Runnable {
            override def run(): Unit = {
              try {
                val threadStartTime = System.currentTimeMillis()
                val writer = dataStore.getFeatureWriterAppend("concurrent-write-test", Transaction.AUTO_COMMIT)
                
                try {
                  for (i <- 1 to featuresPerWriter) {
                    val feature = writer.next()
                    feature.setAttribute("name", s"Writer $writerId Feature $i")
                    feature.setAttribute("writer_id", Integer.valueOf(writerId))
                    feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${writerId}${String.format("%02d", i.asInstanceOf[AnyRef])} 38.895)"))
                    feature.setAttribute("dtg", new Date())
                    writer.write()
                  }
                } finally {
                  writer.close()
                }
                
                val threadEndTime = System.currentTimeMillis()
                results.add((featuresPerWriter, threadEndTime - threadStartTime))
              } finally {
                latch.countDown()
              }
            }
          })
        }

        latch.await(60, TimeUnit.SECONDS) must beTrue
        val endTime = System.currentTimeMillis()
        val totalDuration = endTime - startTime

        val resultList = results.asScala.toList
        val totalFeatures = resultList.map(_._1).sum
        val avgThreadDuration = resultList.map(_._2).sum / resultList.size.toDouble
        val throughput = (totalFeatures * 1000.0) / totalDuration

        println(s"Concurrent write performance: $numWriters writers, $totalFeatures total features in ${totalDuration}ms")
        println(s"Average writer duration: ${avgThreadDuration.formatted("%.2f")}ms")
        println(s"Overall throughput: ${throughput.formatted("%.2f")} features/sec")

        // Verify all features were written
        val query = new Query("concurrent-write-test")
        val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
        
        var count = 0
        try {
          while (reader.hasNext) {
            reader.next()
            count += 1
          }
        } finally {
          reader.close()
        }

        resultList.size must equalTo(numWriters)
        count must equalTo(totalFeatures)
        throughput must beGreaterThan(1.0)
        ok

      } finally {
        executor.shutdown()
      }
    }

    "handle spatial query performance efficiently" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("spatial-perf-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("category", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      // Create spatially distributed data
      val dataSize = 300
      val writer = dataStore.getFeatureWriterAppend("spatial-perf-test", Transaction.AUTO_COMMIT)
      val random = new Random(123)
      
      try {
        for (i <- 1 to dataSize) {
          val feature = writer.next()
          feature.setAttribute("name", s"Spatial Feature $i")
          feature.setAttribute("category", s"cat-${i % 5}")
          
          // Create clusters of points in different regions
          val region = i % 4
          val baseLon = -120.0 + (region * 10.0)
          val baseLat = 35.0 + (region * 5.0)
          val lon = baseLon + (random.nextGaussian() * 2.0)
          val lat = baseLat + (random.nextGaussian() * 2.0)
          
          feature.setAttribute("geom", WKTUtils.read(f"POINT($lon%.6f $lat%.6f)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Test spatial queries with different bounding boxes
      val spatialQueries = Seq(
        ("small", "BBOX(geom, -122, 37, -118, 41)"),
        ("medium", "BBOX(geom, -130, 30, -110, 50)"),
        ("large", "BBOX(geom, -140, 25, -100, 55)")
      )

      spatialQueries.foreach { case (size, bbox) =>
        val startTime = System.currentTimeMillis()
        val query = new Query("spatial-perf-test", CQL.toFilter(bbox))
        val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
        
        var count = 0
        try {
          while (reader.hasNext) {
            reader.next()
            count += 1
          }
        } finally {
          reader.close()
        }
        
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        println(s"Spatial query ($size bbox): $count features in ${duration}ms")
        
        count must beGreaterThanOrEqualTo(0)
        duration must beLessThan(10000L) // Should complete within 10 seconds
      }
      ok
    }

    "handle memory usage efficiently during large operations" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("memory-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("data", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val runtime = Runtime.getRuntime
      val initialMemory = runtime.totalMemory() - runtime.freeMemory()

      // Write features with larger data payloads
      val largeDataSize = 200
      val writer = dataStore.getFeatureWriterAppend("memory-test", Transaction.AUTO_COMMIT)
      val largeString = "x" * 1000 // 1KB per feature
      
      try {
        for (i <- 1 to largeDataSize) {
          val feature = writer.next()
          feature.setAttribute("name", s"Memory Test Feature $i")
          feature.setAttribute("data", s"$largeString-$i")
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
          
          // Periodic memory check
          if (i % 50 == 0) {
            runtime.gc()
            val currentMemory = runtime.totalMemory() - runtime.freeMemory()
            val memoryIncrease = currentMemory - initialMemory
            println(s"Memory usage after $i features: ${memoryIncrease / 1024 / 1024}MB")
          }
        }
      } finally {
        writer.close()
      }

      runtime.gc()
      val finalMemory = runtime.totalMemory() - runtime.freeMemory()
      val totalMemoryIncrease = finalMemory - initialMemory

      println(s"Total memory increase: ${totalMemoryIncrease / 1024 / 1024}MB for $largeDataSize features")

      // Memory increase should be reasonable (less than 100MB for this test)
      totalMemoryIncrease must beLessThan(100L * 1024 * 1024)
      ok
    }

    "handle query result pagination efficiently" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("pagination-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("page", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      // Setup paginated data
      val totalFeatures = 250
      val writer = dataStore.getFeatureWriterAppend("pagination-test", Transaction.AUTO_COMMIT)
      
      try {
        for (i <- 1 to totalFeatures) {
          val feature = writer.next()
          feature.setAttribute("name", s"Page Feature $i")
          feature.setAttribute("page", Integer.valueOf(i / 50)) // 5 pages of ~50 features each
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
          feature.setAttribute("dtg", new Date())
          writer.write()
        }
      } finally {
        writer.close()
      }

      // Test paginated queries
      val pageSize = 50
      val numPages = (totalFeatures + pageSize - 1) / pageSize
      var totalRetrieved = 0

      val paginationStartTime = System.currentTimeMillis()

      for (page <- 0 until numPages) {
        val query = new Query("pagination-test")
        query.setMaxFeatures(pageSize)
        query.setStartIndex(page * pageSize)
        
        val reader = dataStore.getFeatureReader(query, Transaction.AUTO_COMMIT)
        
        var pageCount = 0
        try {
          while (reader.hasNext) {
            reader.next()
            pageCount += 1
          }
        } finally {
          reader.close()
        }
        
        totalRetrieved += pageCount
        println(s"Page $page: $pageCount features")
      }

      val paginationEndTime = System.currentTimeMillis()
      val paginationDuration = paginationEndTime - paginationStartTime

      println(s"Pagination performance: $totalRetrieved features in $numPages pages, ${paginationDuration}ms total")

      totalRetrieved must equalTo(totalFeatures)
      paginationDuration must beLessThan(30000L) // Should complete within 30 seconds
      ok
    }

    "measure serialization performance" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("serialization-perf-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      import org.locationtech.geomesa.dynamodb.data.util.DynamoDbFeatureSerializer
      import org.locationtech.geomesa.features.ScalaSimpleFeature

      val numFeatures = 1000
      val features = (1 to numFeatures).map { i =>
        val feature = new ScalaSimpleFeature(sft, s"perf-test-$i")
        feature.setAttribute("name", s"Performance Test $i")
        feature.setAttribute("value", Integer.valueOf(i))
        feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
        feature.setAttribute("dtg", new Date())
        feature
      }

      // Test serialization performance
      val serializeStartTime = System.currentTimeMillis()
      val items = features.map(DynamoDbFeatureSerializer.featureToItem)
      val serializeEndTime = System.currentTimeMillis()
      val serializeDuration = serializeEndTime - serializeStartTime
      val serializeThroughput = (numFeatures * 1000.0) / serializeDuration

      println(s"Serialization performance: $numFeatures features in ${serializeDuration}ms (${serializeThroughput.formatted("%.2f")} features/sec)")

      // Test deserialization performance
      val deserializeStartTime = System.currentTimeMillis()
      val deserializedFeatures = items.flatMap(DynamoDbFeatureSerializer.itemToFeature(_, sft))
      val deserializeEndTime = System.currentTimeMillis()
      val deserializeDuration = deserializeEndTime - deserializeStartTime
      val deserializeThroughput = (numFeatures * 1000.0) / deserializeDuration

      println(s"Deserialization performance: $numFeatures features in ${deserializeDuration}ms (${deserializeThroughput.formatted("%.2f")} features/sec)")

      deserializedFeatures.size must equalTo(numFeatures)
      serializeThroughput must beGreaterThan(100.0) // At least 100 features/sec
      deserializeThroughput must beGreaterThan(100.0) // At least 100 features/sec
      ok
    }

    "measure index operations performance" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("index-perf-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("category", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      dataStore.createSchema(sft)

      val adapter = dataStore.adapter
      val indices = dataStore.manager.indices(sft)
      val writer = adapter.createWriter(sft, indices)

      val numOperations = 500
      val startTime = System.currentTimeMillis()

      try {
        for (i <- 1 to numOperations) {
          val feature = new org.locationtech.geomesa.features.ScalaSimpleFeature(sft, s"index-perf-$i")
          feature.setAttribute("name", s"Index Performance Test $i")
          feature.setAttribute("category", s"category-${i % 10}")
          feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${String.format("%03d", i.asInstanceOf[AnyRef])} 38.895)"))
          feature.setAttribute("dtg", new Date())
          
          writer.append(feature)
          
          if (i % 100 == 0) {
            writer.flush()
          }
        }
        writer.flush()
      } finally {
        writer.close()
      }

      val endTime = System.currentTimeMillis()
      val duration = endTime - startTime
      val throughput = (numOperations * 1000.0) / duration

      println(s"Index operations performance: $numOperations operations in ${duration}ms (${throughput.formatted("%.2f")} ops/sec)")

      throughput must beGreaterThan(10.0) // At least 10 operations per second
      ok
    }
  }
}
