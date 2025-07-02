/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.tools

import org.locationtech.geomesa.dynamodb.tools.commands._
import org.locationtech.geomesa.tools.{Command, Runner}

object DynamoDbRunner extends Runner {

  override val name: String = "geomesa-dynamodb"

  override def createCommands(jars: Array[String]): Seq[Command] = {
    Seq(
      new DynamoDbCreateSchemaCommand,
      new DynamoDbDeleteCatalogCommand,
      new DynamoDbDeleteFeaturesCommand,
      new DynamoDbDescribeSchemaCommand,
      new DynamoDbExplainCommand,
      new DynamoDbExportCommand,
      new DynamoDbGetTypeNamesCommand,
      new DynamoDbIngestCommand,
      new DynamoDbKeywordsCommand,
      new DynamoDbRemoveSchemaCommand,
      new DynamoDbStatsAnalyzeCommand,
      new DynamoDbStatsBoundsCommand,
      new DynamoDbStatsCountCommand,
      new DynamoDbStatsHistogramCommand,
      new DynamoDbStatsTopKCommand,
      new DynamoDbVersionRemoteCommand,
      new DynamoDbManagePartitionsCommand,
      new DynamoDbCreateTablesCommand
    )
  }

  override def environmentErrorInfo(): Option[String] = {
    if (sys.env.get("AWS_ACCESS_KEY_ID").isEmpty && sys.env.get("AWS_PROFILE").isEmpty) {
      Option("Warning: AWS credentials not found in environment variables or AWS_PROFILE not set. " +
        "Please configure AWS credentials using AWS CLI, environment variables, or IAM roles.")
    } else {
      None
    }
  }
}
