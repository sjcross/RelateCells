package wolfson.RelateCells

import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.Spot
import fiji.plugin.trackmate.SpotCollection
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.tracking.LAPUtils
import fiji.plugin.trackmate.tracking.TrackerKeys
import fiji.plugin.trackmate.tracking.oldlap.SimpleLAPTrackerFactory
import ij.IJ
import ij.ImageJ
import ij.ImagePlus
import ij.gui.OvalRoi
import ij.gui.PolygonRoi
import ij.gui.Roi
import ij.measure.Measurements
import ij.measure.ResultsTable
import ij.plugin.ChannelSplitter
import ij.plugin.Duplicator
import ij.plugin.HyperStackConverter
import ij.plugin.ImageCalculator
import ij.plugin.filter.BackgroundSubtracter
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.ParticleAnalyzer
import ij.plugin.frame.RoiManager
import ij.process.AutoThresholder
import ij.process.FloatPolygon
import ij.process.ImageConverter
import ij.process.StackStatistics

import java.awt.Polygon
import java.lang.reflect.Array


/**
 * Created by sc13967 on 14/03/2017.
 */
class Main {
    // Getting open image
    static void main(String[] args) {
        new Main().run()

    }

    void run(){
        // Parameters
        def dogR = 4

        // Starting ImageJ
        new ImageJ()
        IJ.runMacro("waitForUser")

        // Getting the image open in ImageJ
        def ipl = IJ.getImage()

        // Getting the separate channels
        def ipls = new ChannelSplitter().split(ipl)

        // Calculating the difference of Gaussian for the phase-contrast channel
        def phaseSub = runDoG(ipls[1],dogR)

        // Detecting cells in the phase-contrast channel
//         ArrayList<Cell> phaseCells = detectPhaseContrastCells(phaseSub)

        // Detecting cells in the fluorescence channel
        ArrayList<Cell> fluoCells = detectFluorescentCells(ipls[0])

    }

    ImagePlus runDoG(ImagePlus ipl, double sigma) {
        // Duplicating the input ImagePlus
        def ipl1 = ipl
        def ipl2 = new Duplicator().run(ipl1)

        // Converting both ImagePlus into 32-bit, to give a smoother final result
        new ImageConverter(ipl1).convertToGray32()
        new ImageConverter(ipl2).convertToGray32()

        // Multiplying the intensities by 10, to give a smoother final result
        ipl1.getProcessor()*10
        ipl2.getProcessor()*10

        // Running Gaussian blur at two length scales.  One 1.6 times larger than the other (based on discussion at
        // http://dsp.stackexchange.com/questions/2529/what-is-the-relationship-between-the-sigma-in-the-laplacian-of-
        // gaussian-and-the (Accessed 14-03-2017)
        new GaussianBlur().blurGaussian(ipl1.processor,sigma,sigma,0.01)
        new GaussianBlur().blurGaussian(ipl2.processor,sigma*1.6,sigma*1.6,0.01)

        // Subtracting one from the other to give the final DoG result
        def iplOut = new ImageCalculator().run("Subtract create 32-bit",ipl2,ipl1)

        new ImageConverter(iplOut).convertToGray8()

        return iplOut

    }

    ArrayList<Cell> detectPhaseContrastCells(ImagePlus ipl) {
        def model = new Model()
        model.setLogger(Logger.IJ_LOGGER)

        def settings = new Settings()
        settings.setFrom(ipl)

        settings.detectorFactory = new LogDetectorFactory()
        settings.detectorSettings.put(DetectorKeys.KEY_DO_SUBPIXEL_LOCALIZATION,true)
        settings.detectorSettings.put(DetectorKeys.KEY_RADIUS,(Double) 7.5)
        settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL,(Integer) 1)
        settings.detectorSettings.put(DetectorKeys.KEY_THRESHOLD,(Double) 2)
        settings.detectorSettings.put(DetectorKeys.KEY_DO_MEDIAN_FILTERING,false)

        settings.trackerFactory = new SimpleLAPTrackerFactory()
        settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap()
        settings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE,(Double) 1)
        settings.trackerSettings.put(TrackerKeys.KEY_LINKING_MAX_DISTANCE,(Double) 1)
        settings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP,(Integer) 1)
        settings.trackerSettings.put(TrackerKeys.KEY_ALLOW_TRACK_SPLITTING,false)
        settings.trackerSettings.put(TrackerKeys.KEY_ALLOW_TRACK_MERGING,false)

        def trackmate = new TrackMate(model,settings)

        if (!trackmate.checkInput()) {
            IJ.log(trackmate.getErrorMessage())
        }

        if (!trackmate.process()) {
            IJ.log(trackmate.getErrorMessage())
        }

        def cells = new ArrayList<Cell>()
        def spots = model.getSpots()
        spots.iterable(true).each {
            def ovalRoi = new OvalRoi(it.getFeature(Spot.POSITION_X),it.getFeature(Spot.POSITION_Y),it.getFeature(Spot.RADIUS),it.getFeature(Spot.RADIUS))
            def cell = new Cell(ovalRoi.getFloatPolygon().xpoints,ovalRoi.getFloatPolygon().ypoints,Roi.POLYGON)

            cells.add(cell)

        }

        return cells;
    }

    ArrayList<Cell> detectFluorescentCells(ImagePlus ipl) {
        // Applying basic image processing to clean up the image
        new BackgroundSubtracter().rollingBallBackground(ipl.getProcessor(),50,false,false,false,true,true)
        new GaussianBlur().blurGaussian(ipl.getProcessor(),2,2,0.01)

        // Getting the stack intensity histogram
        def stackStats = new StackStatistics(ipl)
        def statLong = stackStats.histogram
        def statInt = new int[statLong.length]

        // Converting long-format image stack histogram measurements to int-format
        for (int i=0;i<statLong.length;i++) {
            statInt[i] = Math.toIntExact(statLong[i])
        }

        // Determining the threshold (Huang method)
        def thresh = new AutoThresholder().getThreshold(AutoThresholder.Method.Huang,statInt)

        // Running the threshold on each image in the stack
        for (int i=0;i<ipl.getNSlices();i++) {
            ipl.setSlice(i)
            ipl.getProcessor().threshold(thresh)

        }

        // Creating a container for the cells and a RoiManager, where the results will be stored temporarily
        def cells = new ArrayList<Cell>()
        def rois = new RoiManager()
        rois.setVisible(false)

        // Running through each slice (frame), detecting the particles
        for (int slice=0;slice<ipl.getNSlices();slice++) {
            // Running analyse particles
            def partAnal = new ParticleAnalyzer(0, Measurements.AREA | Measurements.CENTROID, null, 300, Integer.MAX_VALUE, 0, 1)
            partAnal.setRoiManager(rois)
            ipl.setSlice(slice)
            partAnal.analyze(ipl)

            // Adding all current rois to the cell arraylist
            for (int i = 0; i < rois.count; i++) {
                def cell = new Cell(rois.getRoi(i).polygon, PolygonRoi.POLYGON)
                cell.setPosition(0,0,slice)
                cells.add(cell)
            }

            // Removing the previously-detected ROIs from the RoiManager
            rois.reset()
        }

        return cells

    }
}

class Cell extends PolygonRoi{

    Cell(int[] xPoints, int[] yPoints, int nPoints, int type) {
        super(xPoints, yPoints, nPoints, type)
    }

    Cell(float[] xPoints, float[] yPoints, int nPoints, int type) {
        super(xPoints, yPoints, nPoints, type)
    }

    Cell(float[] xPoints, float[] yPoints, int type) {
        super(xPoints, yPoints, type)
    }

    Cell(Polygon p, int type) {
        super(p, type)
    }

    Cell(FloatPolygon p, int type) {
        super(p, type)
    }

    Cell(int sx, int sy, ImagePlus imp) {
        super(sx, sy, imp)
    }
}

//Img<IntType> img = ImageJFunctions.wrap(ipl)
//
//// Getting the phase-contrast channel
//def pcImg = Views.hyperSlice(img,2,1)
//pcImg = Views.hyperSlice(pcImg,2,1)
//
//long[] dims = new long[pcImg.numDimensions()]
//pcImg.dimensions(dims)
//
//def imgFac = new CellImgFactory(2)
//def imgSmall = imgFac.create(dims,new FloatType())
//
//Gauss3.gauss(4,pcImg,imgSmall)
//ImageJFunctions.show(imgSmall)