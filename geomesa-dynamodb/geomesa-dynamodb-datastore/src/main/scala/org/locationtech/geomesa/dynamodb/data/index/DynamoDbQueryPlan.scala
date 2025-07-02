/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.index

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.feature.simple.SimpleFeature
import org.locationtech.geomesa.index.api.QueryPlan.{FeatureReducer, ResultsToFeatures}
import org.locationtech.geomesa.index.api.{QueryPlan, QueryStrategy}
import org.locationtech.geomesa.utils.collection.CloseableIterator
import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
import org.locationtech.geomesa.dynamodb.data.aggregators._
import org.locationtech.geomesa.dynamodb.data.util.DynamoDbFeatureSerializer
import software.amazon.awssdk.services.dynamodb.model._

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

/**
  * Enhanced DynamoDB query plan with aggregation support
  */
class DynamoDbQueryPlan(
    ds: DynamoDbDataStore,
    strategy: QueryStrategy
  ) extends QueryPlan[DynamoDbDataStore] with LazyLogging {

  import org.locationtech.geomesa.index.api.QueryPlan._

  override type Results = SimpleFeature

  override val filter: org.locationtech.geomesa.index.api.FilterStrategy = strategy.filter
  override val reducer: Option[FeatureReducer] = None
  override val sort: Option[Seq[(String, Boolean)]] = None
  override val maxFeatures: Option[Int] = None
  override val projection: Option[org.locationtech.geomesa.index.utils.Reprojection.QueryReferenceSystems] = None
  override val resultsToFeatures: ResultsToFeatures[SimpleFeature] = ResultsToFeatures.identity(strategy.index.sft)

  // Aggregators
  private val statsAggregator = new DynamoDbStatsAggregator()
  private val binAggregator = new DynamoDbBinAggregator()
  private val densityAggregator = new DynamoDbDensityAggregator()

  override def scan(ds: DynamoDbDataStore): CloseableIterator[SimpleFeature] = {
    val sft = strategy.index.sft
    val tableName = DynamoDbFeatureSerializer.getTableName(ds.config.catalog, ds.config.tablePrefix, sft.getTypeName)
    
    // Check if this is an aggregation query based on hints
    val hints = strategy.hints
    val aggregationType = Option(hints.get("aggregation.type")).map(_.toString)
    
    aggregationType match {
      case Some("stats") =>
        executeStatsQuery(ds, sft, tableName, hints)
      case Some("bin") =>
        executeBinQuery(ds, sft, tableName, hints)
      case Some("density") =>
        executeDensityQuery(ds, sft, tableName, hints)
      case _ =>
        executeStandardQuery(ds, tableName)
    }
  }

  /**
    * Execute statistics aggregation query
    */
  private def executeStatsQuery(ds: DynamoDbDataStore, sft: org.geotools.api.feature.simple.SimpleFeatureType, tableName: String, hints: org.geotools.util.factory.Hints): CloseableIterator[SimpleFeature] = {
    logger.debug("Executing stats aggregation query")
    val attributes = Option(hints.get("stats.attributes")).map(_.toString.split(",").toSeq).getOrElse(Seq.empty)
    statsAggregator.query(ds, sft, tableName, attributes)
  }

  /**
    * Execute bin aggregation query
    */
  private def executeBinQuery(ds: DynamoDbDataStore, sft: org.geotools.api.feature.simple.SimpleFeatureType, tableName: String, hints: org.geotools.util.factory.Hints): CloseableIterator[SimpleFeature] = {
    logger.debug("Executing bin aggregation query")
    val gridSize = Option(hints.get("bin.grid.size")).map(_.toString.toInt).getOrElse(32)
    binAggregator.query(ds, sft, tableName, gridSize)
  }

  /**
    * Execute density aggregation query
    */
  private def executeDensityQuery(ds: DynamoDbDataStore, sft: org.geotools.api.feature.simple.SimpleFeatureType, tableName: String, hints: org.geotools.util.factory.Hints): CloseableIterator[SimpleFeature] = {
    logger.debug("Executing density aggregation query")
    val gridWidth = Option(hints.get("density.grid.width")).map(_.toString.toInt).getOrElse(32)
    val gridHeight = Option(hints.get("density.grid.height")).map(_.toString.toInt).getOrElse(32)
    val radius = Option(hints.get("density.radius")).map(_.toString.toDouble)
    densityAggregator.query(ds, sft, tableName, gridWidth, gridHeight, radius)
  }

  /**
    * Execute standard (non-aggregation) query
    */
  private def executeStandardQuery(ds: DynamoDbDataStore, tableName: String): CloseableIterator[SimpleFeature] = {
    try {
      logger.debug(s"Executing standard query on table: $tableName")
      
      // Build DynamoDB scan request
      val scanRequestBuilder = ScanRequest.builder()
        .tableName(tableName)
      
      // Add filter expression if we have filters
      val filterExpression = buildFilterExpression()
      if (filterExpression.nonEmpty) {
        scanRequestBuilder.filterExpression(filterExpression)
      }
      
      // Add projection if specified
      val projectionExpression = buildProjectionExpression()
      if (projectionExpression.nonEmpty) {
        scanRequestBuilder.projectionExpression(projectionExpression.mkString(", "))
      }
      
      // Add limit if specified
      maxFeatures.foreach { limit =>
        scanRequestBuilder.limit(limit)
      }
      
      val scanRequest = scanRequestBuilder.build()
      val response = ds.client.scan(scanRequest)
      val items = response.items().asScala.toList
      
      val features = items.flatMap { item =>
        DynamoDbFeatureSerializer.itemToFeature(item.asScala.asJava, strategy.index.sft)
      }
      
      logger.debug(s"Standard query returned ${features.size} features from table: $tableName")
      CloseableIterator(features.iterator)
      
    } catch {
      case NonFatal(e) =>
        logger.error(s"Error executing standard query on table: $tableName", e)
        CloseableIterator.empty
    }
  }

  /**
    * Build filter expression from strategy filters
    */
  private def buildFilterExpression(): String = {
    // This would convert GeoMesa filters to DynamoDB filter expressions
    // For now, return empty string - full implementation would require comprehensive filter translation
    ""
  }

  /**
    * Build projection expression for attribute selection
    */
  private def buildProjectionExpression(): Seq[String] = {
    val attributes = scala.collection.mutable.ListBuffer[String]()
    
    // Always include primary key
    attributes += "feature_id"
    
    // Include geometry if present
    if (strategy.index.sft.getGeometryDescriptor != null) {
      attributes += "geometry"
    }
    
    // Include feature type
    attributes += "feature_type"
    
    // Include attributes
    attributes += "attributes"
    
    attributes.toSeq
  }

  override def explain(explainer: org.locationtech.geomesa.index.utils.Explainer, prefix: String): Unit = {
    explainer.pushLevel(s"${prefix}DynamoDbQueryPlan")
    explainer(s"Table: ${strategy.index.name}")
    explainer(s"Filter: ${strategy.filter}")
    
    // Add aggregation information if present
    val hints = strategy.hints
    val aggregationType = Option(hints.get("aggregation.type")).map(_.toString)
    aggregationType match {
      case Some(aggType) =>
        explainer(s"Aggregation: $aggType")
      case None =>
        explainer("Query Type: Standard")
    }
    
    explainer(s"Max Features: ${maxFeatures.getOrElse("unlimited")}")
    explainer.popLevel()
  }
}
