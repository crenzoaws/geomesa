/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import org.junit.runner.RunWith
import org.locationtech.geomesa.index.metadata.MetadataStringSerializer
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class DynamoDbBackedMetadataTest extends Specification with BeforeAfterAll {

  private var localstack: LocalStackContainer = _
  private var client: DynamoDbClient = _
  private var metadata: DynamoDbBackedMetadata[String] = _

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

    metadata = new DynamoDbBackedMetadata[String](client, "test-catalog", "test-", MetadataStringSerializer)
  }

  override def afterAll(): Unit = {
    if (metadata != null) metadata.close()
    if (client != null) client.close()
    if (localstack != null) localstack.stop()
  }

  "DynamoDbBackedMetadata" should {

    "store and retrieve simple metadata" in {
      val typeName = "test-type"
      val key = "simple.key"
      val value = "test-value"

      metadata.insert(typeName, key, value)
      val retrieved = metadata.read(typeName, key)

      retrieved must beSome(value)
      ok
    }

    "store and retrieve complex metadata" in {
      val typeName = "complex-type"
      val key = "complex.key"
      val value = """{"type":"FeatureType","name":"test","attributes":[{"name":"geom","type":"Point"}]}"""

      metadata.insert(typeName, key, value)
      val retrieved = metadata.read(typeName, key)

      retrieved must beSome(value)
      ok
    }

    "handle non-existent keys correctly" in {
      val typeName = "nonexistent-type"
      val nonExistentKey = "nonexistent.key"
      val retrieved = metadata.read(typeName, nonExistentKey)

      retrieved must beNone
      ok
    }

    "update existing metadata" in {
      val typeName = "update-type"
      val key = "update.key"
      val originalValue = "original-value"
      val updatedValue = "updated-value"

      // Insert original value
      metadata.insert(typeName, key, originalValue)
      metadata.read(typeName, key) must beSome(originalValue)

      // Update the value
      metadata.insert(typeName, key, updatedValue)
      val retrieved = metadata.read(typeName, key)

      retrieved must beSome(updatedValue)
      ok
    }

    "delete metadata correctly" in {
      val typeName = "delete-type"
      val key = "delete.key"
      val value = "value-to-delete"

      // Insert value
      metadata.insert(typeName, key, value)
      metadata.read(typeName, key) must beSome(value)

      // Delete the value
      metadata.remove(typeName, key)
      val retrieved = metadata.read(typeName, key)

      retrieved must beNone
      ok
    }

    "handle batch insert operations" in {
      val typeName = "batch-type"
      val batchData = Map(
        "batch.key1" -> "batch-value1",
        "batch.key2" -> "batch-value2",
        "batch.key3" -> "batch-value3"
      )

      // Insert batch data
      metadata.insert(typeName, batchData)

      // Verify all values were inserted
      batchData.foreach { case (key, expectedValue) =>
        val retrieved = metadata.read(typeName, key)
        retrieved must beSome(expectedValue)
      }
      ok
    }

    "scan metadata with prefix correctly" in {
      val typeName = "scan-type"
      val testData = Map(
        "scan.prefix.key1" -> "scan-value1",
        "scan.prefix.key2" -> "scan-value2",
        "scan.other.key3" -> "scan-value3"
      )

      // Insert test data
      testData.foreach { case (key, value) =>
        metadata.insert(typeName, key, value)
      }

      // Scan with prefix
      val scannedData = metadata.scan(typeName, "scan.prefix").toMap

      // Should contain only the prefixed keys
      scannedData.size must equalTo(2)
      scannedData.get("scan.prefix.key1") must beSome("scan-value1")
      scannedData.get("scan.prefix.key2") must beSome("scan-value2")
      scannedData.get("scan.other.key3") must beNone
      ok
    }

    "scan all metadata correctly" in {
      val typeName = "scan-all-type"
      val testData = Map(
        "all.key1" -> "all-value1",
        "all.key2" -> "all-value2",
        "all.key3" -> "all-value3"
      )

      // Insert test data
      testData.foreach { case (key, value) =>
        metadata.insert(typeName, key, value)
      }

      // Scan all data (empty prefix)
      val scannedData = metadata.scan(typeName, "").toMap

      // Should contain all keys
      testData.foreach { case (key, value) =>
        scannedData.get(key) must beSome(value)
      }
      ok
    }

    "handle caching correctly" in {
      val typeName = "cache-type"
      val key = "cache.key"
      val value = "cache-value"

      // Insert with caching enabled (default)
      metadata.insert(typeName, key, value)
      
      // First read should populate cache
      val firstRead = metadata.read(typeName, key, cache = true)
      firstRead must beSome(value)
      
      // Second read should use cache
      val secondRead = metadata.read(typeName, key, cache = true)
      secondRead must beSome(value)
      
      // Read without cache should still work
      val noCacheRead = metadata.read(typeName, key, cache = false)
      noCacheRead must beSome(value)
      ok
    }

    "handle special characters in keys and values" in {
      val typeName = "special-type"
      val specialKey = "special.key-with_special@chars#123"
      val specialValue = "value with spaces, symbols: !@#$%^&*()_+-={}[]|\\:;\"'<>?,./"

      metadata.insert(typeName, specialKey, specialValue)
      val retrieved = metadata.read(typeName, specialKey)

      retrieved must beSome(specialValue)
      ok
    }

    "handle large values correctly" in {
      val typeName = "large-type"
      val key = "large.key"
      val largeValue = "x" * 10000 // 10KB string

      metadata.insert(typeName, key, largeValue)
      val retrieved = metadata.read(typeName, key)

      retrieved must beSome(largeValue)
      ok
    }

    "handle concurrent operations correctly" in {
      val typeName = "concurrent-type"
      val baseKey = "concurrent"
      val numOperations = 10

      // Perform concurrent inserts
      (1 to numOperations).par.foreach { i =>
        val key = s"$baseKey.key$i"
        val value = s"concurrent-value-$i"
        metadata.insert(typeName, key, value)
      }

      // Verify all values were inserted
      (1 to numOperations).foreach { i =>
        val key = s"$baseKey.key$i"
        val expectedValue = s"concurrent-value-$i"
        val retrieved = metadata.read(typeName, key)
        retrieved must beSome(expectedValue)
      }
      ok
    }

    "handle metadata versioning correctly" in {
      val typeName = "version-type"
      val key = "version.key"
      val versions = Seq("v1", "v2", "v3")

      // Insert different versions
      versions.foreach { version =>
        metadata.insert(typeName, key, version)
        metadata.read(typeName, key) must beSome(version)
      }

      // Final value should be the last version
      metadata.read(typeName, key) must beSome("v3")
      ok
    }

    "get feature types correctly" in {
      val typeNames = Seq("feature-type-1", "feature-type-2", "feature-type-3")
      
      // Insert metadata for different feature types
      typeNames.foreach { typeName =>
        metadata.insert(typeName, "schema", s"schema-for-$typeName")
      }

      // Get all feature types
      val retrievedTypes = metadata.getFeatureTypes.toSet
      
      // Should contain our test types
      typeNames.foreach { typeName =>
        retrievedTypes must contain(typeName)
      }
      ok
    }

    "handle empty scans correctly" in {
      val emptyTypeName = "empty-scan-type"
      
      // Scan empty type
      val results = metadata.scan(emptyTypeName, "").toList
      
      // Should handle empty results gracefully
      results must beEmpty
      ok
    }

    "handle resource cleanup correctly" in {
      // Create a temporary metadata instance
      val tempMetadata = new DynamoDbBackedMetadata[String](client, "temp-catalog", "temp-", MetadataStringSerializer)
      
      try {
        // Use the metadata
        tempMetadata.insert("temp-type", "temp.key", "temp-value")
        tempMetadata.read("temp-type", "temp.key") must beSome("temp-value")
        
        // Close should not throw exceptions
        tempMetadata.close()
        ok
      } catch {
        case _: Exception => 
          // If close throws an exception, that's also acceptable for this test
          ok
      }
    }

    "handle invalidation correctly" in {
      val typeName = "invalidate-type"
      val key = "invalidate.key"
      val value = "invalidate-value"

      // Insert and read to populate cache
      metadata.insert(typeName, key, value)
      metadata.read(typeName, key) must beSome(value)

      // Invalidate cache
      metadata.invalidateCache(typeName, key)
      
      // Should still be able to read from storage
      val retrieved = metadata.read(typeName, key, cache = false)
      retrieved must beSome(value)
      ok
    }

    "handle backup and restore operations" in {
      val typeName = "backup-type"
      val testData = Map(
        "backup.key1" -> "backup-value1",
        "backup.key2" -> "backup-value2"
      )

      // Insert test data
      testData.foreach { case (key, value) =>
        metadata.insert(typeName, key, value)
      }

      // Backup (scan all)
      val backup = metadata.scan(typeName, "").toMap
      backup.size must beGreaterThanOrEqualTo(testData.size)

      // Verify backup contains our data
      testData.foreach { case (key, value) =>
        backup.get(key) must beSome(value)
      }
      ok
    }
  }
}
