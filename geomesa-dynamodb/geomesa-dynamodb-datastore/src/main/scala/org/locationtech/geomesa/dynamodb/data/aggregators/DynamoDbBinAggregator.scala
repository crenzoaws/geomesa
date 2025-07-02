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
import org.locationtech.jts.geom.{Coordinate, Geometry, Point}
import software.amazon.awssdk.services.dynamodb.model._

import java.util.Date
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
  * Simplified aggregator for producing spatial bins (heat maps) from DynamoDB data
  */
class DynamoDbBinAggregator extends LazyLogging {

  /**
    * Execute a bin query against DynamoDB
    */
  def query(
      ds: DynamoDbDataStore,
      sft: SimpleFeatureType,
      tableName: String,
      gridSize: Int = 32,
      envelope: Option[org.locationtech.jts.geom.Envelope] = None
    ): CloseableIterator[SimpleFeature] = {

    try {
      logger.debug(s"Executing bin query on table: $tableName with ${gridSize}x${gridSize} grid")
      
      // Build DynamoDB scan request
      val scanRequest = ScanRequest.builder()
        .tableName(tableName)
        .build()
      
      val response = ds.client.scan(scanRequest)
      
      // Convert DynamoDB items to features
      val items = response.items().asScala.toList
      val features = items.flatMap { item =>
        DynamoDbFeatureSerializer.itemToFeature(item.asScala.asJava, sft)
      }
      
      // Create spatial bins
      val binData = createSpatialBins(features, sft, gridSize, envelope)
      
      // Convert bin data to features
      val binFeatures = convertBinsToFeatures(binData, sft, gridSize)
      
      logger.debug(s"Bin query processed ${features.size} features into ${binFeatures.size} spatial bins")
      CloseableIterator(binFeatures.iterator)
      
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error executing bin query on table: $tableName", e)
        CloseableIterator.empty
    }
  }

  /**
    * Create spatial bins from features
    */
  private def createSpatialBins(
      features: List[SimpleFeature],
      sft: SimpleFeatureType,
      gridSize: Int,
      envelope: Option[org.locationtech.jts.geom.Envelope]
    ): SpatialBinData = {
    
    if (features.isEmpty) {
      return SpatialBinData(Array.empty, 0, 0, null)
    }
    
    try {
      // Calculate or use provided envelope
      val bounds = envelope.getOrElse(calculateEnvelope(features))
      
      // Initialize bin grid
      val binGrid = Array.ofDim[Int](gridSize, gridSize)
      
      // Calculate cell dimensions
      val cellWidth = bounds.getWidth / gridSize
      val cellHeight = bounds.getHeight / gridSize
      
      // Process each feature
      features.foreach { feature =>
        val geom = feature.getDefaultGeometry
        if (geom != null && geom.isInstanceOf[Geometry]) {
          val point = extractPoint(geom.asInstanceOf[Geometry])
          if (point != null) {
            val coord = point.getCoordinate
            
            // Calculate bin coordinates
            val binX = math.min(((coord.x - bounds.getMinX) / cellWidth).toInt, gridSize - 1)
            val binY = math.min(((coord.y - bounds.getMinY) / cellHeight).toInt, gridSize - 1)
            
            if (binX >= 0 && binY >= 0) {
              binGrid(binX)(binY) += 1
            }
          }
        }
      }
      
      SpatialBinData(binGrid.flatten, gridSize, gridSize, bounds)
      
    } catch {
      case NonFatal(e) =>
        logger.error("Error creating spatial bins", e)
        SpatialBinData(Array.empty, 0, 0, null)
    }
  }

  /**
    * Extract point from geometry
    */
  private def extractPoint(geom: Geometry): Point = {
    geom match {
      case point: Point => point
      case _ => 
        // For non-point geometries, use centroid
        val centroid = geom.getCentroid
        if (centroid.isInstanceOf[Point]) centroid.asInstanceOf[Point] else null
    }
  }

  /**
    * Calculate envelope from features
    */
  private def calculateEnvelope(features: List[SimpleFeature]): org.locationtech.jts.geom.Envelope = {
    val envelope = new org.locationtech.jts.geom.Envelope()
    
    features.foreach { feature =>
      val geom = feature.getDefaultGeometry
      if (geom != null && geom.isInstanceOf[Geometry]) {
        envelope.expandToInclude(geom.asInstanceOf[Geometry].getEnvelopeInternal)
      }
    }
    
    envelope
  }

  /**
    * Convert bin data to features
    */
  private def convertBinsToFeatures(
      binData: SpatialBinData,
      sft: SimpleFeatureType,
      gridSize: Int
    ): Seq[SimpleFeature] = {
    
    if (binData.values.isEmpty) {
      return Seq.empty
    }
    
    try {
      // Create feature type for bin results
      val binBuilder = new SimpleFeatureTypeBuilder()
      binBuilder.setName("bin_result")
      binBuilder.add("bin_x", classOf[Integer])
      binBuilder.add("bin_y", classOf[Integer])
      binBuilder.add("count", classOf[Integer])
      binBuilder.add("center_x", classOf[java.lang.Double])
      binBuilder.add("center_y", classOf[java.lang.Double])
      binBuilder.add("grid_size", classOf[Integer])
      binBuilder.add("feature_type", classOf[String])
      binBuilder.add("timestamp", classOf[Date])
      val binSft = binBuilder.buildFeatureType()
      
      val features = scala.collection.mutable.ListBuffer[SimpleFeature]()
      val cellWidth = binData.envelope.getWidth / gridSize
      val cellHeight = binData.envelope.getHeight / gridSize
      
      // Create a feature for each non-empty bin
      for (x <- 0 until gridSize; y <- 0 until gridSize) {
        val index = x * gridSize + y
        val count = binData.values(index)
        
        if (count > 0) {
          val centerX = binData.envelope.getMinX + (x + 0.5) * cellWidth
          val centerY = binData.envelope.getMinY + (y + 0.5) * cellHeight
          
          val feature = new ScalaSimpleFeature(binSft, s"bin_${x}_${y}_${System.currentTimeMillis()}")
          feature.setAttribute("bin_x", Integer.valueOf(x))
          feature.setAttribute("bin_y", Integer.valueOf(y))
          feature.setAttribute("count", Integer.valueOf(count))
          feature.setAttribute("center_x", Double.box(centerX))
          feature.setAttribute("center_y", Double.box(centerY))
          feature.setAttribute("grid_size", Integer.valueOf(gridSize))
          feature.setAttribute("feature_type", sft.getTypeName)
          feature.setAttribute("timestamp", new Date())
          
          features += feature
        }
      }
      
      features.toSeq
      
    } catch {
      case NonFatal(e) =>
        logger.error("Error converting bin data to features", e)
        Seq.empty
    }
  }
}

/**
  * Case class to hold spatial bin data
  */
case class SpatialBinData(
    values: Array[Int],
    width: Int,
    height: Int,
    envelope: org.locationtech.jts.geom.Envelope
)
