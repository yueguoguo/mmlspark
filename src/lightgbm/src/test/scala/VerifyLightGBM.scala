// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import org.apache.spark.ml.evaluation.{BinaryClassificationEvaluator, MulticlassClassificationEvaluator}
import org.apache.spark.ml.util.MLReadable
import org.apache.spark.sql.DataFrame

/** Tests to validate the functionality of LightGBM module.
  */
class VerifyLightGBM extends Benchmarks with EstimatorFuzzing[LightGBMClassifier] {
  lazy val moduleName = "lightgbm"
  var portIndex = 0
  val numPartitions = 2

  // TODO: Need to add multiclass param with objective function
  // verifyLearnerOnMulticlassCsvFile("abalone.csv",                  "Rings", 2)
  // verifyLearnerOnMulticlassCsvFile("BreastTissue.csv",             "Class", 2)
  // verifyLearnerOnMulticlassCsvFile("CarEvaluation.csv",            "Col7", 2)
  verifyLearnerOnBinaryCsvFile("PimaIndian.csv",                   "Diabetes mellitus", 2)
  verifyLearnerOnBinaryCsvFile("data_banknote_authentication.csv", "class", 2)
  verifyLearnerOnBinaryCsvFile("task.train.csv",                   "TaskFailed10", 2)
  verifyLearnerOnBinaryCsvFile("breast-cancer.train.csv",          "Label", 2)
  verifyLearnerOnBinaryCsvFile("random.forest.train.csv",          "#Malignant", 2)
  verifyLearnerOnBinaryCsvFile("transfusion.csv",                  "Donated", 2)

  test("Compare benchmark results file to generated file", TestBase.Extended) {
    // Investigate slightly fluctuating accuracies
    // compareBenchmarkFiles()
  }

  def verifyLearnerOnBinaryCsvFile(fileName: String,
                                   labelColumnName: String,
                                   decimals: Int): Unit = {
    test("Verify LightGBM can be trained and scored on " + fileName, TestBase.Extended) {
      // Increment port index
      portIndex += numPartitions
      val fileLocation = ClassifierTestUtils.classificationTrainFile(fileName).toString
      val dataset: DataFrame =
        session.read.format("com.databricks.spark.csv")
          .option("header", "true").option("inferSchema", "true")
          .option("treatEmptyValuesAsNulls", "false")
          .option("delimiter", if (fileName.endsWith(".csv")) "," else "\t")
          .load(fileLocation)
      val lgbm = new LightGBMClassifier()
      val featuresColumn = lgbm.uid + "_features"
      val featurizer = LightGBMUtils.featurizeData(dataset, labelColumnName, featuresColumn)
      val rawPredCol = "rawPred"
      val model = lgbm.setLabelCol(labelColumnName)
        .setFeaturesCol(featuresColumn)
        .setRawPredictionCol(rawPredCol)
        .setDefaultListenPort(LightGBMClassifier.defaultLocalListenPort + portIndex)
        .setNumLeaves(5)
        .setNumIterations(10)
        .fit(featurizer.transform(dataset).repartition(numPartitions))
      val scoredResult = model.transform(featurizer.transform(dataset)).drop(featuresColumn)
      val eval = new BinaryClassificationEvaluator()
        .setLabelCol(labelColumnName)
        .setRawPredictionCol(rawPredCol)
      val metric = eval.evaluate(scoredResult)
      addAccuracyResult(fileName, "LightGBMClassifier",
        round(metric, decimals))
    }
  }

  def verifyLearnerOnMulticlassCsvFile(fileName: String,
                                       labelColumnName: String,
                                       decimals: Int): Unit = {
    test("Verify classifier can be trained and scored on multiclass " + fileName, TestBase.Extended) {
      // Increment port index
      portIndex += numPartitions
      val fileLocation = ClassifierTestUtils.multiclassClassificationTrainFile(fileName).toString
      val dataset: DataFrame =
        session.read.format("com.databricks.spark.csv")
          .option("header", "true").option("inferSchema", "true")
          .option("treatEmptyValuesAsNulls", "false")
          .option("delimiter", if (fileName.endsWith(".csv")) "," else "\t")
          .load(fileLocation)
      val lgbm = new LightGBMClassifier()
      val featuresColumn = lgbm.uid + "_features"
      val featurizer = LightGBMUtils.featurizeData(dataset, labelColumnName, featuresColumn)
      val predCol = "pred"
      val model = lgbm.setLabelCol(labelColumnName)
        .setFeaturesCol(featuresColumn)
        .setPredictionCol(predCol)
        .setDefaultListenPort(LightGBMClassifier.defaultLocalListenPort + portIndex)
        .setNumLeaves(5)
        .setNumIterations(10)
        .fit(featurizer.transform(dataset).repartition(numPartitions))
      val scoredResult = model.transform(featurizer.transform(dataset)).drop(featuresColumn)
      val eval = new MulticlassClassificationEvaluator()
        .setLabelCol(labelColumnName)
        .setPredictionCol(predCol)
        .setMetricName("accuracy")
      val metric = eval.evaluate(scoredResult)
      addAccuracyResult(fileName, "LightGBMClassifier", round(metric, decimals))
    }
  }

  override def testObjects(): Seq[TestObject[LightGBMClassifier]] = {
    val fileName = "PimaIndian.csv"
    val labelCol = "Diabetes mellitus"
    val featuresCol = "feature"
    val fileLocation = ClassifierTestUtils.classificationTrainFile(fileName).toString
    val dataset: DataFrame =
      session.read.format("com.databricks.spark.csv")
        .option("header", "true").option("inferSchema", "true")
        .option("treatEmptyValuesAsNulls", "false")
        .option("delimiter", if (fileName.endsWith(".csv")) "," else "\t")
        .load(fileLocation)

    val featurizer = LightGBMUtils.featurizeData(dataset, labelCol, featuresCol)
    val train = featurizer.transform(dataset)

    Seq(new TestObject(new LightGBMClassifier().setLabelCol(labelCol).setFeaturesCol(featuresCol).setNumLeaves(5),
      train))
  }

  override def reader: MLReadable[_] = LightGBMClassifier

  override def modelReader: MLReadable[_] = LightGBMClassificationModel
}
