/*
 * Copyright (c) 2025-2025 Amazon.com
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Apache License, Version 2.0
 * which accompanies this distribution and is available at
 * http://www.opensource.org/licenses/apache2.0.php.
 */

package org.locationtech.geomesa.dynamodb.data.util

import com.typesafe.scalalogging.LazyLogging
import org.geotools.api.feature.simple.{SimpleFeature, SimpleFeatureType}
import org.locationtech.geomesa.features.ScalaSimpleFeature
import org.locationtech.geomesa.utils.geotools.SimpleFeatureTypes
import org.locationtech.geomesa.utils.text.WKTUtils
import org.locationtech.jts.geom.Geometry
import software.amazon.awssdk.services.dynamodb.model.AttributeValue

import java.util.{Date, HashMap => JHashMap, Map => JMap}
import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

/**
  * Utility for serializing/deserializing SimpleFeatures to/from DynamoDB items
  */
object DynamoDbFeatureSerializer extends LazyLogging {

  private val ID_FIELD = "feature_id"
  private val GEOMETRY_FIELD = "geometry"
  private val ATTRIBUTES_FIELD = "attributes"
  private val FEATURE_TYPE_FIELD = "feature_type"

  /**
    * Convert a SimpleFeature to a DynamoDB item
    */
  def featureToItem(feature: SimpleFeature): JMap[String, AttributeValue] = {
    val item = new JHashMap[String, AttributeValue]()
    
    // Add feature ID
    item.put(ID_FIELD, AttributeValue.builder().s(feature.getID).build())
    
    // Add feature type name
    item.put(FEATURE_TYPE_FIELD, AttributeValue.builder().s(feature.getFeatureType.getTypeName).build())
    
    // Add geometry if present
    val geom = feature.getDefaultGeometry
    if (geom != null) {
      val wkt = WKTUtils.write(geom.asInstanceOf[Geometry])
      item.put(GEOMETRY_FIELD, AttributeValue.builder().s(wkt).build())
    }
    
    // Add other attributes
    val attributeMap = new JHashMap[String, AttributeValue]()
    val sft = feature.getFeatureType
    
    for (i <- 0 until sft.getAttributeCount) {
      val descriptor = sft.getAttributeDescriptors.get(i)
      val name = descriptor.getLocalName
      val value = feature.getAttribute(i)
      
      if (value != null && !name.equals(sft.getGeometryDescriptor.getLocalName)) {
        val attributeValue = valueToAttributeValue(value)
        if (attributeValue != null) {
          attributeMap.put(name, attributeValue)
        }
      }
    }
    
    if (!attributeMap.isEmpty) {
      item.put(ATTRIBUTES_FIELD, AttributeValue.builder().m(attributeMap).build())
    }
    
    item
  }

  /**
    * Convert a DynamoDB item to a SimpleFeature
    */
  def itemToFeature(item: JMap[String, AttributeValue], sft: SimpleFeatureType): Option[SimpleFeature] = {
    Try {
      val featureId = item.get(ID_FIELD).s()
      val feature = new ScalaSimpleFeature(sft, featureId)
      
      // Set geometry if present
      Option(item.get(GEOMETRY_FIELD)).foreach { geomAttr =>
        val wkt = geomAttr.s()
        val geometry = WKTUtils.read(wkt)
        feature.setDefaultGeometry(geometry)
      }
      
      // Set other attributes
      Option(item.get(ATTRIBUTES_FIELD)).foreach { attrsAttr =>
        val attributeMap = attrsAttr.m()
        
        for (i <- 0 until sft.getAttributeCount) {
          val descriptor = sft.getAttributeDescriptors.get(i)
          val name = descriptor.getLocalName
          
          if (!name.equals(sft.getGeometryDescriptor.getLocalName)) {
            Option(attributeMap.get(name)).foreach { attrValue =>
              val value = attributeValueToValue(attrValue, descriptor.getType.getBinding)
              feature.setAttribute(i, value.asInstanceOf[AnyRef])
            }
          }
        }
      }
      
      feature
    } match {
      case Success(feature) => Some(feature)
      case Failure(e) =>
        logger.error(s"Failed to deserialize feature from DynamoDB item", e)
        None
    }
  }

  /**
    * Convert a Java value to DynamoDB AttributeValue
    */
  private def valueToAttributeValue(value: Any): AttributeValue = {
    value match {
      case null => null
      case s: String => AttributeValue.builder().s(s).build()
      case i: Integer => AttributeValue.builder().n(i.toString).build()
      case l: java.lang.Long => AttributeValue.builder().n(l.toString).build()
      case d: java.lang.Double => AttributeValue.builder().n(d.toString).build()
      case f: java.lang.Float => AttributeValue.builder().n(f.toString).build()
      case b: java.lang.Boolean => AttributeValue.builder().bool(b).build()
      case date: Date => AttributeValue.builder().n(date.getTime.toString).build()
      case bytes: Array[Byte] => AttributeValue.builder().b(software.amazon.awssdk.core.SdkBytes.fromByteArray(bytes)).build()
      case _ => 
        logger.warn(s"Unsupported attribute type: ${value.getClass.getName}, converting to string")
        AttributeValue.builder().s(value.toString).build()
    }
  }

  /**
    * Convert DynamoDB AttributeValue to Java value
    */
  private def attributeValueToValue(attrValue: AttributeValue, targetClass: Class[_]): Any = {
    if (attrValue.s() != null) {
      targetClass match {
        case c if c == classOf[String] => attrValue.s()
        case c if c == classOf[Date] => new Date(attrValue.s().toLong)
        case _ => attrValue.s()
      }
    } else if (attrValue.n() != null) {
      val numStr = attrValue.n()
      targetClass match {
        case c if c == classOf[Integer] || c == classOf[java.lang.Integer] => numStr.toInt
        case c if c == classOf[java.lang.Long] => numStr.toLong
        case c if c == classOf[java.lang.Double] => numStr.toDouble
        case c if c == classOf[java.lang.Float] => numStr.toFloat
        case c if c == classOf[Date] => new Date(numStr.toLong)
        case _ => numStr.toDouble
      }
    } else if (attrValue.bool() != null) {
      attrValue.bool()
    } else if (attrValue.b() != null) {
      val buffer = attrValue.b()
      buffer.asByteArray()
    } else {
      null
    }
  }

  /**
    * Generate table name for a feature type
    */
  def getTableName(catalog: String, prefix: String, typeName: String): String = {
    s"${prefix}${catalog}_$typeName"
  }

  /**
    * Generate primary key for a feature
    */
  def getPrimaryKey(featureId: String): JMap[String, AttributeValue] = {
    Map(ID_FIELD -> AttributeValue.builder().s(featureId).build()).asJava
  }
}
