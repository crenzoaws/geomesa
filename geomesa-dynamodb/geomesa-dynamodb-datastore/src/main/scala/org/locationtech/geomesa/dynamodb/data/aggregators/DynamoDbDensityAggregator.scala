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
  * Simplified aggregator for computing density analysis from DynamoDB data
  */
class DynamoDbDensityAggregator extends LazyLogging {

  /**
    * Execute a density query against DynamoDB
    */
  def query(
      ds: DynamoDbDataStore,
      sft: SimpleFeatureType,
      tableName: String,
      gridWidth: Int = 32,
      gridHeight: Int = 32,
      radius: Option[Double] = None,
      envelope: Option[org.locationtech.jts.geom.Envelope] = None
    ): CloseableIterator[SimpleFeature] = {

    try {
      logger.debug(s"Executing density query on table: $tableName with ${gridWidth}x${gridHeight} grid")
      
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
      
      // Compute density analysis
      val densityData = computeDensityAnalysis(features, sft, gridWidth, gridHeight, radius, envelope)
      
      // Convert density data to features
      val densityFeatures = convertDensityToFeatures(densityData, sft)
      
      logger.debug(s"Density query processed ${features.size} features into ${densityFeatures.size} density results")
      CloseableIterator(densityFeatures.iterator)
      
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error executing density query on table: $tableName", e)
        CloseableIterator.empty
    }
  }

  /**
    * Compute density analysis from features
    */
  private def computeDensityAnalysis(
      features: List[SimpleFeature],
      sft: SimpleFeatureType,
      gridWidth: Int,
      gridHeight: Int,
      radius: Option[Double],
      envelope: Option[org.locationtech.jts.geom.Envelope]
    ): DensityGrid = {
    
    if (features.isEmpty) {
      return DensityGrid(Array.empty, 0, 0, null)
    }
    
    try {
      // Calculate or use provided envelope
      val bounds = envelope.getOrElse(calculateEnvelope(features))
      
      // Initialize density grid
      val densityGrid = Array.ofDim[Double](gridWidth, gridHeight)
      
      // Calculate cell dimensions
      val cellWidth = bounds.getWidth / gridWidth
      val cellHeight = bounds.getHeight / gridHeight
      
      // Extract points from features
      val points = features.flatMap { feature =>
        val geom = feature.getDefaultGeometry
        if (geom != null && geom.isInstanceOf[Geometry]) {
          val point = extractPoint(geom.asInstanceOf[Geometry])
          if (point != null) Some(point.getCoordinate) else None
        } else {
          None
        }
      }
      
      // Compute density using kernel density estimation or simple point counting
      if (radius.isDefined) {
        // Kernel density estimation
        computeKernelDensity(points, densityGrid, bounds, cellWidth, cellHeight, radius.get)
      } else {
        // Simple point density
        computePointDensity(points, densityGrid, bounds, cellWidth, cellHeight)
      }
      
      DensityGrid(densityGrid.flatten, gridWidth, gridHeight, bounds)
      
    } catch {
      case NonFatal(e) =>
        logger.error("Error computing density analysis", e)
        DensityGrid(Array.empty, 0, 0, null)
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
    * Compute kernel density estimation
    */
  private def computeKernelDensity(
      points: List[Coordinate],
      densityGrid: Array[Array[Double]],
      envelope: org.locationtech.jts.geom.Envelope,
      cellWidth: Double,
      cellHeight: Double,
      radius: Double
    ): Unit = {
    
    val radiusSquared = radius * radius
    
    for (x <- densityGrid.indices; y <- densityGrid(x).indices) {
      val cellCenterX = envelope.getMinX + (x + 0.5) * cellWidth
      val cellCenterY = envelope.getMinY + (y + 0.5) * cellHeight
      
      var density = 0.0
      
      points.foreach { point =>
        val dx = point.x - cellCenterX
        val dy = point.y - cellCenterY
        val distanceSquared = dx * dx + dy * dy
        
        if (distanceSquared <= radiusSquared) {
          // Gaussian kernel
          val weight = math.exp(-0.5 * distanceSquared / (radius * radius / 4))
          density += weight
        }
      }
      
      densityGrid(x)(y) = density
    }
  }

  /**
    * Compute simple point density
    */
  private def computePointDensity(
      points: List[Coordinate],
      densityGrid: Array[Array[Double]],
      envelope: org.locationtech.jts.geom.Envelope,
      cellWidth: Double,
      cellHeight: Double
    ): Unit = {
    
    points.foreach { point =>
      val x = math.min(((point.x - envelope.getMinX) / cellWidth).toInt, densityGrid.length - 1)
      val y = math.min(((point.y - envelope.getMinY) / cellHeight).toInt, densityGrid(0).length - 1)
      
      if (x >= 0 && y >= 0) {
        densityGrid(x)(y) += 1.0
      }
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
    * Convert density data to features
    */
  private def convertDensityToFeatures(densityGrid: DensityGrid, sft: SimpleFeatureType): Seq[SimpleFeature] = {
    if (densityGrid.values.isEmpty) {
      return Seq.empty
    }
    
    try {
      // Create feature type for density results
      val densityBuilder = new SimpleFeatureTypeBuilder()
      densityBuilder.setName("density_result")
      densityBuilder.add("grid_width", classOf[Integer])
      densityBuilder.add("grid_height", classOf[Integer])
      densityBuilder.add("total_density", classOf[java.lang.Double])
      densityBuilder.add("max_density", classOf[java.lang.Double])
      densityBuilder.add("min_density", classOf[java.lang.Double])
      densityBuilder.add("avg_density", classOf[java.lang.Double])
      densityBuilder.add("feature_type", classOf[String])
      densityBuilder.add("timestamp", classOf[Date])
      val densitySft = densityBuilder.buildFeatureType()
      
      val feature = new ScalaSimpleFeature(densitySft, s"density_${System.currentTimeMillis()}")
      feature.setAttribute("grid_width", Integer.valueOf(densityGrid.width))
      feature.setAttribute("grid_height", Integer.valueOf(densityGrid.height))
      feature.setAttribute("total_density", Double.box(densityGrid.values.sum))
      feature.setAttribute("max_density", Double.box(densityGrid.values.max))
      feature.setAttribute("min_density", Double.box(densityGrid.values.min))
      feature.setAttribute("avg_density", Double.box(densityGrid.values.sum / densityGrid.values.length))
      feature.setAttribute("feature_type", sft.getTypeName)
      feature.setAttribute("timestamp", new Date())
      
      Seq(feature)
      
    } catch {
      case NonFatal(e) =>
        logger.error("Error converting density grid to features", e)
        Seq.empty
    }
  }
}

/**
  * Case class to hold density grid data
  */
case class DensityGrid(
    values: Array[Double],
    width: Int,
    height: Int,
    envelope: org.locationtech.jts.geom.Envelope
)
