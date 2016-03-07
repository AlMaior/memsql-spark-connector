package com.memsql.spark.phases

import com.memsql.spark.etl.api.{Extractor, PhaseConfig, UserExtractConfig}
import com.memsql.spark.etl.utils.PhaseLogger
import org.apache.spark.sql.{DataFrame, SQLContext}
import org.apache.spark.streaming.StreamingContext


case class MySQLExtractTaskConfig(table_name: String)

case class MySQLExtractConfig(host: String,
                              port: Int,
                              user: String,
                              password: String,
                              db_name: String,
                              task_config: MySQLExtractTaskConfig) extends PhaseConfig

class MySQLExtractor extends Extractor {
  override def next(ssc: StreamingContext,
                    time: Long,
                    sqlContext: SQLContext,
                    config: PhaseConfig,
                    batchInterval: Long,
                    logger: PhaseLogger): Option[DataFrame] = {
    val sc = ssc.sparkContext
    val mysqlConfig = config.asInstanceOf[MySQLExtractConfig]

    val host = mysqlConfig.host
    val port = mysqlConfig.port
    val db_name = mysqlConfig.db_name
    val table_name = mysqlConfig.task_config.table_name

    val url = s"jdbc:mysql://${host}:${port}"
    var df = sqlContext.read.format("jdbc")
      .option("url", url)
      .option("user", mysqlConfig.user)
      .option("password", mysqlConfig.password)
      .option("dbtable", s"${db_name}.${table_name}")
      .load()

    Some(df)
  }
}
