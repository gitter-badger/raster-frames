/*
 * Copyright 2017 Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.gt

import geotrellis.raster.mapalgebra.focal.{Square, Sum}
import geotrellis.raster.{BitCellType, BitConstantTile, ByteCells, ByteConstantTile, CellGrid, CellType, DoubleCells, DoubleConstantTile, FloatCells, FloatConstantTile, IntCells, IntConstantTile, ShortCells, ShortConstantTile, Tile, UByteCells, UByteConstantTile, UShortCells, UShortConstantTile}
import geotrellis.vector.{Extent, ProjectedExtent}
import org.apache.spark.sql.catalyst.analysis.{FunctionRegistry, MultiAlias, UnresolvedAttribute}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.catalyst.expressions.{CreateArray, Expression, Inline}
import org.apache.spark.sql.gt.expressions.{ExplodeTileExpression, UDTAsStructExpression}
import org.apache.spark.sql.types.{StructType, UDTRegistration, UserDefinedType}
import org.apache.spark.sql.{Column, Row, SQLContext, TypedColumn}

import scala.reflect.runtime.universe._
import scala.util.Try

/**
 * GT functions adapted for Spark SQL use.
 *
 * @author sfitch
 * @since 4/3/17
 */
package object functions {
  /** Create columns for each field in the structure or UDT. */
  def flatten[T >: Null: TypeTag](col: TypedColumn[_, T]) = {
    Column(Try(asStruct[T](col)).map(col ⇒ projectStruct(col.encoder.schema, col.expr))
      .getOrElse(projectStruct(col.encoder.schema, col.expr)))
  }

  /** Attempts to convert a UDT into a struct based on the underlying deserializer. */
  def asStruct[T >: Null: TypeTag](col: TypedColumn[_, T]) = {
    val converter = UDTAsStructExpression(udtOf[T], col.expr)
    Column(converter).as[Row](RowEncoder(converter.dataType))
  }

  /** Create a row for each pixel in tile. */
  def explodeTile(cols: Column*) = {
    val exploder = ExplodeTileExpression(cols.map(_.expr))
    // Hack to grab the first two non-cell columns
    val metaNames = exploder.elementSchema.fieldNames.take(2)
    val colNames = cols.map(_.expr).map {
      case ua: UnresolvedAttribute ⇒ ua.name
      case o ⇒ o.prettyName
    }

    Column(exploder).as(Seq("column", "row") ++ colNames)
  }

  // -- Private APIs below --
  private[spark] def udtOf[T >: Null: TypeTag]: UserDefinedType[T] =
    UDTRegistration.getUDTFor(typeTag[T].tpe.toString).map(_.newInstance().asInstanceOf[UserDefinedType[T]])
      .getOrElse(throw new IllegalArgumentException(typeTag[T].tpe + " doesn't have a corresponding UDT"))

  private[spark] def flatten[T >: Null : TypeTag](input: Expression) = {
    val converter = UDTAsStructExpression(udtOf[T], input)
    projectStruct(converter.dataType, converter)
  }

  private[spark] def projectStruct(dataType: StructType, input: Expression) =
    MultiAlias(Inline(CreateArray(Seq(input))), dataType.fields.map(_.name))

  private[spark] def register(sqlContext: SQLContext): Unit = {
    sqlContext.udf.register("st_makeConstantTile", makeConstantTile)
    sqlContext.udf.register("st_focalSum", focalSum)
    sqlContext.udf.register("st_makeTiles", makeTiles)
    sqlContext.udf.register("st_gridRows", gridRows)
    sqlContext.udf.register("st_gridCols", gridCols)
  }
  // Expression-oriented functions have a different registration scheme
  FunctionRegistry.builtin.registerFunction("st_explodeTile", ExplodeTileExpression.apply)
  FunctionRegistry.builtin.registerFunction("st_flattenExtent", (exprs: Seq[Expression]) ⇒ flatten[Extent](exprs.head))
  FunctionRegistry.builtin.registerFunction("st_flattenProjectedExtent", (exprs: Seq[Expression]) ⇒ flatten[ProjectedExtent](exprs.head))

  // Constructor for constant tiles
  private[spark] val makeConstantTile: (Number, Int, Int, String) ⇒ Tile = (value, cols, rows, cellTypeName) ⇒ {
    val cellType = CellType.fromString(cellTypeName)
    cellType match {
      case BitCellType => BitConstantTile(if (value.intValue() == 0) false else true, cols, rows)
      case ct: ByteCells => ByteConstantTile(value.byteValue(), cols, rows, ct)
      case ct: UByteCells => UByteConstantTile(value.byteValue(), cols, rows, ct)
      case ct: ShortCells => ShortConstantTile(value.shortValue() , cols, rows, ct)
      case ct: UShortCells =>  UShortConstantTile(value.shortValue() , cols, rows, ct)
      case ct: IntCells =>  IntConstantTile(value.intValue() , cols, rows, ct)
      case ct: FloatCells => FloatConstantTile(value.floatValue() , cols, rows, ct)
      case ct: DoubleCells => DoubleConstantTile(value.doubleValue(), cols, rows, ct)
    }
  }

  private[spark] val makeTiles: (Int) ⇒ Array[Tile] = (count) ⇒
    Array.fill(count)(makeConstantTile(0, 4, 4, "int8raw"))

  private[spark] val gridCols: (CellGrid) ⇒ (Int) = (tile) ⇒ tile.cols
  private[spark] val gridRows: (CellGrid) ⇒ (Int) = (tile) ⇒ tile.rows

  // Perform a focal sum over square area with given half/width extent (value of 1 would be a 3x3 tile)
  private[spark] val focalSum: (Tile, Int) ⇒ Tile = (tile, extent) ⇒ Sum(tile, Square(extent))

}