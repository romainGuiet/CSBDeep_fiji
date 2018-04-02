package mpicbg.csbd.tiling.task;

import java.util.List;

import mpicbg.csbd.task.Task;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;

import mpicbg.csbd.tiling.AdvancedTiledView;
import mpicbg.csbd.tiling.Tiling;

public interface OutputTiler extends Task {

	public List< RandomAccessibleInterval< FloatType > > run(
			List< AdvancedTiledView< FloatType > > input,
			Tiling tiling,
			AxisType[] axisTypes );

}
