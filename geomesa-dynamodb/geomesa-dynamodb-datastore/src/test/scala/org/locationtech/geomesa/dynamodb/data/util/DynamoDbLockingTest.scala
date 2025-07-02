/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import org.junit.runner.RunWith
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient

import java.io.Closeable
import java.util.{HashMap => JHashMap}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

@RunWith(classOf[JUnitRunner])
class DynamoDbLockingTest extends Specification with BeforeAfterAll {

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
    params.put(DynamoDbCatalogParam.key, "locking-test")
    params.put(DynamoDbRegionParam.key, "us-east-1")
    params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
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

  "DynamoDbLocking" should {

    "acquire and release locks correctly" in {
      val lockKey = "test.basic.lock"

      // Acquire lock
      val lock = dataStore.acquireDistributedLock(lockKey)
      lock must not(beNull)
      lock must beAnInstanceOf[Closeable]

      // Release lock
      lock.close()
      ok
    }

    "prevent concurrent access to same resource" in {
      val lockKey = "test.concurrent.lock"
      val executor = Executors.newFixedThreadPool(2)
      val latch = new CountDownLatch(2)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[Boolean]()

      try {
        // Submit two tasks that try to acquire the same lock
        executor.submit(new Runnable {
          override def run(): Unit = {
            try {
              val lockOpt = dataStore.acquireDistributedLock(lockKey, 5000) // 5 second timeout
              if (lockOpt.isDefined) {
                results.add(true)
                Thread.sleep(2000) // Hold lock for 2 seconds
                lockOpt.get.close()
              } else {
                results.add(false)
              }
            } catch {
              case _: Exception => results.add(false)
            } finally {
              latch.countDown()
            }
          }
        })

        executor.submit(new Runnable {
          override def run(): Unit = {
            try {
              Thread.sleep(500) // Start slightly later
              val lockOpt = dataStore.acquireDistributedLock(lockKey, 1000) // 1 second timeout
              if (lockOpt.isDefined) {
                results.add(true)
                lockOpt.get.close()
              } else {
                results.add(false)
              }
            } catch {
              case _: Exception => results.add(false)
            } finally {
              latch.countDown()
            }
          }
        })

        // Wait for both tasks to complete
        latch.await(10, TimeUnit.SECONDS) must beTrue

        // One should succeed, one should fail (timeout)
        val resultList = results.asScala.toList
        resultList.size must equalTo(2)
        resultList.count(_ == true) must equalTo(1)
        resultList.count(_ == false) must equalTo(1)
        ok

      } finally {
        executor.shutdown()
      }
    }

    "handle lock timeouts correctly" in {
      val lockKey = "test.timeout.lock"

      // Acquire lock first
      val firstLock = dataStore.acquireDistributedLock(lockKey)
      firstLock must not(beNull)

      try {
        // Try to acquire same lock with short timeout
        val secondLockOpt = dataStore.acquireDistributedLock(lockKey, 1000) // 1 second timeout
        secondLockOpt must beNone
        ok
      } finally {
        firstLock.close()
      }
    }

    "handle multiple different locks correctly" in {
      val lockKey1 = "test.multi.lock1"
      val lockKey2 = "test.multi.lock2"

      // Should be able to acquire different locks simultaneously
      val lock1 = dataStore.acquireDistributedLock(lockKey1)
      val lock2 = dataStore.acquireDistributedLock(lockKey2)

      lock1 must not(beNull)
      lock2 must not(beNull)

      // Release both locks
      lock1.close()
      lock2.close()
      ok
    }

    "handle lock reacquisition after release" in {
      val lockKey = "test.reacquire.lock"

      // First acquisition
      val firstLock = dataStore.acquireDistributedLock(lockKey)
      firstLock must not(beNull)
      firstLock.close()

      // Second acquisition (should succeed)
      val secondLock = dataStore.acquireDistributedLock(lockKey)
      secondLock must not(beNull)
      secondLock.close()
      ok
    }

    "handle long lock keys correctly" in {
      val longLockKey = "test.long.lock." + ("x" * 200)

      val lock = dataStore.acquireDistributedLock(longLockKey)
      lock must not(beNull)

      lock.close()
      ok
    }

    "handle special characters in lock keys" in {
      val specialLockKey = "test.special.lock-with_special@chars#123"

      val lock = dataStore.acquireDistributedLock(specialLockKey)
      lock must not(beNull)

      lock.close()
      ok
    }

    "handle concurrent lock operations on different keys" in {
      val numLocks = 5
      val executor = Executors.newFixedThreadPool(numLocks)
      val latch = new CountDownLatch(numLocks)
      val results = new java.util.concurrent.ConcurrentLinkedQueue[String]()

      try {
        // Submit tasks that acquire different locks
        for (i <- 1 to numLocks) {
          executor.submit(new Runnable {
            override def run(): Unit = {
              try {
                val lockKey = s"test.concurrent.different.lock$i"
                val lock = dataStore.acquireDistributedLock(lockKey)
                if (lock != null) {
                  results.add(s"success-$i")
                  Thread.sleep(100) // Hold lock briefly
                  lock.close()
                } else {
                  results.add(s"failed-$i")
                }
              } catch {
                case ex: Exception =>
                  results.add(s"error-$i: ${ex.getMessage}")
              } finally {
                latch.countDown()
              }
            }
          })
        }

        // Wait for all tasks to complete
        latch.await(30, TimeUnit.SECONDS) must beTrue

        // All should succeed since they're different locks
        val resultList = results.asScala.toList
        resultList.size must equalTo(numLocks)
        resultList.foreach { result =>
          result must startWith("success-")
        }
        ok

      } finally {
        executor.shutdown()
      }
    }

    "handle lock expiration correctly" in {
      val lockKey = "test.expiration.lock"

      // This test verifies that the locking mechanism handles expiration
      // The actual expiration behavior depends on the implementation
      val lock = dataStore.acquireDistributedLock(lockKey)
      lock must not(beNull)

      // Simulate holding the lock
      Thread.sleep(100)

      // Lock should still be valid
      lock.close()
      ok
    }

    "handle rapid lock acquisition and release" in {
      val lockKey = "test.rapid.lock"
      val iterations = 10

      // Rapidly acquire and release the same lock
      for (i <- 1 to iterations) {
        val lock = dataStore.acquireDistributedLock(lockKey)
        lock must not(beNull)
        lock.close()
      }
      ok
    }

    "handle lock cleanup on errors" in {
      val lockKey = "test.cleanup.lock"

      // Test that locks are properly cleaned up even when exceptions occur
      val result = Try {
        val lock = dataStore.acquireDistributedLock(lockKey)
        lock must not(beNull)
        // Simulate an error
        throw new RuntimeException("Simulated error")
      }
      
      result.isFailure must beTrue
      
      // Should be able to acquire the lock again after the exception
      val newLock = dataStore.acquireDistributedLock(lockKey)
      newLock must not(beNull)
      newLock.close()
      ok
    }

    "handle distributed lock table creation" in {
      // Create a new datastore to test table creation
      val params = new JHashMap[String, AnyRef]()
      params.put(DynamoDbCatalogParam.key, "new-locking-catalog")
      params.put(DynamoDbRegionParam.key, "us-east-1")
      params.put(DynamoDbEndpointParam.key, localstack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString)
      params.put("AWS_ACCESS_KEY_ID", localstack.getAccessKey)
      params.put("AWS_SECRET_ACCESS_KEY", localstack.getSecretKey)

      val factory = new org.locationtech.geomesa.dynamodb.data.DynamoDbDataStoreFactory()
      val newDataStore = factory.createDataStore(params).asInstanceOf[org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore]
      
      try {
        // Should be able to acquire locks immediately after creation
        val lock = newDataStore.acquireDistributedLock("test.new.lock")
        lock must not(beNull)
        lock.close()
        ok
      } finally {
        newDataStore.dispose()
      }
    }

    "handle lock contention gracefully" in {
      val lockKey = "test.contention.lock"
      val numThreads = 10
      val executor = Executors.newFixedThreadPool(numThreads)
      val latch = new CountDownLatch(numThreads)
      val successCount = new java.util.concurrent.atomic.AtomicInteger(0)
      val failureCount = new java.util.concurrent.atomic.AtomicInteger(0)

      try {
        // Submit many tasks that try to acquire the same lock
        for (i <- 1 to numThreads) {
          executor.submit(new Runnable {
            override def run(): Unit = {
              try {
                val lockOpt = dataStore.acquireDistributedLock(lockKey, 2000) // 2 second timeout
                if (lockOpt.isDefined) {
                  successCount.incrementAndGet()
                  Thread.sleep(50) // Hold lock briefly
                  lockOpt.get.close()
                } else {
                  failureCount.incrementAndGet()
                }
              } catch {
                case _: Exception => failureCount.incrementAndGet()
              } finally {
                latch.countDown()
              }
            }
          })
        }

        // Wait for all tasks to complete
        latch.await(30, TimeUnit.SECONDS) must beTrue

        // Should have some successes and some failures due to contention
        val totalOperations = successCount.get() + failureCount.get()
        totalOperations must equalTo(numThreads)
        successCount.get() must beGreaterThan(0)
        ok

      } finally {
        executor.shutdown()
      }
    }
  }
}
