/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.tools.commands

import com.beust.jcommander.Parameters
import org.locationtech.geomesa.dynamodb.tools.{DynamoDbDataStoreCommand, DynamoDbParams}
import org.locationtech.geomesa.tools._
import org.locationtech.geomesa.tools.export._
import org.locationtech.geomesa.tools.ingest._
import org.locationtech.geomesa.tools.stats._
import org.locationtech.geomesa.tools.status._

// Schema commands
class DynamoDbCreateSchemaCommand extends CreateSchemaCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new CreateSchemaParams with DynamoDbParams
}

class DynamoDbDeleteCatalogCommand extends DeleteCatalogCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new DeleteCatalogParams with DynamoDbParams
}

class DynamoDbDescribeSchemaCommand extends DescribeSchemaCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new DescribeSchemaParams with DynamoDbParams
}

class DynamoDbGetTypeNamesCommand extends GetTypeNamesCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new GetTypeNamesParams with DynamoDbParams
}

class DynamoDbRemoveSchemaCommand extends RemoveSchemaCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new RemoveSchemaParams with DynamoDbParams
}

// Data commands
class DynamoDbDeleteFeaturesCommand extends DeleteFeaturesCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new DeleteFeaturesParams with DynamoDbParams
}

class DynamoDbExplainCommand extends ExplainCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new ExplainParams with DynamoDbParams
}

class DynamoDbExportCommand extends ExportCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new ExportParams with DynamoDbParams
}

class DynamoDbIngestCommand extends IngestCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new IngestParams with DynamoDbParams
}

class DynamoDbKeywordsCommand extends KeywordsCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new KeywordsParams with DynamoDbParams
}

// Stats commands
class DynamoDbStatsAnalyzeCommand extends StatsAnalyzeCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new StatsAnalyzeParams with DynamoDbParams
}

class DynamoDbStatsBoundsCommand extends StatsBoundsCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new StatsBoundsParams with DynamoDbParams
}

class DynamoDbStatsCountCommand extends StatsCountCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new StatsCountParams with DynamoDbParams
}

class DynamoDbStatsHistogramCommand extends StatsHistogramCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new StatsHistogramParams with DynamoDbParams
}

class DynamoDbStatsTopKCommand extends StatsTopKCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new StatsTopKParams with DynamoDbParams
}

// Status commands
class DynamoDbVersionRemoteCommand extends VersionRemoteCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new VersionRemoteParams with DynamoDbParams
}

class DynamoDbManagePartitionsCommand extends ManagePartitionsCommand[org.geotools.api.data.DataStore] with DynamoDbDataStoreCommand {
  override val params = new ManagePartitionsParams with DynamoDbParams
}
