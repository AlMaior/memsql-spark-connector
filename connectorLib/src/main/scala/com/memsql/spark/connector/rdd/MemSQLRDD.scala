package com.memsql.spark.connector.rdd

import java.sql.{Connection, ResultSet, Statement, PreparedStatement, Types}
import scala.reflect.ClassTag

import org.apache.spark.{Logging, Partition, SparkContext, TaskContext}
import org.apache.spark.rdd.RDD
import com.memsql.spark.connector.util.{MemSQLDriverManager, NextIterator}

private class MemSQLRDDPartition(override val index: Int, val host: String, val port: Int) extends Partition

/**
  * An [[org.apache.spark.rdd.RDD]] that can read data from a MemSQL database based on a SQL query.
  *
  * If the given query supports it, this RDD will read data directly from the
  * MemSQL cluster's leaf nodes rather than from the master aggregator, which
  * typically results in much faster reads.  However, if the given query does
  * not support this (e.g. queries involving joins or GROUP BY operations), the
  * results will be returned in a single partition.
  *
  * @param dbHost The host to connect to for the master aggregator of the MemSQL
  *   cluster.
  * @param dbPort The port to connect to for the master aggregator of the MemSQL
  *   cluster.
  * @param user The username to use when connecting to the databases in the
  *   MemSQL cluster.  All the nodes in the cluster should use the same user.
  * @param password The password to use when connecting to the databases in the
  *   MemSQL cluster.  All the nodes in the cluster should use the same password.
  * @param dbName The name of the database we're working in.
  * @param sql The text of the query. Can be a prepared statement template,
 *    in which case parameters from sqlParams are substituted.
 *  @param sqlParams The parameters of the query if sql is a template.
  * @param mapRow A function from a ResultSet to a single row of the desired
  *   result type(s).  This should only call getInt, getString, etc; the RDD
  *   takes care of calling next.  The default maps a ResultSet to an array of
  *   Object.
  */
case class MemSQLRDD[T: ClassTag](
  @transient sc: SparkContext,
  dbHost: String,
  dbPort: Int,
  user: String,
  password: String,
  dbName: String,
  sql: String,
  sqlParams: Seq[Object] = Seq[Object](),
  mapRow: (ResultSet) => T = MemSQLRDD.resultSetToObjectArray _)
    extends RDD[T](sc, Nil) with Logging {

  var perPartitionSqlTemplate = ""
  var usePerPartitionSql = false

  override def getPartitions: Array[Partition] = {
    var conn: Connection = null
    var versionStmt: Statement = null
    var explainStmt: PreparedStatement = null
    try {
      conn = MemSQLRDD.getConnection(dbHost, dbPort, user, password, dbName)
      versionStmt = conn.createStatement
      val versionRs = versionStmt.executeQuery("SHOW VARIABLES LIKE 'memsql_version'")
      val versions = MemSQLRDD.resultSetToIterator(versionRs).map(r => r.getString("Value")).toArray
      val version = versions(0).split('.')(0).toInt
      var explainQuery = ""

      // In MemSQL v4.0 the EXPLAIN command no longer returns the query, so
      // we run a version check.
      if (version > 3) {
          explainQuery = "EXPLAIN EXTENDED "
      } else {
          explainQuery = "EXPLAIN "
      }

      explainStmt = conn.prepareStatement(explainQuery + sql)
      fillParams(explainStmt)

      val explainRs = explainStmt.executeQuery
      // TODO: this won't work with MarkoExplain
      // TODO: this could be optimized work for distributed joins, but thats not the primary usecase (especially since joins aren't pushed down)
      usePerPartitionSql = (0 until explainRs.getMetaData.getColumnCount).exists((i:Int) => explainRs.getMetaData.getColumnName(i + 1).equals("Query"))
      if (usePerPartitionSql) {
        val extraAndQueries = MemSQLRDD.resultSetToIterator(explainRs)
          .map(r => (r.getString("Extra"), r.getString("Query"), r.getString("select_type")))
          .toArray
        if (extraAndQueries(0)._1 == "memsql: Simple Iterator -> Network"
            && extraAndQueries.length > 1
            && !extraAndQueries.exists(_._3 == "DRESULT")) {
          usePerPartitionSql = true
          perPartitionSqlTemplate = extraAndQueries(1)._2
        } else {
          usePerPartitionSql = false
        }
      }

      if (!usePerPartitionSql){
        Array[Partition](new MemSQLRDDPartition(0, dbHost, dbPort))
      } else {

        val partitionsStmt = conn.createStatement
        val partitionRs = partitionsStmt.executeQuery("SHOW PARTITIONS")

        def createPartition(row: ResultSet): MemSQLRDDPartition = {
          new MemSQLRDDPartition(row.getInt("Ordinal"), row.getString("Host"), row.getInt("Port"))
        }

        MemSQLRDD.resultSetToIterator(partitionRs)
          .filter(r => r.getString("Role") == "Master")
          .map(createPartition)
          .toArray
      }
    } finally {
      if (null != versionStmt && ! versionStmt.isClosed()) {
        versionStmt.close()
      }
      if (null != explainStmt && ! explainStmt.isClosed()) {
        explainStmt.close()
      }
      if (null != conn && ! conn.isClosed()) {
        conn.close()
      }
    }
  }

  override def compute(thePart: Partition, context: TaskContext): Iterator[T] = new NextIterator[T] {
    context.addTaskCompletionListener(context => closeIfNeeded())
    val part = thePart.asInstanceOf[MemSQLRDDPartition]
    var partitionDb = dbName
    if (usePerPartitionSql) {
      partitionDb = dbName + '_' + part.index
    }
    val conn = MemSQLRDD.getConnection(part.host, part.port, user, password, partitionDb)
    val stmtSql = getPerPartitionSql(part.index)
    val stmt = conn.prepareStatement(stmtSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)

    if (!usePerPartitionSql) {
      fillParams(stmt)
    }

    val rs = stmt.executeQuery

    override def getNext: T = {
      if (rs.next()) {
        mapRow(rs)
      } else {
        finished = true
        null.asInstanceOf[T]
      }
    }

    override def close() {
      try {
        if (null != stmt && ! stmt.isClosed()) {
          stmt.close()
        }
      } catch {
        case e: Exception => logWarning("Exception closing statement", e)
      }
      try {
        if (null != conn && ! conn.isClosed()) {
          conn.close()
        }
        logInfo("closed connection")
      } catch {
        case e: Exception => logWarning("Exception closing connection", e)
      }
    }
  }

  override def getPreferredLocations(split: Partition): Seq[String] = {
    val memSqlSplit = split.asInstanceOf[MemSQLRDDPartition]
    Seq(memSqlSplit.host)
  }

  private def getPerPartitionSql(idx: Int): String = {
    // The EXPLAIN query that we run in getPartitions gives us the SQL query
    // that will be run against MemSQL partition number 0; we want to run this
    // query against an arbitrary partition, so we replace the database name
    // in this partition (which is in the form {dbName}_0) with {dbName}_{i}
    // where i is our partition index.
    if (usePerPartitionSql) {
      val dbNameRegex = (dbName + "_0").r
      dbNameRegex.replaceAllIn(perPartitionSqlTemplate, dbName + "_" + idx)
    } else {
      sql
    }
  }

  def fillParams(stmt: PreparedStatement): Unit = {
    sqlParams.zipWithIndex.foreach {
      case (el, i) => {
        if (el == null) {
          // Arbitrary type. The type doesn't matter
          // for null values in MemSQL.
          stmt.setNull(i + 1, Types.CHAR)
        } else {
          stmt.setObject(i + 1, el)
        }
      }
    }
  }
}

object MemSQLRDD {
  def resultSetToObjectArray(rs: ResultSet): Array[Object] = {
    Array.tabulate[Object](rs.getMetaData.getColumnCount)(i => rs.getObject(i + 1))
  }

  def getConnection(
    host: String,
    port: Int,
    user: String,
    password: String,
    dbName: String = "information_schema"): Connection = {
    // Prepare the MySQL JDBC driver.
    Class.forName("com.mysql.jdbc.Driver").newInstance()
    val dbAddress = "jdbc:mysql://" + host + ":" + port + "/" + dbName
    MemSQLDriverManager.getConnection(dbAddress, user, password)
  }

  def resultSetToIterator(rs: ResultSet): Iterator[ResultSet] = new Iterator[ResultSet] {
    def hasNext = rs.next()
    def next() = rs
  }
}
