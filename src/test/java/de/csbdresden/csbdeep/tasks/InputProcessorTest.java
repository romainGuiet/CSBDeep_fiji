
package de.csbdresden.csbdeep.tasks;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Ignore;
import org.junit.Test;

import de.csbdresden.csbdeep.io.DefaultInputProcessor;
import de.csbdresden.csbdeep.io.InputProcessor;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.axis.Axes;
import net.imagej.axis.AxisType;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.real.FloatType;

public class InputProcessorTest {

	@Test
	@Ignore
	public void run() {
		final ImageJ ij = new ImageJ();

		final Dataset input = ij.dataset().create(new FloatType(), new long[] { 10,
			50, 100 }, "", new AxisType[] { Axes.Z, Axes.X, Axes.Y });

		final InputProcessor inputProcessor = new DefaultInputProcessor();

		final List<RandomAccessibleInterval<FloatType>> output = inputProcessor.run(
			input, null);

		assertEquals(1, output.size());
		assertEquals(10, output.get(0).dimension(0));
		assertEquals(50, output.get(0).dimension(1));
		assertEquals(100, output.get(0).dimension(2));
		assertEquals(1, output.get(0).dimension(3));

	}

}
