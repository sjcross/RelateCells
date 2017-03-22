package wolfson.RelateCells

import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.Spot
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.tracking.LAPUtils
import fiji.plugin.trackmate.tracking.TrackerKeys
import fiji.plugin.trackmate.tracking.oldlap.SimpleLAPTrackerFactory
import fiji.plugin.trackmate.tracking.oldlap.hungarian.MunkresKuhnAlgorithm
import ij.IJ
import ij.ImageJ
import ij.ImagePlus
import ij.LookUpTable
import ij.gui.OvalRoi
import ij.gui.Overlay
import ij.gui.PolygonRoi
import ij.gui.Roi
import ij.measure.Measurements
import ij.plugin.ChannelSplitter
import ij.plugin.Duplicator
import ij.plugin.ImageCalculator
import ij.plugin.filter.BackgroundSubtracter
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.ParticleAnalyzer
import ij.plugin.frame.RoiManager
import ij.process.AutoThresholder
import ij.process.FloatPolygon
import ij.process.ImageConverter
import ij.process.LUT
import ij.process.StackStatistics
import org.apache.hadoop.hbase.util.MunkresAssignment

import java.awt.Color
import java.awt.Polygon
import java.awt.image.IndexColorModel
import java.lang.reflect.Array

/**
 * Created by sc13967 on 14/03/2017.
 */
class Main {
    // Getting open image
    static void main(String[] args) {
        run()

    }

    static void run(){
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
        //def phaseSub = runDoG(ipls[1],dogR)

        // Detecting cells in the phase-contrast channel
        //ArrayList<Cell> phaseCells = detectPhaseContrastCells(phaseSub)

        // Detecting cells in the fluorescence channel
        ArrayList<Cell> fluoCells = detectFluorescentCells(ipls[0])

        // Linking phase contrast cells to tracks
        trackCells(fluoCells)

        // Displaying the tracked cells on the fluorescence channel
        showCells(fluoCells, ipls[0])

    }

    static ImagePlus runDoG(ImagePlus ipl, double sigma) {
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

    static ArrayList<Cell> detectPhaseContrastCells(ImagePlus ipl) {
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

        return cells
    }

    static ArrayList<Cell> detectFluorescentCells(ImagePlus ipl) {
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
            ipl.setSlice(i+1)
            ipl.getProcessor().threshold(thresh)

        }

        // Creating a container for the cells and a RoiManager, where the results will be stored temporarily
        def cells = new ArrayList<Cell>()
        def rois = new RoiManager()
        rois.setVisible(false)

        // Running through each slice (frame), detecting the particles
        for (int slice=0;slice<ipl.getNSlices();slice++) {
            ipl.setSlice(slice+1)
            ipl.getProcessor().invert()

            // Running analyse particles
            def partAnal = new ParticleAnalyzer(ParticleAnalyzer.EXCLUDE_EDGE_PARTICLES, Measurements.AREA | Measurements.CENTROID, null, 300, Integer.MAX_VALUE, 0, 1)
            partAnal.setRoiManager(rois)
            partAnal.analyze(ipl)

            // Adding all current rois to the cell arraylist
            for (int i = 0; i < rois.count; i++) {
                def cell = new Cell(rois.getRoi(i).polygon, PolygonRoi.POLYGON)
                cell.setPosition(slice+1)
                cells.add(cell)
            }

            // Removing the previously-detected ROIs from the RoiManager
            rois.reset()
        }

        return cells

    }

    /**
     * Links cells using the Munkres algorithm.
     * @param cells
     */
    static void trackCells(ArrayList<Cell> cells) {
        def maxDist = 100

        // Getting the maximum frame number
        def maxFr = 0
        cells.each {
            if (it.getPosition() > maxFr) {
                maxFr = it.getPosition()
            }
        }

        def trackID = 0

        def rand = new Random(System.currentTimeMillis())
        // Assigning new trackIDs to all cells in the first frame
        cells.each {
            if (it.getPosition() == 1) {
                it.setTrackID(trackID++)

                it.setColour(Color.getHSBColor(rand.nextFloat(),1,1))
            }
        }

        // Going through each frame, getting the current cells and the ones from the previous frame
        for (int fr = 2;fr <= maxFr; fr++) {
            def prevCells = new ArrayList<Cell>()
            def currCells = new ArrayList<Cell>()

            cells.each {
                if (it.getPosition()+1 == fr) {
                    prevCells.add(it)
                } else if (it.getPosition() == fr) {
                    currCells.add(it)
                }
            }

            // Creating a 2D cost matrix for the overlap.  A maximum linking distance is specified, above which costs are Inf
            def cost = new float[currCells.size()][prevCells.size()]
            for (int curr=0;curr<cost.length;curr++) {
                for (int prev=0;prev<cost[0].length;prev++) {
                    def currCent = currCells.get(curr).contourCentroid
                    def prevCent = prevCells.get(prev).contourCentroid

                    def dist = Math.sqrt((prevCent[0]-currCent[0])*(prevCent[0]-currCent[0])+(prevCent[1]-currCent[1])*(prevCent[1]-currCent[1]))

                    if (dist < maxDist) {
                        cost[curr][prev] = dist
                    } else {
                        cost[curr][prev] = Float.MAX_VALUE
                    }
                }
            }

            // Running the Munkres algorithm to assign matches.
            def assignment = new MunkresAssignment(cost).solve()

            // Applying the calculated track IDs to the cells
            for (int curr=0;curr<assignment.size();curr++) {
                if (assignment[curr] == -1) {
                    def currCell = currCells.get(curr)
                    currCell.setTrackID(trackID++)
                    currCell.setColour(Color.getHSBColor(rand.nextFloat(),1,1))

                } else {
                    def prevCell = prevCells.get(assignment[curr])
                    def currCell = currCells.get(curr)
                    currCell.setTrackID(prevCell.getTrackID())
                    currCell.setColour(prevCell.getColour())

                }
            }
        }
    }

    /**
     * Basic analysis taking cells in each frame as isolated objects.
     * @param cells1
     * @param cells2
     */
    static void compareCellPositions(ArrayList<Cell> cells1, ArrayList<Cell> cells2) {

    }

    static void showCells(ArrayList<Cell> cells, ImagePlus ipl) {
        def overlay = new Overlay()

        cells.each {
            System.out.println(it.getColour())
            it.setStrokeColor(it.getColour())
            overlay.add(it)
            
        }

        ipl.setOverlay(overlay)
        ipl.show()
        IJ.runMacro("Grays")

    }
}

class Cell extends PolygonRoi{
    int trackID = -1
    def colour = Color.getHSBColor(1,1,1)

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

    int getTrackID() {
        return trackID
    }

    void setTrackID(int trackID) {
        this.trackID = trackID
    }

    def getColour() {
        return colour
    }

    void setColour(colour) {
        this.colour = colour
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