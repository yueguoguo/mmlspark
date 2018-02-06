// Copyright (C) Microsoft Corporation. All rights reserved.
// Licensed under the MIT License. See LICENSE in project root for information.

package com.microsoft.ml.spark

import com.microsoft.ml.lightgbm.{SWIGTYPE_p_void, lightgbmlib, lightgbmlibConstants}
import org.apache.spark.TaskContext
import org.apache.spark.ml.linalg.{DenseVector, SparseVector}
import org.apache.spark.sql.Row
import org.slf4j.Logger

case class TrainParams(numIterations: Int, learningRate: Double, numLeaves: Int)

private object TrainUtils extends java.io.Serializable {
  private val DefaultBufferLength: Int = 10000

  def translate(labelColumn: String, featuresColumn: String, parallelism: String, log: Logger,
                trainParams: TrainParams, inputRows: Iterator[Row]): Iterator[LightGBMBooster] = {
    if (!inputRows.hasNext)
      List[LightGBMBooster]().toIterator

    val rows = inputRows.toArray
    val numRows = rows.length
    val rowsAsDoubleArray = rows.map(row => (row.get(row.fieldIndex(featuresColumn)) match {
      case dense: DenseVector => dense.toArray
      case sparse: SparseVector => sparse.toDense.toArray
    }, row.getDouble(row.fieldIndex(labelColumn))))

    val datasetPtr = LightGBMUtils.generateDataset(numRows, rowsAsDoubleArray.map(_._1))
    // Validate generated dataset has the correct number of rows and cols
    validateDataset(datasetPtr)

    // Generate the label column and add to dataset
    val labelColArray = lightgbmlib.new_floatArray(numRows)
    rowsAsDoubleArray.zipWithIndex.foreach(ri =>
      lightgbmlib.floatArray_setitem(labelColArray, ri._2, ri._1._2.toFloat))
    val labelAsVoidPtr = lightgbmlib.float_to_voidp_ptr(labelColArray)
    val data32bitType = lightgbmlibConstants.C_API_DTYPE_FLOAT32
    LightGBMUtils.validate(
      lightgbmlib.LGBM_DatasetSetField(datasetPtr, "label", labelAsVoidPtr, numRows, data32bitType), "DatasetSetField")

    // Create the booster
    val boosterOutPtr = lightgbmlib.voidpp_handle()
    val parameters = s"is_pre_partition=True tree_learner=$parallelism boosting_type=gbdt " +
      s"objective=binary metric=binary_logloss,auc num_iterations=${trainParams.numIterations} " +
      s"learning_rate=${trainParams.learningRate} num_leaves=${trainParams.numLeaves}"
    LightGBMUtils.validate(lightgbmlib.LGBM_BoosterCreate(datasetPtr, parameters, boosterOutPtr), "Booster")
    val boosterPtr = lightgbmlib.voidpp_value(boosterOutPtr)
    val isFinishedPtr = lightgbmlib.new_intp()
    var isFinised = 0
    var iters = 0
    while (isFinised == 0 && iters < trainParams.numIterations) {
      val result = lightgbmlib.LGBM_BoosterUpdateOneIter(boosterPtr, isFinishedPtr)
      LightGBMUtils.validate(result, "Booster Update One Iter")
      isFinised = lightgbmlib.intp_value(isFinishedPtr)
      log.info("LightGBM running iteration: " + iters + " with result: " + result + " and is finished: " + isFinised)
      iters = iters + 1
    }
    val bufferLength = DefaultBufferLength
    val bufferLengthPtr = lightgbmlib.new_longp()
    lightgbmlib.longp_assign(bufferLengthPtr, bufferLength)
    val bufferLengthPtrInt64 = lightgbmlib.long_to_int64_t_ptr(bufferLengthPtr)
    val bufferOutLengthPtr = lightgbmlib.new_int64_tp()
    val tempM = lightgbmlib.LGBM_BoosterSaveModelToStringSWIG(boosterPtr, -1, bufferLengthPtrInt64, bufferOutLengthPtr)
    val bufferOutLength = lightgbmlib.longp_value(lightgbmlib.int64_t_to_long_ptr(bufferOutLengthPtr))
    // TODO: Move the reallocation logic inside the SWIG wrapper
    val model =
      if (bufferOutLength > bufferLength) {
        lightgbmlib.LGBM_BoosterSaveModelToStringSWIG(boosterPtr, -1, bufferOutLengthPtr, bufferOutLengthPtr)
      } else tempM
    log.info("Buffer output length: " + bufferOutLength)
    // Finalize network when done
    LightGBMUtils.validate(lightgbmlib.LGBM_NetworkFree(), "Finalize network")
    List[LightGBMBooster](new LightGBMBooster(model)).toIterator
  }

  private def validateDataset(datasetPtr: SWIGTYPE_p_void) = {
    // Validate num rows
    val numDataPtr = lightgbmlib.new_intp()
    LightGBMUtils.validate(lightgbmlib.LGBM_DatasetGetNumData(datasetPtr, numDataPtr), "DatasetGetNumData")
    val numData = lightgbmlib.intp_value(numDataPtr)
    if (numData <= 0) {
      throw new Exception("Unexpected num data: " + numData)
    }

    // Validate num cols
    val numFeaturePtr = lightgbmlib.new_intp()
    LightGBMUtils.validate(lightgbmlib.LGBM_DatasetGetNumFeature(datasetPtr, numFeaturePtr), "DatasetGetNumFeature")
    val numFeature = lightgbmlib.intp_value(numFeaturePtr)
    if (numFeature <= 0) {
      throw new Exception("Unexpected num feature: " + numFeature)
    }
  }

  def trainLightGBM(nodes: String, numNodes: Int, labelColumn: String, featuresColumn: String, parallelism: String,
                    defaultListenPort: Int, log: Logger, trainParams: TrainParams)
                   (inputRows: Iterator[Row]): Iterator[LightGBMBooster] = {
    // Initialize the native library
    LightGBMUtils.initializeNativeLibrary()
    // Initialize the network communication
    val partitionId = TaskContext.getPartitionId()
    val localListenPort = defaultListenPort + partitionId
    log.info("LightGBM worker listening on: " + localListenPort)
    LightGBMUtils.validate(lightgbmlib.LGBM_NetworkInit(nodes, localListenPort, LightGBMClassifier.defaultListenTimeout,
      numNodes), "Network init")
    translate(labelColumn, featuresColumn, parallelism, log, trainParams, inputRows)
  }
}
