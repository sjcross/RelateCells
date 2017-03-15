package wolfson.RelateCells

import fiji.IJ1LogService
import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.tracking.TrackerKeys
import fiji.plugin.trackmate.tracking.sparselap.SparseLAPTrackerFactory
import ij.IJ
import ij.ImageJ
import ij.ImagePlus
import ij.plugin.ChannelSplitter
import ij.plugin.Duplicator
import ij.plugin.ImageCalculator
import ij.plugin.filter.GaussianBlur
import ij.process.ImageConverter

/**
 * Created by sc13967 on 14/03/2017.
 */
class Main {
    // Getting open image
    static void main(String[] args) {
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

        phaseSub.show()

        runTrackMate(phaseSub)

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

        return iplOut

    }

    static void runTrackMate(ImagePlus ipl) {
        def settings = new Settings()
        settings.setFrom(ipl)

        settings.detectorFactory = new LogDetectorFactory()
        settings.detectorSettings.put(DetectorKeys.KEY_DO_SUBPIXEL_LOCALIZATION,true)
        settings.detectorSettings.put(DetectorKeys.KEY_RADIUS,2.5)
        settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL,0)
        settings.detectorSettings.put(DetectorKeys.KEY_THRESHOLD,0.001)
        settings.detectorSettings.put(DetectorKeys.KEY_DO_MEDIAN_FILTERING,false)

        settings.trackerFactory = new SparseLAPTrackerFactory()
        settings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE,1d)
        settings.trackerSettings.put(TrackerKeys.KEY_LINKING_MAX_DISTANCE,1d)
        settings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP,1d)
        settings.trackerSettings.put(TrackerKeys.KEY_ALLOW_TRACK_SPLITTING,false)
        settings.trackerSettings.put(TrackerKeys.KEY_ALLOW_TRACK_MERGING,false)

        System.out.println(settings.toString())

        def model = new Model()
        model.setLogger(Logger.IJ_LOGGER)

        def trackmate = new TrackMate(model,settings)
        trackmate.process()

        IJ.log(String.valueOf(model.getSpots().getNSpots(true)))

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