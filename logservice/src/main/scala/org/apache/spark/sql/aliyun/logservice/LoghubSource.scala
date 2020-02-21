/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.sql.aliyun.logservice

import java.io._
import java.nio.charset.StandardCharsets

import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

import org.I0Itec.zkclient.{ZkClient, ZkConnection}
import org.I0Itec.zkclient.serialize.ZkSerializer
import org.apache.commons.cli.MissingArgumentException
import org.apache.commons.io.IOUtils
import org.apache.hadoop.fs.Path
import org.json4s._
import org.json4s.JsonAST.JString
import org.json4s.jackson.JsonMethods._
import org.json4s.jackson.Serialization

import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.sql.execution.streaming.{HDFSMetadataLog, Offset, SerializedOffset, Source}
import org.apache.spark.sql.types.StructType

class LoghubSource(
    @transient sqlContext: SQLContext,
    override val schema: StructType,
    defaultSchema: Boolean,
    sourceOptions: Map[String, String],
    metadataPath: String,
    startingOffsets: LoghubOffsetRangeLimit,
    @transient loghubOffsetReader: LoghubOffsetReader)
  extends Source with Logging with Serializable {

  private var maxOffsetsPerTrigger =
    sourceOptions.getOrElse("maxOffsetsPerTrigger", 64 * 1024 + "").toLong
  private val endpoint = sourceOptions.getOrElse("endpoint",
    throw new MissingArgumentException("Missing log store endpoint (='endpoint')."))
  private val logProject = sourceOptions.getOrElse("sls.project",
    throw new MissingArgumentException("Missing logService project (='sls.project')."))
  private val logStore = sourceOptions.getOrElse("sls.store",
    throw new MissingArgumentException("Missing logService store (='sls.store')."))

  private lazy val initialPartitionOffsets = {
    val metadataLog =
      new HDFSMetadataLog[LoghubSourceOffset](sqlContext.sparkSession, metadataPath) {
        override def serialize(metadata: LoghubSourceOffset, out: OutputStream): Unit = {
          out.write(0) // A zero byte is written to support Spark 2.1.0 (SPARK-19517)
          val writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))
          writer.write("v" + LoghubSource.VERSION + "\n")
          writer.write(metadata.json())
          writer.flush()
        }

        override def deserialize(in: InputStream): LoghubSourceOffset = {
          in.read() // A zero byte is read to support Spark 2.1.0 (SPARK-19517)
          val content = IOUtils.toString(new InputStreamReader(in, StandardCharsets.UTF_8))
          // HDFSMetadataLog guarantees that it never creates a partial file.
          assert(content.length != 0)
          if (content(0) == 'v') {
            val indexOfNewLine = content.indexOf("\n")
            if (indexOfNewLine > 0) {
              LoghubSourceOffset(
                SerializedOffset(content.substring(indexOfNewLine + 1)), sourceOptions)
            } else {
              throw new IllegalStateException(
                s"Log file was malformed: failed to detect the log file version line.")
            }
          } else {
            // The log was generated by Spark 2.1.0
            LoghubSourceOffset(SerializedOffset(content), sourceOptions)
          }
        }
      }

    metadataLog.get(0).getOrElse {
      val offsets = startingOffsets match {
        case EarliestOffsetRangeLimit =>
          LoghubSourceOffset(loghubOffsetReader.fetchEarliestOffsets())
        case LatestOffsetRangeLimit =>
          LoghubSourceOffset(loghubOffsetReader.fetchLatestOffsets())
        case SpecificOffsetRangeLimit(partitionOffsets) =>
          LoghubSourceOffset(partitionOffsets)
      }
      metadataLog.add(0, offsets)
      logInfo(s"Initial offsets: $offsets")
      offsets
    }.shardToOffsets
  }

  private val dynamicConfigEnable =
    sourceOptions.getOrElse("dynamicConfigEnable", "false").toBoolean
  if (dynamicConfigEnable) {
    enableDynamicConfig()
  }

  private var lastCursorTime: Int = -1

  override def getOffset: Option[Offset] = {
    // Make sure initialPartitionOffsets is initialized
    initialPartitionOffsets

    if (lastCursorTime < 0) {
      lastCursorTime = initialPartitionOffsets.values.map(_._1).max
    }
    val latest = loghubOffsetReader.fetchLatestOffsets()
    val limitCursorTime = loghubOffsetReader.rateLimit(lastCursorTime, Some(maxOffsetsPerTrigger))
    val end = LoghubSourceOffset(latest.map(e => (e._1, limitCursorTime)), sourceOptions)
    lastCursorTime = limitCursorTime
    Some(end)
  }

  override def getBatch(start: Option[Offset], end: Offset): DataFrame = {
    val fromShardOffsets = start match {
      case Some(prevBatchEndOffset) =>
        LoghubSourceOffset.getShardOffsets(prevBatchEndOffset, sourceOptions)
      case None =>
        initialPartitionOffsets
    }
    val shardOffsets = new ArrayBuffer[(Int, Int, Int)]()
    val untilShardOffsets = LoghubSourceOffset.getShardOffsets(end, sourceOptions)
    if (lastCursorTime < 0) {
      lastCursorTime = untilShardOffsets.values.map(_._1).head
    }
    val (shards, newShards) = untilShardOffsets.keySet.partition { shard =>
      fromShardOffsets.contains(shard)
    }
    val earliest = if (newShards.nonEmpty) {
      loghubOffsetReader.fetchEarliestOffsets(newShards)
    } else {
      Map.empty[LoghubShard, (Int, String)]
    }
    shards.toSeq.foreach(shard => {
      shardOffsets.+=((shard.shard, fromShardOffsets(shard)._1, untilShardOffsets(shard)._1))
    })
    newShards.toSeq.foreach(shard => {
      shardOffsets.+=((shard.shard, earliest(shard)._1, untilShardOffsets(shard)._1))
    })
    val rdd = new LoghubSourceRDD(sqlContext.sparkContext, shardOffsets, schema.fieldNames,
      schema.toDDL, defaultSchema, sourceOptions)

    sqlContext.internalCreateDataFrame(rdd, schema, isStreaming = true)
  }

  def enableDynamicConfig(): Unit = {
    val zkConnect = sourceOptions.getOrElse("zookeeper.connect",
      throw new MissingArgumentException("Missing zookeeper connect URL (='zookeeper.connect')."))
    val zkSessionTimeoutMs = sourceOptions.getOrElse("zookeeper.session.timeoutMs", "10000").toInt
    val zkConnectTimeoutMs = sourceOptions.getOrElse("zookeeper.connect.timeoutMs", "10000").toInt
    val zkConnection = new ZkConnection(zkConnect, zkSessionTimeoutMs)
    val zkClient = new ZkClient(zkConnection, zkConnectTimeoutMs, new ZKStringSerializer())
    val checkpointRoot = new Path(metadataPath).getParent.toUri.getPath
    if (!zkClient.exists(checkpointRoot)) {
      zkClient.createPersistent(checkpointRoot, true)
    }
    val dynamicConfigManager = DynamicConfigManager
      .getOrCreateDynamicConfigManager(checkpointRoot, zkConnect, zkSessionTimeoutMs)
    val handler = new ZNodeChangeHandler {
      private implicit val formats = Serialization.formats(NoTypeHints)

      override val path: String = s"$checkpointRoot/config"

      override def handleCreation(): Unit = {
        handle()
      }

      override def handleDataChange(): Unit = {
        handle()
      }

      private def handle(): Unit = {
        val data: String = zkClient.readData(path)
        try {
          val JString(version) = parse(data) \ "version"
          if (version.equals("v1")) {
            parse(data) \ "config" \ logProject \ logStore \ "maxOffsetsPerTrigger" match {
              case JNothing => // ok
              case JString(value) =>
                if (maxOffsetsPerTrigger != value.toLong) {
                  logInfo(s"Config 'maxOffsetsPerTrigger' for [$logProject/$logStore] is " +
                    s"changed to ${value.toLong} from $maxOffsetsPerTrigger.")
                  maxOffsetsPerTrigger = value.toLong
                }
            }
          } else {
            logError(
              s"""Unsupported dynamic config data version $version, only support ["v1"].
                 |$formatMsg
               """.stripMargin)
          }
        } catch {
          case NonFatal(_) => logError(s"""$formatMsg, got $data""")
        }
      }

      private val formatMsg =
        s"""Expected
           |{
           |  "version":"v1",
           |  "config":
           |  {
           |     "logProject-A":
           |     {
           |       "logStore-A":
           |       {
           |         "config-A":"abc",
           |         "config-B":"efg"
           |       },
           |       "logStore-B":
           |       {
           |         "config-A":"abc",
           |         "config-B":"efg"
           |       }
           |     }
           |  }
           |}"""
    }

    dynamicConfigManager.registerZNodeChangeHandler(name, handler)
  }

  override def stop(): Unit = {
    loghubOffsetReader.close()
  }

  def name: String = s"LoghubSource[$logProject/$logStore@$endpoint]"

  override def toString: String = s"LoghubSource[$loghubOffsetReader]"
}

object LoghubSource {
  val VERSION = 1
}

private[logservice] class ZKStringSerializer extends ZkSerializer {
  override def serialize(o: scala.Any): Array[Byte] = {
    try {
      o.asInstanceOf[String].getBytes("UTF-8")
    } catch {
      case _: UnsupportedEncodingException => null
    }
  }

  override def deserialize(bytes: Array[Byte]): AnyRef = {
    if (bytes == null) {
      null
    } else {
      try {
        new String(bytes, "UTF-8")
      } catch {
        case _: UnsupportedEncodingException => null
      }
    }
  }
}