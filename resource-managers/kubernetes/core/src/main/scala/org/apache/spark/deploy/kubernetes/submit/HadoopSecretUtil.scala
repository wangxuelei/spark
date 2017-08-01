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
package org.apache.spark.deploy.kubernetes.submit

import io.fabric8.kubernetes.api.model.{Container, ContainerBuilder, Pod, PodBuilder}

import org.apache.spark.deploy.kubernetes.constants._

object HadoopSecretUtil {

  def configurePod(secretNameOption: Option[String], pod: Pod) : Pod = {
    secretNameOption.map { secret =>
      new PodBuilder(pod)
        .editOrNewSpec()
        .addNewVolume()
        .withName(SPARK_APP_HADOOP_SECRET_VOLUME_NAME)
        .withNewSecret()
        .withSecretName(secret)
        .endSecret()
        .endVolume()
        .endSpec()
        .build()
    }.getOrElse(pod)
  }

  def configureContainer(secretNameOption: Option[String],
                         containerSpec: Container) : Container = {
    secretNameOption.map { secret =>
      new ContainerBuilder(containerSpec)
        .addNewVolumeMount()
        .withName(SPARK_APP_HADOOP_SECRET_VOLUME_NAME)
        .withMountPath(SPARK_APP_HADOOP_CREDENTIALS_BASE_DIR)
        .endVolumeMount()
        .addNewEnv()
        .withName(ENV_HADOOP_TOKEN_FILE_LOCATION)
        .withValue(SPARK_APP_HADOOP_TOKEN_FILE_PATH)
        .endEnv()
        .build()
    }.getOrElse(containerSpec)
  }
}
