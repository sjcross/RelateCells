// TODO: Filter for TrackMate tracks with a mean intenisty ~0 (those found in the image border following registration)


import fiji.plugin.trackmate.Logger
import fiji.plugin.trackmate.Model
import fiji.plugin.trackmate.Settings
import fiji.plugin.trackmate.Spot
import fiji.plugin.trackmate.TrackMate
import fiji.plugin.trackmate.detection.DetectorKeys
import fiji.plugin.trackmate.detection.LogDetectorFactory
import fiji.plugin.trackmate.gui.descriptors.SpotFilterDescriptor
import fiji.plugin.trackmate.tracking.LAPUtils
import fiji.plugin.trackmate.tracking.TrackerKeys
import fiji.plugin.trackmate.tracking.oldlap.SimpleLAPTrackerFactory
import ij.IJ
import ij.ImagePlus
import ij.gui.GenericDialog
import ij.gui.Line
import ij.gui.OvalRoi
import ij.gui.Overlay
import ij.gui.PolygonRoi
import ij.gui.Roi
import ij.measure.Measurements
import ij.plugin.ChannelSplitter
import ij.plugin.Duplicator
import ij.plugin.ImageCalculator
import ij.plugin.RGBStackMerge
import ij.plugin.filter.BackgroundSubtracter
import ij.plugin.filter.GaussianBlur
import ij.plugin.filter.ParticleAnalyzer
import ij.plugin.frame.RoiManager
import ij.process.AutoThresholder
import ij.process.FloatPolygon
import ij.process.ImageConverter
import ij.process.StackConverter
import ij.process.StackProcessor
import ij.process.StackStatistics
import org.apache.hadoop.hbase.util.MunkresAssignment
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.xssf.usermodel.XSSFSheet
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import wolfson.common.FileConditions.ExtensionMatchesString
import wolfson.common.FileConditions.NameContainsPattern
import wolfson.common.FileConditions.NameContainsString
import wolfson.common.HighContent.HCFilenameGenerator
import wolfson.common.HighContent.HCParameterExtractor
import wolfson.common.HighContent.HCPatterns
import wolfson.common.HighContent.HCResult
import wolfson.common.System.FileCrawler

import javax.swing.JFileChooser
import java.awt.Color
import java.awt.Polygon
import java.util.regex.Pattern

/**
 * Created by sc13967 on 14/03/2017.
 */
class Relate_Cells {
    int maxCellID = 0 // Fluorescence-channel and phase-channel cells draw from the same pool

    static void main(String[] args) {
        new Relate_Cells().run()

    }

    void run() {
        // Starting ImageJ
        //new ImageJ()

        // Getting parameters via a generic dialog
        def params = getParameters()

        // Initialising ArrayList to hold HCResults
        IJ.log("Initialising system")
        def results = new ArrayList<HCResult>()

        // Setting up a condition to only load images with the IncuCyte filename format
        def filePattern = Pattern.compile(HCPatterns.incuCyteShortFilenamePattern)
        def filePatternCondition = new NameContainsPattern(filePattern, NameContainsPattern.INC_PARTIAL)

        // Setting up a condition to only load images containing "FL_" (i.e. the fluorescence channel images)
        def fileNameCondition = new NameContainsString("FL_")

        // Setting up a condition to only load images with .tif or .tiff extensions
        String[] exts = ["tif", "tiff"]
        def extensionConditon = new ExtensionMatchesString(exts)

        // Getting the root folder for analysis
        def rootFolder = new File((String) params.get("Root_folder"))

        // Initialising FileCrawler based on previously identified criteria
        def fileCrawler = new FileCrawler()
        fileCrawler.setRootFolder(rootFolder)
        fileCrawler.addFileCondition(filePatternCondition)
        fileCrawler.addFileCondition(fileNameCondition)
        fileCrawler.addFileCondition(extensionConditon)

        // Calculating the number of files that will be processed
        def numFiles = fileCrawler.getNumberOfValidFilesInStructure()
        IJ.log(numFiles+" image stacks to process")

        // Re-initialising the FileCrawler
        fileCrawler = new FileCrawler()
        fileCrawler.setRootFolder(rootFolder)
        fileCrawler.addFileCondition(filePatternCondition)
        fileCrawler.addFileCondition(fileNameCondition)
        fileCrawler.addFileCondition(extensionConditon)

        // Running through all compatible files in the structure
        int iter = 1
        def file = fileCrawler.getNextValidFileInStructure()
        while (file != null) {
            IJ.log("Processing image stack "+iter+":")

            // Creating the result structure for the current file
            IJ.log("    Extracting metadata:")
            def result = new HCResult()
            new HCParameterExtractor(result).extractIncuCyteShortFile(file.name)
            IJ.log("        Well: "+result.well)
            IJ.log("        Field: "+result.field)

            // Loading current image stack
            def ipl = loadImages(file, result)

            // Running stack alignment
            ipl = runDriftCorrection(ipl,params)
            ipl.show()
            ipl.updateAndDraw()

            // Running the cell relation process
            runRelation(ipl, params, result)
            results.add(result)

            // Loading the next valid file
            file = fileCrawler.getNextValidFileInStructure()

            iter++

            return

        }
    }

    static HashMap<String,Object> getParameters() {
        GenericDialog gd = new GenericDialog("Parameters")
        gd.addMessage("GENERAL:")
        gd.addNumericField("Border width (%): ",5,0)
        gd.addMessage(" ")
        gd.addMessage("FLUORESCENCE CHANNEL:")
        gd.addNumericField("DoG filter radius (px): ", 4, 1)
        gd.addNumericField(" Max. edge-edge distance (px): ", 20, 1)
        gd.addNumericField("Max. centroid-centroid distance (px): ", 50, 1)
        gd.addCheckbox("Invert intensity",true)
        gd.addMessage(" ")
        gd.addMessage("PHASE CONTRAST CHANNEL:")
        gd.addNumericField("Detection radius (px): ", 7.5, 1)
        gd.addNumericField("Threshold (px): ", 2, 1)
        gd.addNumericField("Max. linking distance (px): ", 1, 1)
        gd.addNumericField("Gap closing max. linking distance (px): ", 1, 1)
        gd.addNumericField("Gap closing max. frame gap (px): ", 1, 0)
        gd.addMessage(" ")
        gd.addMessage("OUTPUT:")
        gd.addCheckbox("Display detection and links",true)

        gd.showDialog()

        // Parameters
        HashMap<String,Object> params = new HashMap<String, Object>()
        params.put("Border_width",(double) gd.getNextNumber()) // Percentage width of the border following drift correction
        params.put("DoG_Radius",(double) gd.getNextNumber()) // Radius for smaller Gaussian blur in DoG filtering (larger is 1.6*dogR)
        params.put("Max_Link_Threshold",(double) gd.getNextNumber()) // Distance in px from one cell boundary to the other
        params.put("Centroid_Link_Threshold",(double) gd.getNextNumber()) // Centroid distance for accurate edge-edge distance to be calculated
        params.put("Invert_intensity",(boolean) gd.getNextBoolean()) // Inverting the intensity appears necessary for some ImageJ versions (not sure why yet)
        params.put("TrackMate_Radius",(double) gd.getNextNumber())
        params.put("TrackMate_Threshold",(double) gd.getNextNumber())
        params.put("TrackMate_Gap_Closing_Max_Dist",(double) gd.getNextNumber())
        params.put("TrackMate_Linking_Max_Dist",(double) gd.getNextNumber())
        params.put("TrackMate_Gap_Closing_Frame_Gap",(Integer) gd.getNextNumber())
        params.put("Display_Links",(boolean) gd.getNextBoolean())

        // Getting root folder for analysis
        def rootFolder = getRootFolder()
        params.put("Root_folder",rootFolder.absolutePath)

        // Getting export location for the Excel file
        def exportFile = getExportLocation()
        params.put("Export_file",exportFile.getAbsolutePath())

        return params

    }

    static File getRootFolder() {
        // Opening the file dialog.  Only directories can be selected
        def openFileDialog = new JFileChooser("C:\\Users\\sc13967\\Google Drive\\People\\H\\Lea Hampton-O'Neil\\2017-03-07 Cell association (Incucyte)\\Batch data\\Crop")
        openFileDialog.setDialogTitle("Select the root folder")
        openFileDialog.setMultiSelectionEnabled(false)
        openFileDialog.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY)
        openFileDialog.showOpenDialog(null)
        def rootFolder = openFileDialog.getSelectedFile()

        return rootFolder

    }

    static File getExportLocation() {
		// Opening the file dialog.  Only directories can be selected
        def openFileDialog = new JFileChooser()
        openFileDialog.setDialogTitle("Select file to save")
        openFileDialog.setMultiSelectionEnabled(false)
        openFileDialog.setFileSelectionMode(JFileChooser.FILES_ONLY)
        openFileDialog.showOpenDialog(null)
        def exportFile = openFileDialog.getSelectedFile()

        return exportFile

    }

    static ImagePlus loadImages(File file, HCResult result) {
        // Getting the filename for the corresponding phase-contrast image
        def phFilename = HCFilenameGenerator.generateIncuCyteShortFile("PH",result.well,result.field,result.ext)

        // Creating structure to hold both image stacks
        def ipls = new ImagePlus[2]

        // Loading the current fluorescence channel file to ImageJ
        IJ.log("    Loading fluorescence image stack ("+file.name+")")
        ipls[0] = IJ.openImage(file.absolutePath)

        // Loading the current phase-contrast channel file to ImageJ
        IJ.log("    Loading phase-contrast image stack ("+phFilename+")")
        ipls[1] = IJ.openImage(file.getParentFile().absolutePath+"\\"+phFilename)

        // Combining the two images for stack registration
        IJ.log("    Creating composite image")
        def ipl = RGBStackMerge.mergeChannels(ipls,false)

        // Converting ImagePlus to time-based if there are more slices than frames and the number of frames is 1
        if (ipl.getNSlices() > 1 & ipl.getNFrames() == 1) {
            switchTimeAndZ(ipl)
        }

        return ipl

    }

    static ImagePlus runDriftCorrection(ImagePlus ipl, HashMap<String,Object> params) {
        new StackConverter(ipl).convertToRGB()

        // Running stack registration
        IJ.log("    Aligning image stack using StackReg")
        ipl.setT(ipl.getNFrames())
        IJ.run(ipl,"StackReg","transformation=Translation")

        //Converting back to a composite image
        def ipls = new ChannelSplitter().split(ipl)
        ImagePlus[] ipls2 = [ipls[0],ipls[1]]
        ipl = RGBStackMerge.mergeChannels(ipls2,false)

        // Cropping the image to the central region
        def borderFrac = (double) params.get("Border_width")/100
        def w = ipl.getWidth()
        def h = ipl.getHeight()
        def ist = new StackProcessor(ipl.getStack()).crop((w*borderFrac).intValue(),(h*borderFrac).intValue(),(w*(1-2*borderFrac)).intValue(),(h*(1-2*borderFrac)).intValue())
        ipl.setStack(ist)

        // Converting ImagePlus to time-based if there are more slices than frames and the number of frames is 1
        if (ipl.getNSlices() > 1 & ipl.getNFrames() == 1) {
            switchTimeAndZ(ipl)
        }

        return ipl

    }

    HCResult runRelation(ImagePlus ipl, HashMap<String,Object> params, HCResult result) {
        // Getting the separate channels
//        IJ.log("Splitting channels")
        def ipls = new ChannelSplitter().split(ipl)

        // Calculating the difference of Gaussian for the phase-contrast channel
        IJ.log("    Running DoG filter on phase-contrast channel")
        def phaseIpl = runDoG(ipls[1],(double) params.get("DoG_Radius"))

        // Creating a separate image to perform fluorescence operations on
        IJ.log("    Duplicating fluorescence channel")
        def fluorIpl = new Duplicator().run(ipls[0])

        // Detecting cells in the phase-contrast channel
        IJ.log("    Detecting cells in phase-contrast channel using TrackMate")
        ArrayList<Cell> phCells = detectPhaseContrastCells(phaseIpl, params)

        // Detecting cells in the fluorescence channel
        IJ.log("    Detecting cells in fluorescence channel")
        ArrayList<Cell> flCells = detectFluorescentCells(fluorIpl, params)

        // Linking phase contrast cells to tracks
        IJ.log("    Tracking cells in fluorescence channel using Apache HBase (MunkresAssignment)")
        trackCells(flCells)

        // Linking phase channel cells to fluorescence channel cells
        IJ.log("    Linking fluorescence and phase-contrast cells")
        compareCellPositions(phCells, flCells, (double) params.get("Max_Link_Threshold"), (double) params.get("Centroid_Link_Threshold"))

        // Calculating number of phase cells per frame
        IJ.log("    Calculating number of cells per frame in phase-contrast channel")
        def phCellsPerFrame = measureNumCellsPerFrame(phCells, ipl.getNFrames())

        // Displaying the tracked cells on the fluorescence channel
        if (params.get("Display_Links")) {
            IJ.log("Visualising detections")
            showLinkedCells(phCells, flCells, ipl)
            ipl.show()
            ipl.updateAndDraw()
        }

//        // Exporting tracked cells and the cell-cell links to an Excel file
//        IJ.log("Exporting results to .xlsx file")
//        def exportFile = new File((String) params.get("Export_file"))
//        exportResults(flCells,phCellsPerFrame,params, exportFile)

        System.out.println("runRelation returns null.  Should return HCResult")
        return null

    }

    /**
     * Converts a hyperstack with time listed in the Z-dimension to having frames in T.  Uses the same approach as
     * TrackMate
     * @param ipl Input ImagePlus
     */
    static void switchTimeAndZ(ImagePlus ipl) {
        def dimensions = ipl.getDimensions()
        ipl.setDimensions(dimensions[2],dimensions[4],dimensions[3])

    }

    static ImagePlus runDoG(ImagePlus ipl, double sigma) {
        // Duplicating the input ImagePlus
        def ipl1 = ipl
        def ipl2 = new Duplicator().run(ipl1)

        // Converting both ImagePlus into 32-bit, to give a smoother final result
        new ImageConverter(ipl1).convertToGray32()
        new ImageConverter(ipl2).convertToGray32()

        // Performing the next couple of steps on all slices
        for (int i = 0; i < ipl.getNFrames(); i++) {
            // Setting the current slice number
            ipl1.setT(i+1)
            ipl2.setT(i+1)

            // Multiplying the intensities by 10, to give a smoother final result
            ipl1.getProcessor() * 10
            ipl2.getProcessor() * 10

            // Running Gaussian blur at two length scales.  One 1.6 times larger than the other (based on discussion at
            // http://dsp.stackexchange.com/questions/2529/what-is-the-relationship-between-the-sigma-in-the-laplacian-of-
            // gaussian-and-the (Accessed 14-03-2017)
            new GaussianBlur().blurGaussian(ipl1.getProcessor(), sigma, sigma, 0.01)
            new GaussianBlur().blurGaussian(ipl2.getProcessor(), sigma * 1.6, sigma * 1.6, 0.01)
        }

        // Subtracting one from the other to give the final DoG result
        def iplOut = new ImageCalculator().run("Subtract create stack 32-bit",ipl2,ipl1)

        new ImageConverter(iplOut).convertToGray8()

        return iplOut

    }

    ArrayList<Cell> detectPhaseContrastCells(ImagePlus ipl, HashMap<String,Object> params) {
        def model = new Model()

        // Disabling logging
        model.setLogger(Logger.VOID_LOGGER)

        def settings = new Settings()
        settings.setFrom(ipl)

        settings.detectorFactory = new LogDetectorFactory()
        settings.detectorSettings.put(DetectorKeys.KEY_DO_SUBPIXEL_LOCALIZATION,true)
        settings.detectorSettings.put(DetectorKeys.KEY_RADIUS,(Double) params.get("TrackMate_Radius"))
        settings.detectorSettings.put(DetectorKeys.KEY_TARGET_CHANNEL,(Integer) 1)
        settings.detectorSettings.put(DetectorKeys.KEY_THRESHOLD,(Double) params.get("TrackMate_Threshold"))
        settings.detectorSettings.put(DetectorKeys.KEY_DO_MEDIAN_FILTERING,false)

        settings.trackerFactory = new SimpleLAPTrackerFactory()
        settings.trackerSettings = LAPUtils.getDefaultLAPSettingsMap()
        settings.trackerSettings.put(TrackerKeys.KEY_LINKING_MAX_DISTANCE,(Double) params.get("TrackMate_Linking_Max_Dist"))
        settings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_DISTANCE,(Double) params.get("TrackMate_Gap_Closing_Max_Dist"))
        settings.trackerSettings.put(TrackerKeys.KEY_GAP_CLOSING_MAX_FRAME_GAP,(Integer) params.get("TrackMate_Gap_Closing_Frame_Gap"))
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
            def rad = it.getFeature(Spot.RADIUS)
            def ovalRoi = new OvalRoi(it.getFeature(Spot.POSITION_X)-rad,it.getFeature(Spot.POSITION_Y)-rad,rad*2,rad*2)
            def cell = new Cell(ovalRoi.getFloatPolygon().xpoints,ovalRoi.getFloatPolygon().ypoints,Roi.POLYGON)
            cell.setPosition(it.getFeature(Spot.POSITION_T).intValue()+1)
            cell.setCellID(maxCellID++)

            cells.add(cell)

        }

        return cells
    }

    ArrayList<Cell> detectFluorescentCells(ImagePlus ipl, HashMap<String,Object> params) {
        // Applying basic image processing to clean up the image
        // Performing the next couple of steps on all slices
        for (int i = 0; i < ipl.getNFrames(); i++) {
            // Setting the current slice number
            def frame = i+1
            ipl.setT(frame)

            new BackgroundSubtracter().rollingBallBackground(ipl.getProcessor(),50,false,false,false,true,true)
            new GaussianBlur().blurGaussian(ipl.getProcessor(),2,2,0.01)

        }

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
        for (int i=0;i<ipl.getNFrames();i++) {
            ipl.setT(i+1)
            ipl.getProcessor().threshold(thresh)

        }

        // Creating a container for the cells and a RoiManager, where the results will be stored temporarily
        def cells = new ArrayList<Cell>()
        def rois = RoiManager.getRoiManager()
        rois.setVisible(false)

        // Running through each slice (frame), detecting the particles
        for (int frame=0;frame<ipl.getNFrames();frame++) {
            ipl.setT(frame+1)

            // Inverting the intensity.  This seems necessary for some versions of ImageJ, but not others :/
            if ((boolean) params.get("Invert_intensity")) {
                ipl.getProcessor().invert()
            }

            // Running analyse particles
            def partAnal = new ParticleAnalyzer(ParticleAnalyzer.EXCLUDE_EDGE_PARTICLES, Measurements.AREA | Measurements.CENTROID, null, 300, Integer.MAX_VALUE, 0, 1)
            partAnal.setRoiManager(rois)
            partAnal.analyze(ipl)

            // Adding all current rois to the cell arraylist
            for (int i = 0; i < rois.count; i++) {
                def cell = new Cell(rois.getRoi(i).polygon, PolygonRoi.POLYGON)
                cell.setPosition(frame+1)
                cell.setCellID(maxCellID++)
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
    static void compareCellPositions(ArrayList<Cell> phCells, ArrayList<Cell> flCells, double maxLinkDist, double centLinkDist) {
        // Running through all cells in both the fluorescence and phase channels

        for (int i=phCells.size()-1;i>=0;i--) {
            IJ.showProgress((phCells.size()-i)/phCells.size())
            def phCell = phCells.get(i)
            for (def flCell:flCells) {
                // Only testing for a link if the cells are visible in the same frame
                if (phCell.getPosition() == flCell.getPosition()) {
                    // Performing a crude spatial test to check if they are remotely close
                    def phCent = phCell.contourCentroid
                    def flCent = flCell.contourCentroid

                    // Testing if the phase channel cell is within the boundary of the fluorescent channel cell.  This
                    // can arise from mis-detection during the DoG/TrackMate steps
                    if (flCell.contains(phCent[0].intValue(), phCent[1].intValue())) {
                        phCells.remove(i)

                    } else {
                        // Calculating a crude centre-centre cell distance to identify cells for further comparison
                        def centDist = Math.sqrt((phCent[0] - flCent[0]) * (phCent[0] - flCent[0]) + (phCent[1] - flCent[1]) * (phCent[1] - flCent[1]))

                        if (centDist < centLinkDist) {
                            // Calculating the distance between the cells
                            def dist = flCell.measureDistanceToCell(phCell)

                            // If the distance is less than the user-defined threshold ("maxLinkDist") adding reference to
                            // the other cell in each cell's linkedCells ArrayList
                            if (dist < maxLinkDist) {
                                flCell.addLinkedCell(phCell)
                                phCell.addLinkedCell(flCell)

                            }
                        }
                    }
                }
            }
        }
    }

    static int[] measureNumCellsPerFrame(ArrayList<Cell> cells, int nFrames) {
        // Initialising the results array
        def numCells = new int[nFrames]

        cells.each {
            numCells[it.getPosition()-1]++
        }

        return numCells

    }

    static void showCells(ArrayList<Cell> cells, ImagePlus ipl) {
        def overlay = new Overlay()

        cells.each {
            it.setStrokeColor(it.getColour())
            overlay.add(it)

        }

        ipl.setOverlay(overlay)
        ipl.show()
        IJ.runMacro("Grays")

    }

    static showLinkedCells(ArrayList<Cell> phCells, ArrayList<Cell> flCells, ImagePlus ipl) {
        def overlay = new Overlay()

        // Adding phase-channel cells to the overlay
        phCells.each {
            it.setStrokeColor(it.getColour())
            overlay.add(it)

        }

        // Adding fluorescence-channel cells to the overlay and links to phase channel cells
        flCells.each {
            it.setStrokeColor(it.getColour())
            overlay.add(it)

            def links = it.getLinkedCells()
            for (def link:links) {
                def cent1 = it.contourCentroid
                def cent2 = link.contourCentroid
                def line = new Line(cent1[0],cent1[1],cent2[0],cent2[1])
                line.setPosition(it.getPosition())
                line.setStrokeColor(it.getColour())
                overlay.add(line)
            }
        }

        ipl.setOverlay(overlay)

    }

    static void exportResults(ArrayList<Cell> cells, int[] cellsPerFrame, HashMap<String,Object> params, File exportFile) {
        XSSFWorkbook workbook = new XSSFWorkbook()

        prepareResults(workbook, cells, cellsPerFrame)
        prepareParametersXLSX(workbook, params)

        try {
            FileOutputStream outputStream = new FileOutputStream(exportFile)
            workbook.write(outputStream)
            workbook.close()

        } catch (FileNotFoundException e) {
            e.printStackTrace()
        } catch (IOException e) {
            e.printStackTrace()
        }
    }

    static void prepareResults(XSSFWorkbook workbook,ArrayList<Cell> cells, int[] cellsPerFrame) {
        XSSFSheet resultSheet = workbook.createSheet("Results")

        // Creating row headers
        int rowNum = 0
        int colNum = 0

        def row = resultSheet.createRow(rowNum++)

        def cellIDHeader = row.createCell(colNum++)
        cellIDHeader.setCellValue("CellID")

        def trackIDHeader = row.createCell(colNum++)
        trackIDHeader.setCellValue("TrackID")

        def xHeader = row.createCell(colNum++)
        xHeader.setCellValue("CENT_X")

        def yHeader = row.createCell(colNum++)
        yHeader.setCellValue("CENT_Y")

        def frameHeader = row.createCell(colNum++)
        frameHeader.setCellValue("FRAME")

        def nLinksHeader = row.createCell(colNum++)
        nLinksHeader.setCellValue("N_LINKS")

        def nLinksNormHeader = row.createCell(colNum++)
        nLinksNormHeader.setCellValue("N_LINKS_NORM")

        // Adding a new row for each detected cell
        cells.each {
            colNum = 0
            row = resultSheet.createRow(rowNum++)

            def cellIDVal = row.createCell(colNum++)
            cellIDVal.setCellValue(it.cellID)

            def trackIDVal = row.createCell(colNum++)
            trackIDVal.setCellValue(it.trackID)

            def cent = it.contourCentroid
            def xVal = row.createCell(colNum++)
            xVal.setCellValue(cent[0])

            def yVal = row.createCell(colNum++)
            yVal.setCellValue(cent[1])

            def frameVal = row.createCell(colNum++)
            frameVal.setCellValue(it.getPosition())

            def nLinksVal = row.createCell(colNum++)
            nLinksVal.setCellValue(it.getLinkedCells().size())

            def nLinksNormVal = row.createCell(colNum++)
            int nCells = it.getLinkedCells().size()
            int currNCellsPerFrame = cellsPerFrame[it.getPosition()-1]
            if (nCells != 0 & currNCellsPerFrame != 0) {
                nLinksNormVal.setCellValue(nCells/currNCellsPerFrame)
            } else {
                nLinksNormVal.setCellValue(Double.NaN)
            }

        }
    }

    static void prepareParametersXLSX(XSSFWorkbook workbook, HashMap<String,Object> params) {
        XSSFSheet paramSheet = workbook.createSheet("Parameters")

        int rowNum = 0
        params.keySet().each {
            Object entry = params.get(it)

            Row row = paramSheet.createRow(rowNum++)

            org.apache.poi.ss.usermodel.Cell nameCell = row.createCell(0)
            nameCell.setCellValue(it)

            org.apache.poi.ss.usermodel.Cell valueCell = row.createCell(1)
            valueCell.setCellValue(entry.toString())
        }
    }
}

class Cell extends PolygonRoi {
    int cellID = -1
    int trackID = -1
    def colour = Color.getHSBColor(1,1,1)
    def linkedCells = new ArrayList<Cell>()


    // CONSTRUCTORS

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


    // PUBLIC METHODS
    /**
     * Measures the closest edge-edge separation between the cell and a second, user-specified cell
     * @return
     */
    double measureDistanceToCell(Cell cell2) {
        def x1 = floatPolygon.xpoints
        def y1 = floatPolygon.ypoints
        def x2 = cell2.floatPolygon.xpoints
        def y2 = cell2.floatPolygon.ypoints

        def minDist = Double.MAX_VALUE
        for (int i=0;i<x1.length;i++) {
            for (int j=0;j<x2.length;j++) {
                def dist = Math.sqrt((x2[j]-x1[i])*(x2[j]-x1[i])+(y2[j]-y1[i])*(y2[j]-y1[i]))
                if (dist < minDist) {
                    minDist = dist
                }
            }
        }

        return minDist

    }

    void addLinkedCell(Cell cell) {
        linkedCells.add(cell)
    }

    void removeLinkedCell(Cell cell) {
        linkedCells.remove(cell)
    }


    // GETTERS AND SETTERS

    int getCellID() {
        return cellID
    }

    void setCellID(int cellID) {
        this.cellID = cellID
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

    ArrayList<Cell> getLinkedCells() {
        return linkedCells

    }

}