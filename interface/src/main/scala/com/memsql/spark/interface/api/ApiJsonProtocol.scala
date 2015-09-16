package com.memsql.spark.interface.api

import com.memsql.spark.etl.api.configs._
import com.memsql.spark.etl.utils.JsonEnumProtocol
import spray.json._

object ApiJsonProtocol extends JsonEnumProtocol {
  implicit val extractPhaseKindFormat = jsonEnum(ExtractPhaseKind)
  implicit val transformPhaseKindFormat = jsonEnum(TransformPhaseKind)
  implicit val loadPhaseKindFormat = jsonEnum(LoadPhaseKind)

  implicit def phaseFormat[T: JsonFormat]: RootJsonFormat[Phase[T]] = jsonFormat2(Phase.apply[T])

  implicit val pipelineConfigFormat = jsonFormat5(PipelineConfig)

  implicit val pipelineBatchTypeFormat = jsonEnum(PipelineBatchType)
  implicit val phaseMetricRecordFormat = jsonFormat7(PhaseMetricRecord)
  implicit val taskErrorRecordFormat = jsonFormat5(TaskErrorRecord)
  implicit val pipelineMetricRecordFormat = jsonFormat9(PipelineMetricRecord)

  implicit val pipelineStateFormat = jsonEnum(PipelineState)

  val basePipelineFormat = jsonFormat(Pipeline.apply, "pipeline_id", "state", "batch_interval", "config", "last_updated", "error")

  implicit object pipelineFormat extends RootJsonFormat[Pipeline] {
    def write(p: Pipeline): JsValue =
      JsObject(
        basePipelineFormat.write(p).asJsObject.fields + ("trace_batch_count" -> p.traceBatchCount.toJson)
      )

    // Note: This method doesn't support reading in traceBatchCount
    def read(value: JsValue): Pipeline = basePipelineFormat.read(value)
  }
}
