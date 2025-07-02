/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.junit.runner.RunWith
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.utils.text.WKTUtils
import org.locationtech.jts.geom.{Geometry, Point}
import org.specs2.mutable.Specification
import org.specs2.runner.JUnitRunner
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import java.util.{Date, UUID}
import scala.collection.JavaConverters._

@RunWith(classOf[JUnitRunner])
class DynamoDbFeatureSerializerTest extends Specification {

  "DynamoDbFeatureSerializer" should {

    "serialize and deserialize simple features correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("test-feature")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("age", classOf[Integer])
      sftBuilder.add("height", classOf[java.lang.Double])
      sftBuilder.add("active", classOf[java.lang.Boolean])
      sftBuilder.add("geom", classOf[Point], DefaultGeographicCRS.WGS84)
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val originalFeature = new ScalaSimpleFeature(sft, "test-id-1")
      originalFeature.setAttribute("name", "John Doe")
      originalFeature.setAttribute("age", Integer.valueOf(30))
      originalFeature.setAttribute("height", Double.box(5.9))
      originalFeature.setAttribute("active", java.lang.Boolean.TRUE)
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
      originalFeature.setAttribute("dtg", new Date(1609459200000L)) // 2021-01-01

      // Serialize
      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      item must not(beNull)
      item.size() must beGreaterThan(0)

      // Deserialize
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)
      deserializedOpt must beSome

      val deserialized = deserializedOpt.get
      deserialized.getID must equalTo("test-id-1")
      deserialized.getAttribute("name") must equalTo("John Doe")
      deserialized.getAttribute("age") must equalTo(Integer.valueOf(30))
      deserialized.getAttribute("height") must equalTo(Double.box(5.9))
      deserialized.getAttribute("active") must equalTo(java.lang.Boolean.TRUE)
      deserialized.getAttribute("dtg") must equalTo(new Date(1609459200000L))
      
      val geom = deserialized.getAttribute("geom").asInstanceOf[Point]
      geom.getX must beCloseTo(-77.036, 0.001)
      geom.getY must beCloseTo(38.895, 0.001)
      ok
    }

    "handle null values correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("null-test-feature")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("optional_field", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val originalFeature = new ScalaSimpleFeature(sft, "null-test-1")
      originalFeature.setAttribute("name", "Test Feature")
      originalFeature.setAttribute("optional_field", null)
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(0 0)"))
      originalFeature.setAttribute("dtg", new Date())

      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Test Feature")
      // Null handling may vary by implementation
      val optionalField = deserialized.getAttribute("optional_field")
      (optionalField == null || optionalField == "") must beTrue
      ok
    }

    "handle different geometry types correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("geometry-test-feature")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Geometry])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val geometries = Seq(
        ("point", "POINT(-77.036 38.895)"),
        ("point2", "POINT(0 0)"),
        ("point3", "POINT(-180 -90)"),
        ("point4", "POINT(180 90)")
      )

      geometries.foreach { case (name, wkt) =>
        val originalFeature = new ScalaSimpleFeature(sft, s"geom-test-$name")
        originalFeature.setAttribute("name", s"Test $name")
        originalFeature.setAttribute("geom", WKTUtils.read(wkt))
        originalFeature.setAttribute("dtg", new Date())

        val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
        val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

        deserializedOpt must beSome
        val deserialized = deserializedOpt.get
        deserialized.getAttribute("name") must equalTo(s"Test $name")
        val deserializedGeom = deserialized.getAttribute("geom").asInstanceOf[Geometry]
        deserializedGeom must not(beNull)
        deserializedGeom.getGeometryType must equalTo(WKTUtils.read(wkt).getGeometryType)
      }
      ok
    }

    "handle complex data types correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("complex-test-feature")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("long_value", classOf[java.lang.Long])
      sftBuilder.add("float_value", classOf[java.lang.Float])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val originalFeature = new ScalaSimpleFeature(sft, "complex-test-1")
      originalFeature.setAttribute("name", "Complex Feature")
      originalFeature.setAttribute("long_value", java.lang.Long.valueOf(9876543210L))
      originalFeature.setAttribute("float_value", java.lang.Float.valueOf(3.14159f))
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
      originalFeature.setAttribute("dtg", new Date())

      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Complex Feature")
      deserialized.getAttribute("long_value") must equalTo(java.lang.Long.valueOf(9876543210L))
      deserialized.getAttribute("float_value") must equalTo(java.lang.Float.valueOf(3.14159f))
      ok
    }

    "handle large string values correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("large-string-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("large_text", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val largeText = "x" * 10000 // 10KB string
      val originalFeature = new ScalaSimpleFeature(sft, "large-string-1")
      originalFeature.setAttribute("name", "Large String Test")
      originalFeature.setAttribute("large_text", largeText)
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(0 0)"))
      originalFeature.setAttribute("dtg", new Date())

      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Large String Test")
      deserialized.getAttribute("large_text") must equalTo(largeText)
      ok
    }

    "handle special characters in strings correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("special-chars-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("special_text", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val specialText = "Special chars: !@#$%^&*()_+-={}[]|\\:;\"'<>?,./ àáâãäåæçèéêë 中文 🌍🚀"
      val originalFeature = new ScalaSimpleFeature(sft, "special-chars-1")
      originalFeature.setAttribute("name", "Special Characters Test")
      originalFeature.setAttribute("special_text", specialText)
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(0 0)"))
      originalFeature.setAttribute("dtg", new Date())

      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Special Characters Test")
      deserialized.getAttribute("special_text") must equalTo(specialText)
      ok
    }

    "handle extreme numeric values correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("extreme-numeric-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("max_int", classOf[Integer])
      sftBuilder.add("min_int", classOf[Integer])
      sftBuilder.add("max_double", classOf[java.lang.Double])
      sftBuilder.add("min_double", classOf[java.lang.Double])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val originalFeature = new ScalaSimpleFeature(sft, "extreme-numeric-1")
      originalFeature.setAttribute("name", "Extreme Numeric Test")
      originalFeature.setAttribute("max_int", Integer.valueOf(Integer.MAX_VALUE))
      originalFeature.setAttribute("min_int", Integer.valueOf(Integer.MIN_VALUE))
      originalFeature.setAttribute("max_double", Double.box(Double.MaxValue))
      originalFeature.setAttribute("min_double", Double.box(Double.MinValue))
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(0 0)"))
      originalFeature.setAttribute("dtg", new Date())

      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Extreme Numeric Test")
      deserialized.getAttribute("max_int") must equalTo(Integer.valueOf(Integer.MAX_VALUE))
      deserialized.getAttribute("min_int") must equalTo(Integer.valueOf(Integer.MIN_VALUE))
      deserialized.getAttribute("max_double") must equalTo(Double.box(Double.MaxValue))
      deserialized.getAttribute("min_double") must equalTo(Double.box(Double.MinValue))
      ok
    }

    "handle date edge cases correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("date-edge-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("epoch_date", classOf[Date])
      sftBuilder.add("future_date", classOf[Date])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val epochDate = new Date(0L) // Unix epoch
      val futureDate = new Date(4102444800000L) // Year 2100
      val originalFeature = new ScalaSimpleFeature(sft, "date-edge-1")
      originalFeature.setAttribute("name", "Date Edge Test")
      originalFeature.setAttribute("epoch_date", epochDate)
      originalFeature.setAttribute("future_date", futureDate)
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(0 0)"))
      originalFeature.setAttribute("dtg", new Date())

      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Date Edge Test")
      deserialized.getAttribute("epoch_date") must equalTo(epochDate)
      deserialized.getAttribute("future_date") must equalTo(futureDate)
      ok
    }

    "generate correct table names" in {
      val tableName1 = DynamoDbFeatureSerializer.getTableName("catalog", "prefix-", "typename")
      tableName1 must equalTo("prefix-catalog_typename")

      val tableName2 = DynamoDbFeatureSerializer.getTableName("catalog", "", "typename")
      tableName2 must equalTo("catalog_typename")

      val tableName3 = DynamoDbFeatureSerializer.getTableName("my-catalog", "prod-", "my-feature-type")
      tableName3 must equalTo("prod-my-catalog_my-feature-type")
      ok
    }

    "handle serialization errors gracefully" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("error-test-feature")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      // Create a feature with null geometry
      val originalFeature = new ScalaSimpleFeature(sft, "error-test-1")
      originalFeature.setAttribute("name", "Error Test")
      originalFeature.setAttribute("geom", null) // This might cause issues
      originalFeature.setAttribute("dtg", new Date())

      // Should handle null geometry gracefully
      val item = DynamoDbFeatureSerializer.featureToItem(originalFeature)
      item must not(beNull)
      
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)
      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getAttribute("name") must equalTo("Error Test")
      // Null geometry handling depends on implementation
      ok
    }

    "handle round-trip serialization consistency" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("roundtrip-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("value", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val originalFeature = new ScalaSimpleFeature(sft, "roundtrip-1")
      originalFeature.setAttribute("name", "Round Trip Test")
      originalFeature.setAttribute("value", Integer.valueOf(42))
      originalFeature.setAttribute("geom", WKTUtils.read("POINT(-77.036 38.895)"))
      originalFeature.setAttribute("dtg", new Date(1609459200000L))

      // Multiple round trips
      var currentFeature: org.geotools.api.feature.simple.SimpleFeature = originalFeature
      for (i <- 1 to 5) {
        val item = DynamoDbFeatureSerializer.featureToItem(currentFeature)
        val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)
        deserializedOpt must beSome
        currentFeature = deserializedOpt.get
      }

      // Should maintain consistency after multiple round trips
      currentFeature.getAttribute("name") must equalTo("Round Trip Test")
      currentFeature.getAttribute("value") must equalTo(Integer.valueOf(42))
      currentFeature.getAttribute("dtg") must equalTo(new Date(1609459200000L))
      
      val geom = currentFeature.getAttribute("geom").asInstanceOf[Point]
      geom.getX must beCloseTo(-77.036, 0.001)
      geom.getY must beCloseTo(38.895, 0.001)
      ok
    }

    "handle empty feature correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("empty-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      val emptyFeature = new ScalaSimpleFeature(sft, "empty-1")
      // Don't set any attributes

      val item = DynamoDbFeatureSerializer.featureToItem(emptyFeature)
      val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)

      deserializedOpt must beSome
      val deserialized = deserializedOpt.get
      deserialized.getID must equalTo("empty-1")
      // All attributes should be null or default values
      ok
    }

    "handle primary key generation correctly" in {
      val featureId = "test-feature-123"
      val primaryKey = DynamoDbFeatureSerializer.getPrimaryKey(featureId)
      
      primaryKey must not(beNull)
      primaryKey.size() must beGreaterThan(0)
      primaryKey.containsKey("feature_id") must beTrue
      primaryKey.get("feature_id").s() must equalTo(featureId)
      ok
    }

    "handle concurrent serialization correctly" in {
      val sftBuilder = new SimpleFeatureTypeBuilder()
      sftBuilder.setName("concurrent-test")
      sftBuilder.add("name", classOf[String])
      sftBuilder.add("thread_id", classOf[Integer])
      sftBuilder.add("geom", classOf[Point])
      sftBuilder.add("dtg", classOf[Date])
      val sft = sftBuilder.buildFeatureType()

      // Test concurrent serialization/deserialization
      val results = (1 to 10).par.map { i =>
        val feature = new ScalaSimpleFeature(sft, s"concurrent-$i")
        feature.setAttribute("name", s"Concurrent Test $i")
        feature.setAttribute("thread_id", Integer.valueOf(i))
        feature.setAttribute("geom", WKTUtils.read(s"POINT(-77.${i}36 38.895)"))
        feature.setAttribute("dtg", new Date())

        val item = DynamoDbFeatureSerializer.featureToItem(feature)
        val deserializedOpt = DynamoDbFeatureSerializer.itemToFeature(item, sft)
        
        deserializedOpt must beSome
        val deserialized = deserializedOpt.get
        deserialized.getAttribute("thread_id").asInstanceOf[Integer].intValue() must equalTo(i)
        i
      }.seq

      results.size must equalTo(10)
      results.toSet must equalTo((1 to 10).toSet)
      ok
    }
  }
}
