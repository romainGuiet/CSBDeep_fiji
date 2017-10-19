package mpicbg.csbd.commands;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import net.imagej.axis.AxisType;
import net.imglib2.Cursor;
import net.imglib2.FinalInterval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;

import org.scijava.thread.ThreadService;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Tensor;
import org.tensorflow.framework.SignatureDef;

import mpicbg.csbd.imglib2.ArrangedView;
import mpicbg.csbd.imglib2.CombinedView;
import mpicbg.csbd.imglib2.TiledView;
import mpicbg.csbd.tensorflow.DatasetConverter;
import mpicbg.csbd.tensorflow.DatasetTensorBridge;
import mpicbg.csbd.tensorflow.TensorFlowRunner;

public class TiledPredictionUtil {

	public static < T extends RealType< T > > List< RandomAccessibleInterval< FloatType > >
			tiledPrediction(
					final RandomAccessibleInterval< T > input,
					final int nTiles,
					final int blockMultiple,
					final int overlap,
					final DatasetConverter< T > datasetConverter,
					final DatasetTensorBridge bridge,
					final SavedModelBundle model,
					final SignatureDef signature,
					final String inputNodeName,
					final String outputNodeName,
					final ThreadService threadService ) { // TODO output type

		int largestDim = getLargestDim( input, bridge );
		long largestSize = input.dimension( largestDim );

		// Calculate the blocksize to use
		double blockwidthIdeal = largestSize / ( double ) nTiles;
		long blockwidth = ( long ) ( Math.ceil( blockwidthIdeal / blockMultiple ) * blockMultiple );

		// Expand the image to fit the blocksize
		RandomAccessibleInterval< T > im =
				expandDimToSize( input, largestDim, blockwidth * nTiles );

		// Expand other dimensions to fit blockMultiple
		for ( int i = 0; i < im.numDimensions(); i++ ) {
			if ( bridge.getDimTypeByDatasetDim( i ).isXY() ) {
				im = i == largestDim ? im : expandDimToSize(
						im,
						i,
						( long ) Math.ceil(
								im.dimension( i ) / ( double ) blockMultiple ) * blockMultiple );
			}
		}
		printDim( "After expand", im );

		// Set the tile size
		long[] tileSize = Intervals.dimensionsAsLongArray( im );
		tileSize[ largestDim ] = blockwidth;

		// Put the padding per dimension in a array
		long[] padding = new long[ im.numDimensions() ];
		padding[ largestDim ] = overlap;

		// Create the tiled view
		TiledView< T > tiledView = new TiledView<>( im, tileSize, padding );
		Cursor< RandomAccessibleInterval< T > > cursor = Views.iterable( tiledView ).cursor();

		// Set padding to negative to remove it later
		long[] negPadding = padding.clone();
		negPadding[ largestDim ] = -padding[ largestDim ];

		//get mapping for input tensor (index is input image dimension index, value is tensor index)
		int[] mappingIn = new int[ bridge.getAbstractInputTensorShape().getDimCount() ];
		//get mapping for input tensor (index is input image dimension index, value is tensor index)
		int[] mappingOut = new int[ bridge.getAbstractOutputTensorShape().getDimCount() ];
		calculateMapping( mappingIn, mappingOut, bridge );

		boolean multithreading = false;

		// Loop over the tiles and execute the prediction
		List< RandomAccessibleInterval< FloatType > > results = new ArrayList<>();
		List< Future< RandomAccessibleInterval< FloatType > > > futures = new ArrayList<>();
		while ( cursor.hasNext() ) {
			RandomAccessibleInterval< T > tile = cursor.next();
			//uiService.show(tile);
			if ( multithreading ) {
				futures.add(
						threadService.run( new Callable< RandomAccessibleInterval< FloatType > >() {

							@Override
							public RandomAccessibleInterval< FloatType > call() {
								return executeGraphWithPadding(
										tile,
										datasetConverter,
										mappingIn,
										mappingOut,
										model,
										signature,
										inputNodeName,
										outputNodeName );
							}
						} ) );
			} else {
				try {
					threadService.invoke( new Runnable() {

						@Override
						public void run() {
							RandomAccessibleInterval< FloatType > tileExecuted =
									executeGraphWithPadding(
											tile,
											datasetConverter,
											mappingIn,
											mappingOut,
											model,
											signature,
											inputNodeName,
											outputNodeName );
							if ( tileExecuted != null ) {

								long[] negPaddingPlus = new long[ tileExecuted.numDimensions() ];
								for ( int i = 0; i < negPadding.length; i++ ) {
									negPaddingPlus[ i ] = negPadding[ i ];
								}
								tileExecuted =
										Views.zeroMin(
												Views.expandZero( tileExecuted, negPaddingPlus ) );

//								//uiService.show(tileExecuted);
								results.add( tileExecuted );
							}
						}

					} );
				} catch ( InvocationTargetException | InterruptedException exc ) {
					// TODO Auto-generated catch block
					exc.printStackTrace();
					return new ArrayList<>();
				}
			}
		}

		for ( Future< RandomAccessibleInterval< FloatType > > future : futures ) {
			RandomAccessibleInterval< FloatType > tileExecuted = null;
			try {
				tileExecuted = future.get();
			} catch ( InterruptedException | ExecutionException exc ) {
				exc.printStackTrace();
				for ( Future< RandomAccessibleInterval< FloatType > > otherfuture : futures ) {
					if ( !otherfuture.isDone() ) {
						otherfuture.cancel( true );
					}
				}
				return new ArrayList<>();
			}
			tileExecuted = Views.zeroMin( Views.expandZero( tileExecuted, negPadding ) );
//			uiService.show(tileExecuted);
			results.add( tileExecuted );
		}

		if ( results.size() > 0 ) {
			// Arrange and combine the tiles again
			long[] grid = new long[ results.get( 0 ).numDimensions() ];
			for ( int i = 0; i < grid.length; i++ ) {
				grid[ i ] = i == largestDim ? nTiles : 1;
			}
			RandomAccessibleInterval< FloatType > result =
					new CombinedView<>( new ArrangedView<>( results, grid ) );

			RandomAccessibleInterval< FloatType > fittedResult =
					expandDimToSize( result, largestDim, largestSize );

//			ImageJ ij = new ImageJ();
//			ij.ui().show( "_result", result );
//			ij.ui().show( "_expandedresult", expandedresult );

			int lastdim = fittedResult.numDimensions() - 1;

			if ( fittedResult.dimension( lastdim ) == 2 ) {

			return splitChannels( fittedResult, lastdim ); }

			return new ArrayList<>();
		}

		return new ArrayList<>();
	}

	private static < T extends RealType< T > > int
			getLargestDim( RandomAccessibleInterval< T > input, DatasetTensorBridge bridge ) {
		// Get the largest dimension and its size
		int largestDim = 0;
		long largestSize = 0;
		for ( int d = 0; d < input.numDimensions(); d++ ) {
			long dimSize = input.dimension( d );
			if ( bridge.getDimTypeByDatasetDim( d ).isXY() && dimSize > largestSize ) {
				largestSize = dimSize;
				largestDim = d;
			}
		}
		return largestDim;
	}

	private static void
			calculateMapping( int[] mappingIn, int[] mappingOut, DatasetTensorBridge bridge ) {

		for ( int i = 0; i < mappingIn.length; i++ ) {
			mappingIn[ i ] = bridge.getTfIndexByDatasetDim( i );
		}
		replaceNegativesWithMissingIndices( mappingIn );
		System.out.println( "mapping in: " + Arrays.toString( mappingIn ) );

		//check if network reduces dimension, if yes, remote Z from mapping
		bridge.handleDimensionReduction();

		AxisType[] mappingOutDimType = new AxisType[ mappingOut.length ];
		for ( int i = 0; i < mappingOut.length; i++ ) {
			mappingOut[ i ] =
					bridge.getTfIndexByDatasetDim( i );
			mappingOutDimType[ i ] =
					bridge.getDimTypeByDatasetDim( i );
		}
		replaceNegativesWithMissingIndices( mappingOut );
		System.out.println( "mapping out: " + Arrays.toString( mappingOut ) );
	}

	public static < T extends RealType< T > > List< RandomAccessibleInterval< FloatType > >
			splitChannels( RandomAccessibleInterval< FloatType > img, int channelDim ) {

		ArrayList< RandomAccessibleInterval< FloatType > > res = new ArrayList<>();

		long[] minint = new long[ img.numDimensions() ];
		long[] maxint = new long[ img.numDimensions() ];
		for ( int i = 0; i < minint.length; i++ ) {
			if ( i != channelDim ) {
				minint[ i ] = img.min( i );
				maxint[ i ] = img.max( i );
			}
		}
		minint[ channelDim ] = 0;
		maxint[ channelDim ] = 0;
		res.add( Views.zeroMin( Views.interval( img, minint, maxint ) ) );

		minint[ channelDim ] = 1;
		maxint[ channelDim ] = 1;
		res.add( Views.zeroMin( Views.interval( img, minint, maxint ) ) );

		return res;
	}

	private static void replaceNegativesWithMissingIndices( int[] arr ) {
		List< Integer > indices = new ArrayList<>();
		for ( int i = 0; i < arr.length; i++ ) {
			indices.add( arr[ i ] );
		}
		for ( int i = 0; i < arr.length; i++ ) {
			if ( !indices.contains( i ) ) {
				for ( int j = 0; j < arr.length; j++ ) {
					if ( arr[ j ] == -1 ) {
						arr[ j ] = i;
						break;
					}
				}
			}
		}
	}

	private static < T extends RealType< T > > RandomAccessibleInterval< T >
			expandDimToSize( RandomAccessibleInterval< T > im, int d, long size ) {
		final int n = im.numDimensions();
		final long[] min = new long[ n ];
		final long[] max = new long[ n ];
		im.min( min );
		im.max( max );
		max[ d ] += ( size - im.dimension( d ) );
		return Views.interval( Views.extendMirrorDouble( im ), new FinalInterval( min, max ) );
	}

	private static < T extends RealType< T > > RandomAccessibleInterval< FloatType >
			executeGraphWithPadding(
					final RandomAccessibleInterval< T > tile,
					final DatasetConverter< T > datasetConverter,
					final int[] mappingIn,
					final int[] mappingOut,
					final SavedModelBundle model,
					final SignatureDef signature,
					final String inputNodeName,
					final String outputNodeName ) {

		Tensor inputTensor = datasetConverter.datasetToTensor( tile, mappingIn );
		if ( inputTensor != null ) {
			Tensor outputTensor = TensorFlowRunner.executeGraph(
					model,
					signature,
					inputTensor,
					inputNodeName,
					outputNodeName );
			if ( outputTensor != null ) { return datasetConverter.tensorToDataset(
					outputTensor,
					mappingOut ); }
		}
		return null;
	}

	// TODO remove
	private static void printDim( String name, RandomAccessibleInterval< ? > im ) {
		System.out.print( name + ": [ " );
		for ( int i = 0; i < im.numDimensions(); i++ ) {
			System.out.print( im.dimension( i ) + " " );
		}
		System.out.println( "]" );
	}

}
