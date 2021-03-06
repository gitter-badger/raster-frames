/*
 * This software is licensed under the Apache 2 license, quoted below.
 *
 * Copyright (c) 2017. Astraea, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     [http://www.apache.org/licenses/LICENSE-2.0]
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package astraea.spark.rasterframes

import java.time.ZonedDateTime

import geotrellis.proj4.LatLng
import geotrellis.raster._
import geotrellis.raster.io.geotiff.{GeoTiff, SinglebandGeoTiff}
import geotrellis.spark.testkit.TileLayerRDDBuilders
import geotrellis.spark.tiling.{CRSWorldExtent, LayoutDefinition}
import geotrellis.spark._
import geotrellis.vector.{Extent, ProjectedExtent}
import org.apache.commons.io.IOUtils
import org.apache.spark.SparkContext
import org.apache.spark.sql.SparkSession

import scala.reflect.ClassTag
import scala.util.Random

/**
 * Pre-configured data constructs for testing.
 *
 * @author sfitch 
 * @since 4/3/17
 */
trait TestData {
  val instant = ZonedDateTime.now()
  val extent = Extent(1, 2, 3, 4)
  val sk = SpatialKey(37, 41)
  val stk = SpaceTimeKey(sk, instant)
  val pe = ProjectedExtent(extent, LatLng)
  val tpe = TemporalProjectedExtent(pe, instant)
  val tlm = TileLayerMetadata(
    CellType.fromName("uint8"),
    LayoutDefinition(
      extent,
      TileLayout(
        4, 4, 4, 4
      )
    ),
    extent, LatLng, KeyBounds(stk, stk)
  )

  def squareIncrementingTile(size: Int): Tile = ByteArrayTile((1 to (size * size)).map(_.toByte).toArray, size, size)

  val byteArrayTile: Tile = squareIncrementingTile(3)
  val bitConstantTile = BitConstantTile(1, 2, 2)
  val byteConstantTile = ByteConstantTile(7, 3, 3)

  val multibandTile = MultibandTile(byteArrayTile, byteConstantTile)

  val allTileTypes: Seq[Tile] = {
    val rows = 3
    val cols = 3
    val range = 1 to rows * cols
    def rangeArray[T: ClassTag](conv: (Int ⇒ T)): Array[T] = range.map(conv).toArray
    Seq(
      BitArrayTile(Array[Byte](0,1,2,3,4,5,6,7,8), 3*8, 3),
      ByteArrayTile(rangeArray(_.toByte), rows, cols),
      DoubleArrayTile(rangeArray(_.toDouble), rows, cols),
      FloatArrayTile(rangeArray(_.toFloat), rows, cols),
      IntArrayTile(rangeArray(identity), rows, cols),
      ShortArrayTile(rangeArray(_.toShort), rows, cols),
      UByteArrayTile(rangeArray(_.toByte), rows, cols),
      UShortArrayTile(rangeArray(_.toShort), rows, cols)
    )
  }

  def sampleGeoTiff = SinglebandGeoTiff(IOUtils.toByteArray(getClass.getResourceAsStream("/L8-B8-Robinson-IL.tiff")))

  def sampleTileLayerRDD(implicit spark: SparkSession): TileLayerRDD[SpatialKey] = {

    val raster = sampleGeoTiff.projectedRaster.reproject(LatLng)

    val layout = LayoutDefinition(LatLng.worldExtent, TileLayout(36, 18, 128, 128))

    val kb = KeyBounds(SpatialKey(0, 0), SpatialKey(layout.layoutCols, layout.layoutRows))

    val tlm = TileLayerMetadata(raster.tile.cellType, layout, layout.extent, LatLng, kb)

    val rdd = spark.sparkContext.makeRDD(Seq((raster.projectedExtent, raster.tile)))

    ContextRDD(rdd.tileToLayout(tlm), tlm)
  }
}

object TestData extends TestData {

  /** Construct a tile of given size and cell type populated with random values. */
  def randomTile(cols: Int, rows: Int, cellTypeName: String): Tile = {
    val cellType = CellType.fromName(cellTypeName)

    val tile = ArrayTile.alloc(cellType, cols, rows)
    if(cellType.isFloatingPoint) {
      tile.mapDouble(_ ⇒ Random.nextGaussian())
    }
    else {
      tile.map(_ ⇒ (Random.nextGaussian() * 256).toInt)
    }
  }

  /** Create a series of random tiles. */
  val makeTiles: (Int) ⇒ Array[Tile] = (count) ⇒
    Array.fill(count)(randomTile(4, 4, "int8raw"))

  def randomTileLayerRDD(
    rasterCols: Int, rasterRows: Int,
    layoutCols: Int, layoutRows: Int)(implicit sc: SparkContext): TileLayerRDD[SpatialKey] = {
    val tile = randomTile(rasterCols, rasterRows, "uint8")
    TileLayerRDDBuilders.createTileLayerRDD(tile, layoutCols, layoutRows, LatLng)._2
  }
}
