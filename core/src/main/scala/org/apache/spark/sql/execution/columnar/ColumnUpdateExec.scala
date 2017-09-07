/*
 * Copyright (c) 2017 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.sql.execution.columnar

import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode, ExpressionCanonicalizer}
import org.apache.spark.sql.catalyst.expressions.{Attribute, BindReferences, Expression}
import org.apache.spark.sql.collection.Utils
import org.apache.spark.sql.execution.SparkPlan
import org.apache.spark.sql.execution.columnar.encoding.ColumnDeltaEncoder
import org.apache.spark.sql.execution.columnar.impl.ColumnDelta
import org.apache.spark.sql.execution.metric.{SQLMetric, SQLMetrics}
import org.apache.spark.sql.execution.row.RowExec
import org.apache.spark.sql.sources.{ConnectionProperties, DestroyRelation, JdbcExtendedUtils}
import org.apache.spark.sql.store.StoreUtils
import org.apache.spark.sql.types.StructType

/**
 * Generated code plan for updates into a column table.
 * This extends [[RowExec]] to generate the combined code for row buffer updates.
 */
case class ColumnUpdateExec(child: SparkPlan, columnTable: String,
    partitionColumns: Seq[String], partitionExpressions: Seq[Expression], numBuckets: Int,
    isPartitioned: Boolean, tableSchema: StructType, externalStore: ExternalStore,
    relation: Option[DestroyRelation], updateColumns: Seq[Attribute],
    updateExpressions: Seq[Expression], keyColumns: Seq[Attribute],
    connProps: ConnectionProperties, onExecutor: Boolean) extends ColumnExec {

  assert(updateColumns.length == updateExpressions.length)

  private lazy val schemaAttributes = tableSchema.toAttributes
  /**
   * The indexes below are the final ones that go into ColumnFormatKey(columnIndex).
   * For deltas the convention is to use negative values beyond those available for
   * each hierarchy depth. So starting at DELTA_STATROW index of -3, the first column
   * will use indexes -4, -5, -6 for hierarchy depth 3, second column will use
   * indexes -7, -8, -9 and so on. The values below are initialized to the first value
   * in the series while merges with higher hierarchy depth will be done via a
   * CacheListener on the store.
   */
  private val updateIndexes = updateColumns.map(a => ColumnDelta.deltaColumnIndex(
    Utils.fieldIndex(schemaAttributes, a.name,
      sqlContext.conf.caseSensitiveAnalysis), hierarchyDepth = 0)).toArray

  override protected def opType: String = "Update"

  override def nodeName: String = "ColumnUpdate"

  override lazy val metrics: Map[String, SQLMetric] = {
    if (onExecutor) Map.empty
    else Map(
      "numUpdateRows" -> SQLMetrics.createMetric(sparkContext,
        "number of updates to row buffer"),
      "numUpdateColumnBatchRows" -> SQLMetrics.createMetric(sparkContext,
        "number of updates to column batches"))
  }

  override def simpleString: String =
    s"${super.simpleString} update: columns=$updateColumns expressions=$updateExpressions"

  @transient private var batchOrdinal: String = _
  @transient private var finishUpdate: String = _
  @transient private var updateMetric: String = _
  @transient protected var txId: String = _

  override protected def doProduce(ctx: CodegenContext): String = {

    val sql = new StringBuilder
    sql.append("UPDATE ").append(resolvedName).append(" SET ")
    JdbcExtendedUtils.fillColumnsClause(sql, updateColumns.map(_.name),
      escapeQuotes = true, separator = ", ")
    sql.append(" WHERE ")
    // only the ordinalId is required apart from partitioning columns
    if (keyColumns.length > 3) {
      JdbcExtendedUtils.fillColumnsClause(sql, keyColumns.dropRight(3).map(_.name),
        escapeQuotes = true)
      sql.append(" AND ")
    }
    sql.append(StoreUtils.ROWID_COLUMN_NAME).append("=?")

    super.doProduce(ctx, sql.toString(), () =>
      s"""
         |if ($batchOrdinal > 0) {
         |  $finishUpdate(null, -1); // force a finish
         |}
         |$taskListener.setSuccess();
      """.stripMargin)
  }

  override def doConsume(ctx: CodegenContext, input: Seq[ExprCode],
      row: ExprCode): String = {
    // use an array of delta encoders and cursors
    val deltaEncoders = ctx.freshName("deltaEncoders")
    val cursors = ctx.freshName("cursors")
    val index = ctx.freshName("index")
    batchOrdinal = ctx.freshName("batchOrdinal")
    val lastColumnBatchId = ctx.freshName("lastColumnBatchId")
    val lastBucketId = ctx.freshName("lastBucketId")
    finishUpdate = ctx.freshName("finishUpdate")
    val initializeEncoders = ctx.freshName("initializeEncoders")

    val updateSchema = StructType.fromAttributes(updateColumns)
    val schemaTerm = ctx.addReferenceObj("updateSchema", updateSchema,
      classOf[StructType].getName)
    val deltaIndexes = ctx.addReferenceObj("deltaIndexes", updateIndexes, "int[]")
    val externalStoreTerm = ctx.addReferenceObj("externalStore", externalStore)
    val tableName = ctx.addReferenceObj("columnTable", columnTable, "java.lang.String")
    updateMetric = if (onExecutor) null else metricTerm(ctx, "numUpdateColumnBatchRows")

    val numColumns = updateColumns.length
    val deltaEncoderClass = classOf[ColumnDeltaEncoder].getName
    val columnBatchClass = classOf[ColumnBatch].getName

    ctx.addMutableState(s"$deltaEncoderClass[]", deltaEncoders, "")
    ctx.addMutableState("long[]", cursors,
      s"""
         |$deltaEncoders = new $deltaEncoderClass[$numColumns];
         |$cursors = new long[$numColumns];
         |$initializeEncoders();
      """.stripMargin)
    ctx.addMutableState("int", batchOrdinal, "")
    ctx.addMutableState("UTF8String", lastColumnBatchId, "")
    ctx.addMutableState("int", lastBucketId, "")

    // last three columns in keyColumns should be internal ones
    val keyCols = keyColumns.takeRight(3)
    assert(keyCols.head.name.equalsIgnoreCase(ColumnDelta.mutableKeyNames.head))
    assert(keyCols(1).name.equalsIgnoreCase(ColumnDelta.mutableKeyNames(1)))
    assert(keyCols(2).name.equalsIgnoreCase(ColumnDelta.mutableKeyNames(2)))

    // bind the update expressions
    ctx.INPUT_ROW = null
    ctx.currentVars = input
    val allExpressions = updateExpressions ++ keyColumns
    val updateInput = ctx.generateExpressions(allExpressions.map(
      u => ExpressionCanonicalizer.execute(BindReferences.bindReference(
        u, child.output))), doSubexpressionElimination = true)
    ctx.currentVars = null

    val keyVars = updateInput.takeRight(3)
    val ordinalIdVar = keyVars.head.value
    val batchIdVar = keyVars(1).value
    val bucketVar = keyVars(2).value

    val updateVarsCode = evaluateVariables(updateInput)
    // row buffer needs to select the rowId and partitioning columns so drop last two
    val rowConsume = super.doConsume(ctx, updateInput.dropRight(2),
      StructType(getUpdateSchema(allExpressions.dropRight(2))))

    ctx.addNewFunction(initializeEncoders,
      s"""
         |private void $initializeEncoders() {
         |  for (int $index = 0; $index < $numColumns; $index++) {
         |    $deltaEncoders[$index] = new $deltaEncoderClass(0);
         |    $cursors[$index] = $deltaEncoders[$index].initialize($schemaTerm.fields()[$index],
         |        ${classOf[ColumnDelta].getName}.INIT_SIZE(), true);
         |  }
         |}
      """.stripMargin)
    // Creating separate encoder write functions instead of inlining for wide-schemas
    // in updates (especially with support for putInto being added). Performance should
    // be about the same since JVM inlines where it determines will help performance.
    val callEncoders = updateColumns.zipWithIndex.map { case (col, i) =>
      val function = ctx.freshName("encoderFunction")
      val ordinal = ctx.freshName("ordinal")
      val isNull = ctx.freshName("isNull")
      val field = ctx.freshName("field")
      val dataType = col.dataType
      val encoderTerm = s"$deltaEncoders[$i]"
      val cursorTerm = s"$cursors[$i]"
      val ev = updateInput(i)
      ctx.addNewFunction(function,
        s"""
           |private void $function(int $ordinal, int $ordinalIdVar,
           |    boolean $isNull, ${ctx.javaType(dataType)} $field) {
           |  $encoderTerm.setUpdatePosition($ordinalIdVar);
           |  ${ColumnWriter.genCodeColumnWrite(ctx, dataType, col.nullable, encoderTerm,
                cursorTerm, ev.copy(isNull = isNull, value = field), ordinal)}
           |}
        """.stripMargin)
      // code for invoking the function
      s"$function($batchOrdinal, (int)$ordinalIdVar, ${ev.isNull}, ${ev.value});"
    }.mkString("\n")
    ctx.addNewFunction(finishUpdate,
      s"""
         |private void $finishUpdate(UTF8String batchId, int bucketId) {
         |  if (batchId == null || !batchId.equals($lastColumnBatchId)) {
         |    if ($lastColumnBatchId == null) {
         |      // first call
         |      $lastColumnBatchId = batchId;
         |      $lastBucketId = bucketId;
         |      return;
         |    }
         |    // finish previous encoders, put into table and re-initialize
         |    final java.nio.ByteBuffer[] buffers = new java.nio.ByteBuffer[$numColumns];
         |    for (int $index = 0; $index < $numColumns; $index++) {
         |      buffers[$index] = $deltaEncoders[$index].finish($cursors[$index]);
         |    }
         |    // TODO: SW: delta stats row (can have full limits for those columns)
         |    // for now put dummy bytes in delta stats row
         |    final $columnBatchClass columnBatch = $columnBatchClass.apply(
         |        $batchOrdinal, buffers, new byte[] { 0, 0, 0, 0 }, $deltaIndexes);
         |    // maxDeltaRows is -1 so that insert into row buffer is never considered
         |    $externalStoreTerm.storeColumnBatch($tableName, columnBatch,
         |        $lastBucketId, $lastColumnBatchId, -1, new scala.Some($connTerm));
         |    $result += $batchOrdinal;
         |    ${if (updateMetric eq null) "" else s"$updateMetric.${metricAdd(batchOrdinal)};"}
         |    $initializeEncoders();
         |    $lastColumnBatchId = batchId;
         |    $lastBucketId = bucketId;
         |    $batchOrdinal = 0;
         |  }
         |}
      """.stripMargin)

    s"""
       |$updateVarsCode
       |if ($batchIdVar != null) {
       |  // finish and apply update if the next column batch ID is seen
       |  if ($batchIdVar != $lastColumnBatchId) {
       |    $finishUpdate($batchIdVar, $bucketVar);
       |  }
       |  // write to the encoders
       |  $callEncoders
       |  $batchOrdinal++;
       |} else {
       |  $rowConsume
       |}
    """.stripMargin
  }
}