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
package org.apache.spark.streaming.aliyun.logservice

import java.util

import org.apache.spark.streaming.aliyun.logservice.utils.VersionInfoUtils

object LoghubClient {

  private case class CacheKey(accessKeyId: String, accessKeySecret: String, endpoint: String)

  private var cache: util.HashMap[CacheKey, LoghubClientAgent] = _

  def getOrCreate(endpoint: String,
                  accessKeyId: String,
                  accessKeySecret: String,
                  consumerGroup: String): LoghubClientAgent = synchronized {
    if (cache == null) {
      cache = new util.HashMap[CacheKey, LoghubClientAgent]()
    }
    val k = CacheKey(accessKeyId, accessKeySecret, endpoint)
    var client = cache.get(k)
    if (client == null) {
      client = new LoghubClientAgent(endpoint, accessKeyId, accessKeySecret)
      client.setUserAgent(VersionInfoUtils.getUserAgent + "-" + consumerGroup)
      cache.put(k, client)
    }
    client
  }
}
