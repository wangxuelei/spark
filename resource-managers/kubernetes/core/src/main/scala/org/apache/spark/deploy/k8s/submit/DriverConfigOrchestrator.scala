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
package org.apache.spark.deploy.k8s.submit

import org.apache.spark.{SparkConf, SparkException}
import org.apache.spark.deploy.k8s.{KubernetesUtils, MountSecretsBootstrap}
import org.apache.spark.deploy.k8s.Config._
import org.apache.spark.deploy.k8s.Constants._
import org.apache.spark.deploy.k8s.submit.steps._
import org.apache.spark.launcher.SparkLauncher
import org.apache.spark.util.SystemClock
import org.apache.spark.util.Utils

/**
 * Figures out and returns the complete ordered list of needed DriverConfigurationSteps to
 * configure the Spark driver pod. The returned steps will be applied one by one in the given
 * order to produce a final KubernetesDriverSpec that is used in KubernetesClientApplication
 * to construct and create the driver pod.
 */
private[spark] class DriverConfigOrchestrator(
    kubernetesAppId: String,
    kubernetesResourceNamePrefix: String,
    mainAppResource: Option[MainAppResource],
    appName: String,
    mainClass: String,
    appArgs: Array[String],
    sparkConf: SparkConf) {

  // The resource name prefix is derived from the Spark application name, making it easy to connect
  // the names of the Kubernetes resources from e.g. kubectl or the Kubernetes dashboard to the
  // application the user submitted.

  private val imagePullPolicy = sparkConf.get(CONTAINER_IMAGE_PULL_POLICY)

  def getAllConfigurationSteps: Seq[DriverConfigurationStep] = {
    val driverCustomLabels = KubernetesUtils.parsePrefixedKeyValuePairs(
      sparkConf,
      KUBERNETES_DRIVER_LABEL_PREFIX)
    require(!driverCustomLabels.contains(SPARK_APP_ID_LABEL), "Label with key " +
      s"$SPARK_APP_ID_LABEL is not allowed as it is reserved for Spark bookkeeping " +
      "operations.")
    require(!driverCustomLabels.contains(SPARK_ROLE_LABEL), "Label with key " +
      s"$SPARK_ROLE_LABEL is not allowed as it is reserved for Spark bookkeeping " +
      "operations.")

    val secretNamesToMountPaths = KubernetesUtils.parsePrefixedKeyValuePairs(
      sparkConf,
      KUBERNETES_DRIVER_SECRETS_PREFIX)

    val allDriverLabels = driverCustomLabels ++ Map(
      SPARK_APP_ID_LABEL -> kubernetesAppId,
      SPARK_ROLE_LABEL -> SPARK_POD_DRIVER_ROLE)

    val serviceBootstrapStep = new DriverServiceBootstrapStep(
      kubernetesResourceNamePrefix,
      allDriverLabels,
      sparkConf,
      new SystemClock)

    val additionalMainAppJar = if (mainAppResource.nonEmpty) {
       val mayBeResource = mainAppResource.get match {
        case JavaMainAppResource(resource) if resource != SparkLauncher.NO_RESOURCE =>
          Some(resource)
        case _ => None
      }
      mayBeResource
    } else {
      None
    }

    val sparkJars = sparkConf.getOption("spark.jars")
      .map(_.split(","))
      .getOrElse(Array.empty[String]) ++
      additionalMainAppJar.toSeq
    val sparkFiles = sparkConf.getOption("spark.files")
      .map(_.split(","))
      .getOrElse(Array.empty[String])

    // TODO(SPARK-23153): remove once submission client local dependencies are supported.
    if (existSubmissionLocalFiles(sparkJars) || existSubmissionLocalFiles(sparkFiles)) {
      throw new SparkException("The Kubernetes mode does not yet support referencing application " +
        "dependencies in the local file system.")
    }

    val dependencyResolutionStep = if (sparkJars.nonEmpty || sparkFiles.nonEmpty) {
      Seq(new DependencyResolutionStep(
        sparkJars,
        sparkFiles))
    } else {
      Nil
    }

    val mountSecretsStep = if (secretNamesToMountPaths.nonEmpty) {
      Seq(new DriverMountSecretsStep(new MountSecretsBootstrap(secretNamesToMountPaths)))
    } else {
      Nil
    }

    Seq(
      serviceBootstrapStep) ++
      dependencyResolutionStep ++
      mountSecretsStep
  }

  private def existSubmissionLocalFiles(files: Seq[String]): Boolean = {
    files.exists { uri =>
      Utils.resolveURI(uri).getScheme == "file"
    }
  }

  private def existNonContainerLocalFiles(files: Seq[String]): Boolean = {
    files.exists { uri =>
      Utils.resolveURI(uri).getScheme != "local"
    }
  }
}
