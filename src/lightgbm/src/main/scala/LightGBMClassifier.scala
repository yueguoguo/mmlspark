// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import org.apache.spark.ml.param._
import org.apache.spark.ml.util._
import org.apache.spark.ml.classification.{ProbabilisticClassificationModel, ProbabilisticClassifier}
import org.apache.spark.ml.linalg.{DenseVector, SparseVector, Vector, Vectors}
import org.apache.spark.sql._

import scala.reflect.runtime.universe.{TypeTag, typeTag}

object LightGBMClassifier extends DefaultParamsReadable[LightGBMClassifier] {
  /** The default port for LightGBM network initialization
    */
  val defaultLocalListenPort = 12400
  /** The default timeout for LightGBM network initialization
    */
  val defaultListenTimeout = 120
}

class LightGBMClassifier(override val uid: String)
  extends ProbabilisticClassifier[Vector, LightGBMClassifier, LightGBMClassificationModel]
  with MMLParams {
  def this() = this(Identifiable.randomUID("LightGBMClassifier"))

  val parallelism = StringParam(this, "parallelism",
    "Tree learner parallelism, can be set to data_parallel or voting_parallel", "data_parallel")

  def getParallelism: String = $(parallelism)
  def setParallelism(value: String): this.type = set(parallelism, value)

  val defaultListenPort = IntParam(this, "defaultListenPort",
    "The default listen port on executors, used for testing")

  def getDefaultListenPort: Int = $(defaultListenPort)
  def setDefaultListenPort(value: Int): this.type = set(defaultListenPort, value)

  setDefault(defaultListenPort -> LightGBMClassifier.defaultLocalListenPort)

  val numIterations = IntParam(this, "numIterations",
    "Number of iterations, LightGBM constructs num_class * num_iterations trees", 100)

  def getNumIterations: Int = $(numIterations)
  def setNumIterations(value: Int): this.type = set(numIterations, value)

  val learningRate = DoubleParam(this, "learningRate", "Learning rate or shrinkage rate", 0.1)

  def getLearningRate: Double = $(learningRate)
  def setLearningRate(value: Double): this.type = set(learningRate, value)

  val numLeaves = IntParam(this, "numLeaves", "Number of leaves", 31)

  def getNumLeaves: Int = $(numLeaves)
  def setNumLeaves(value: Int): this.type = set(numLeaves, value)

  /** Trains the LightGBM Classification model.
    *
    * @param dataset The input dataset to train.
    * @return The trained model.
    */
  override protected def train(dataset: Dataset[_]): LightGBMClassificationModel = {
    val df = dataset.toDF()
    df.cache()
    val (nodes, numNodes) = LightGBMUtils.getNodes(df, getDefaultListenPort)
    /* Run a parallel job via map partitions to initialize the native library and network,
     * translate the data to the LightGBM in-memory representation and train the models
     */
    val encoder = Encoders.kryo[LightGBMBooster]
    log.info(s"Nodes used for LightGBM: $nodes")
    val trainParams = TrainParams(getNumIterations, getLearningRate, getNumLeaves)
    val lightGBMBooster = df
      .mapPartitions(TrainUtils.trainLightGBM(nodes, numNodes, getLabelCol, getFeaturesCol,
        getParallelism, getDefaultListenPort, log, trainParams))(encoder)
      .reduce((booster1, booster2) => booster1)
    new LightGBMClassificationModel(uid, lightGBMBooster, getLabelCol, getFeaturesCol,
      getPredictionCol, getProbabilityCol, getRawPredictionCol,
      if (isDefined(thresholds)) Some(getThresholds) else None)
  }

  override def copy(extra: ParamMap): LightGBMClassifier = defaultCopy(extra)
}

/** Model produced by [[LightGBMClassifier]]. */
class LightGBMClassificationModel(override val uid: String, model: LightGBMBooster, labelColName: String,
                                  featuresColName: String, predictionColName: String, probColName: String,
                                  rawPredictionColName: String, thresholdValues: Option[Array[Double]])
  extends ProbabilisticClassificationModel[Vector, LightGBMClassificationModel]
    with ConstructorWritable[LightGBMClassificationModel] {

  // Update the underlying Spark ML params
  // (for proper serialization to work we put them on constructor instead of using copy as in Spark ML)
  set(labelCol, labelColName)
  set(featuresCol, featuresColName)
  set(predictionCol, predictionColName)
  set(probabilityCol, probColName)
  set(rawPredictionCol, rawPredictionColName)
  if (thresholdValues.isDefined) set(thresholds, thresholdValues.get)

  override protected def raw2probabilityInPlace(rawPrediction: Vector): Vector = {
    rawPrediction match {
      case dv: DenseVector =>
        dv.values(0) = 1.0 / (1.0 + math.exp(-2.0 * dv.values(0)))
        dv.values(1) = 1.0 - dv.values(0)
        dv
      case sv: SparseVector =>
        throw new RuntimeException("Unexpected error in LightGBMClassificationModel:" +
          " raw2probabilityInPlace encountered SparseVector")
    }
  }

  override def numClasses: Int = model.numClasses()

  override protected def predictRaw(features: Vector): Vector = {
    val prediction = model.scoreRaw(features)
    Vectors.dense(Array(-prediction, prediction))
  }

  override def copy(extra: ParamMap): LightGBMClassificationModel = defaultCopy(extra)

  override val ttag: TypeTag[LightGBMClassificationModel] = typeTag[LightGBMClassificationModel]

  override def objectsToSave: List[Any] = List(uid, model, getLabelCol, getFeaturesCol, getPredictionCol,
    getProbabilityCol, getRawPredictionCol, thresholdValues)
}

object LightGBMClassificationModel extends ConstructorReadable[LightGBMClassificationModel]
