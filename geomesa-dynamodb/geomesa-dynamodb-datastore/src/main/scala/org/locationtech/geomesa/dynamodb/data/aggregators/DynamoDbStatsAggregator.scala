/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.aggregators

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.geotools.feature.simple.SimpleFeatureTypeBuilder
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
import org.locationtech.geomesa.dynamodb.data.util.DynamoDbFeatureSerializer
import software.amazon.awssdk.services.dynamodb.model._

import java.util.Date
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
  * Simplified aggregator for computing statistics over DynamoDB data
  */
class DynamoDbStatsAggregator extends LazyLogging {

  /**
    * Execute a stats query against DynamoDB
    */
  def query(
      ds: DynamoDbDataStore,
      sft: SimpleFeatureType,
      tableName: String,
      attributes: Seq[String] = Seq.empty
    ): CloseableIterator[SimpleFeature] = {

    try {
      logger.debug(s"Executing stats query on table: $tableName")
      
      // Build DynamoDB scan request
      val scanRequest = ScanRequest.builder()
        .tableName(tableName)
        .build()
      
      val response = ds.client.scan(scanRequest)
      
      // Process items and compute statistics
      val items = response.items().asScala.toList
      val features = items.flatMap { item =>
        DynamoDbFeatureSerializer.itemToFeature(item.asScala.asJava, sft)
      }
      
      // Compute statistics
      val stats = computeStatistics(features, sft, attributes)
      
      // Convert stats to features
      val statFeatures = convertStatsToFeatures(stats, sft)
      
      logger.debug(s"Stats query returned ${statFeatures.size} statistical results")
      CloseableIterator(statFeatures.iterator)
      
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error executing stats query on table: $tableName", e)
        CloseableIterator.empty
    }
  }

  /**
    * Compute statistics from features
    */
  private def computeStatistics(
      features: List[SimpleFeature], 
      sft: SimpleFeatureType,
      attributes: Seq[String]
    ): Map[String, AttributeStats] = {
    
    val stats = scala.collection.mutable.Map[String, AttributeStats]()
    
    // Determine which attributes to analyze
    val attributesToAnalyze = if (attributes.nonEmpty) {
      attributes
    } else {
      // Analyze all numeric attributes
      sft.getAttributeDescriptors.asScala
        .filter(desc => isNumericType(desc.getType.getBinding))
        .map(_.getLocalName)
        .toSeq
    }
    
    // Initialize stats for each attribute
    attributesToAnalyze.foreach { attrName =>
      stats(attrName) = AttributeStats(attrName)
    }
    
    // Process each feature
    features.foreach { feature =>
      attributesToAnalyze.foreach { attrName =>
        val value = feature.getAttribute(attrName)
        if (value != null) {
          stats(attrName).observe(value)
        }
      }
    }
    
    stats.toMap
  }

  /**
    * Check if a type is numeric
    */
  private def isNumericType(clazz: Class[_]): Boolean = {
    clazz == classOf[Integer] || clazz == classOf[java.lang.Integer] ||
    clazz == classOf[Long] || clazz == classOf[java.lang.Long] ||
    clazz == classOf[Double] || clazz == classOf[java.lang.Double] ||
    clazz == classOf[Float] || clazz == classOf[java.lang.Float]
  }

  /**
    * Convert statistics to features
    */
  private def convertStatsToFeatures(stats: Map[String, AttributeStats], sft: SimpleFeatureType): Seq[SimpleFeature] = {
    // Create feature type for stats results
    val statsBuilder = new SimpleFeatureTypeBuilder()
    statsBuilder.setName("stats_result")
    statsBuilder.add("attribute_name", classOf[String])
    statsBuilder.add("count", classOf[java.lang.Long])
    statsBuilder.add("min_value", classOf[java.lang.Double])
    statsBuilder.add("max_value", classOf[java.lang.Double])
    statsBuilder.add("sum_value", classOf[java.lang.Double])
    statsBuilder.add("avg_value", classOf[java.lang.Double])
    statsBuilder.add("feature_type", classOf[String])
    statsBuilder.add("timestamp", classOf[Date])
    val statsSft = statsBuilder.buildFeatureType()
    
    stats.map { case (attrName, attrStats) =>
      val feature = new ScalaSimpleFeature(statsSft, s"stats_${attrName}_${System.currentTimeMillis()}")
      feature.setAttribute("attribute_name", attrName)
      feature.setAttribute("count", Long.box(attrStats.count))
      feature.setAttribute("min_value", Double.box(attrStats.min))
      feature.setAttribute("max_value", Double.box(attrStats.max))
      feature.setAttribute("sum_value", Double.box(attrStats.sum))
      feature.setAttribute("avg_value", Double.box(if (attrStats.count > 0) attrStats.sum / attrStats.count else 0.0))
      feature.setAttribute("feature_type", sft.getTypeName)
      feature.setAttribute("timestamp", new Date())
      feature
    }.toSeq
  }
}

/**
  * Simple statistics holder
  */
case class AttributeStats(
    attributeName: String,
    var count: Long = 0,
    var sum: Double = 0.0,
    var min: Double = Double.MaxValue,
    var max: Double = Double.MinValue
) {
  
  def observe(value: Any): Unit = {
    val numValue = value match {
      case i: Integer => i.toDouble
      case l: Long => l.toDouble
      case d: Double => d
      case f: Float => f.toDouble
      case _ => return // Skip non-numeric values
    }
    
    count += 1
    sum += numValue
    min = math.min(min, numValue)
    max = math.max(max, numValue)
  }
}
