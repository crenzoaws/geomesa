/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import org.locationtech.geomesa.dynamodb.data.DynamoDbDataStore
import org.locationtech.geomesa.index.stats.{GeoMesaStats, MetadataBackedStats}
import org.locationtech.geomesa.index.stats.MetadataBackedStats.{StatsMetadataSerializer, WritableStat}
import org.locationtech.geomesa.utils.stats.Stat

/**
  * DynamoDB stats implementation
  *
  * @param ds data store
  * @param metadata metadata instance
  */
class DynamoDbGeoMesaStats(ds: DynamoDbDataStore, metadata: DynamoDbBackedMetadata[Stat])
    extends MetadataBackedStats(ds, metadata) {

  override protected def write(typeName: String, stats: Seq[WritableStat]): Unit = {
    val (merge, overwrite) = stats.partition(_.merge)
    
    // Insert stats that can be merged
    if (merge.nonEmpty) {
      metadata.insert(typeName, merge.map(s => s.key -> s.stat).toMap)
      // Invalidate cache for merged stats
      merge.foreach(s => metadata.invalidateCache(typeName, s.key))
    }
    
    // For overwrite stats, remove existing and insert new
    if (overwrite.nonEmpty) {
      metadata.remove(typeName, overwrite.map(_.key))
      metadata.insert(typeName, overwrite.map(s => s.key -> s.stat).toMap)
    }
  }
}

object DynamoDbGeoMesaStats {

  /**
    * Creates stats for a DynamoDB data store
    *
    * @param ds data store
    * @return
    */
  def apply(ds: DynamoDbDataStore): GeoMesaStats = {
    val serializer = new StatsMetadataSerializer(ds)
    val metadata = new DynamoDbBackedMetadata(ds.client, ds.config.catalog, "_stats", serializer)
    new DynamoDbGeoMesaStats(ds, metadata)
  }
}
