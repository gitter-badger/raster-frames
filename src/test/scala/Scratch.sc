import geotrellis.raster.{CellSize, RasterExtent}
import geotrellis.vector.Extent
val size = 64
val extent = Extent(431902.5, 4320187.5, 432862.5, 4321147.5)
val re = RasterExtent(extent, size, size)
val gb = re.gridBoundsFor(extent)
val targetCS = CellSize(extent, gb.colMax, gb.rowMax)
assert(targetCS == re.cellSize, s"$targetCS != ${re.cellSize}")
