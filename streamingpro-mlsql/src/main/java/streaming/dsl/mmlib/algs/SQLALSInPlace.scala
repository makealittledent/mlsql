package streaming.dsl.mmlib.algs

import org.apache.spark.ml.classification.RandomForestClassifier
import org.apache.spark.ml.evaluation.RegressionEvaluator
import org.apache.spark.ml.param.{ParamMap, Params}
import org.apache.spark.ml.recommendation.{ALS, ALSModel}
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import org.apache.spark.sql.expressions.UserDefinedFunction
import streaming.dsl.mmlib.SQLAlg
import streaming.dsl.mmlib.algs.param.BaseParams

/**
  * Created by allwefantasy on 24/7/2018.
  */
class SQLALSInPlace(override val uid: String) extends SQLAlg with MllibFunctions with Functions with BaseParams {

  def this() = this(BaseParams.randomUID())

  override def train(df: DataFrame, path: String, params: Map[String, String]): DataFrame = {

    val keepVersion = params.getOrElse("keepVersion", "true").toBoolean
    setKeepVersion(keepVersion)

    val evaluateTable = params.get("evaluateTable")
    setEvaluateTable(evaluateTable.getOrElse("None"))


    SQLPythonFunc.incrementVersion(path, keepVersion)
    val spark = df.sparkSession

    trainModelsWithMultiParamGroup[ALSModel](df, path, params, () => {
      new ALS()
    }, (model, fitParam) => {
      evaluateTable match {
        case Some(etable) =>
          model.asInstanceOf[ALSModel].setColdStartStrategy(params.getOrElse("coldStartStrategy", "nan"))
          val evaluateTableDF = spark.table(etable)
          val predictions = model.asInstanceOf[ALSModel].transform(evaluateTableDF)
          val evaluator = new RegressionEvaluator()
            .setMetricName("rmse")
            .setLabelCol(fitParam.getOrElse("ratingCol", "rating"))
            .setPredictionCol("prediction")

          val rmse = evaluator.evaluate(predictions)
          //分值越低越好
          List(MetricValue("rmse", -rmse))
        case None => List()
      }
    }
    )

    val (bestModelPath, baseModelPath, metaPath) = mllibModelAndMetaPath(path, params, spark)

    val model = ALSModel.load(bestModelPath(0))

    if (params.contains("userRec")) {
      val userRecs = model.recommendForAllUsers(params.getOrElse("userRec", "10").toInt)
      userRecs.write.mode(SaveMode.Overwrite).parquet(path + "/data/userRec")
    }

    if (params.contains("itemRec")) {
      val itemRecs = model.recommendForAllItems(params.getOrElse("itemRec", "10").toInt)
      itemRecs.write.mode(SaveMode.Overwrite).parquet(path + "/data/itemRec")
    }

    saveMllibTrainAndSystemParams(spark, params, metaPath)
    formatOutput(getModelMetaData(spark, path))
  }

  override def explainParams(sparkSession: SparkSession): DataFrame = {
    _explainParams(sparkSession, () => {
      new ALS()
    })
  }

  override def load(sparkSession: SparkSession, _path: String, params: Map[String, String]): Any = {
    throw new RuntimeException("register is not supported in ALSInPlace")
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {
    throw new RuntimeException("register is not supported in ALSInPlace")
  }

}
