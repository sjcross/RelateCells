package wbif.sjx.RelateCells;

import ij.*;
import ij.gui.Roi;
import ij.plugin.PlugIn;
import org.apache.commons.io.FilenameUtils;
import org.apache.hadoop.hbase.util.MunkresAssignment;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.swing.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import java.awt.*;
import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Random;

/**
 * Loads tracks from results table.  Tracks are stored in terms of calibrated distances.
 */
public class FixTrackIDs implements PlugIn {
    /**
     * Main method for debugging.
     * @param args
     */
    public static void main(String[] args) {
        new ImageJ();
        new FixTrackIDs().run("");

    }

    /**
     * Run by ImageJ.
     * @param s
     */
    public void run(String s) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            ArrayList<ArrayList<Result>> combinedResults = new ArrayList<>();

            // Getting the file to read
            String filePath = Prefs.get("TrackAnalysis.filePath","");
            JFileChooser jFileChooser = new JFileChooser(filePath);
            jFileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            jFileChooser.showDialog(null,"Open");

            File inputFile = jFileChooser.getSelectedFile();
            Prefs.set("TrackAnalysis.filePath",inputFile.getAbsolutePath());
            String outputPath = inputFile.getParent();
            String outputName;
            if (FilenameUtils.getExtension(inputFile.getName()).equals("xml")) {
                outputName = FilenameUtils.getBaseName(inputFile.getName());
            } else {
                outputName = inputFile.getName();
            }

            DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder documentBuilder = null;
            documentBuilder = documentBuilderFactory.newDocumentBuilder();

            filePath = inputFile.getAbsolutePath();
            FileInputStream fileInputStream = new FileInputStream(filePath);
            Document doc = documentBuilder.parse(fileInputStream);
            doc.getDocumentElement().normalize();

            HashMap<String,Object> params = new HashMap<>();
            params.put("Export_file",outputPath+"\\"+outputName+"_CORRECTED.xml");
            System.out.println("New results will be saved to "+outputPath+"\\"+outputName+"_CORRECTED.xml");

            // Iterating over each experiment
            NodeList experimentNodes = doc.getElementsByTagName("EXPERIMENT");
            for (int i=0;i<experimentNodes.getLength();i++) {
                NamedNodeMap namedNodeMap = experimentNodes.item(i).getAttributes();

                ArrayList<CellForFix> cells = new ArrayList<>();

                Result templateResult = new Result();
                templateResult.setFile(new File(namedNodeMap.getNamedItem("FILEPATH").getNodeValue()));
                templateResult.setComment(namedNodeMap.getNamedItem("COMMENT").getNodeValue());
                templateResult.setWell(namedNodeMap.getNamedItem("WELL").getNodeValue());
                templateResult.setField(Integer.parseInt(namedNodeMap.getNamedItem("FIELD").getNodeValue()));

                // Running through each track.  Track assignments aren't stored, as they will  be re-calculated
                NodeList trackNodes = experimentNodes.item(i).getChildNodes();
                for (int j=0;j<trackNodes.getLength();j++) {

                    // Running through all points in the track, adding them to the ArrayList
                    NodeList pointNodes = trackNodes.item(j).getChildNodes();
                    for (int k=0;k<pointNodes.getLength();k++) {
                        Node pointNode = pointNodes.item(k);

                        float x = Float.parseFloat(pointNode.getAttributes().getNamedItem("X").getNodeValue());
                        float y = Float.parseFloat(pointNode.getAttributes().getNamedItem("Y").getNodeValue());
                        int f = (int) Math.round(Float.parseFloat(pointNode.getAttributes().getNamedItem("FR").getNodeValue()));
                        int nLinks = (int) Math.round(Float.parseFloat(pointNode.getAttributes().getNamedItem("N_LKS").getNodeValue()));
                        float nLinksNorm = Float.parseFloat(pointNode.getAttributes().getNamedItem("N_LKS_N").getNodeValue());

                        CellForFix cell = new CellForFix(new float[]{x-1E-3f,x-1E-3f,x+1E-3f,x+1E-3f,x-1E-3f}, new float[]{y+1E-3f,y-1E-3f,y-1E-3f,y+1E-3f,y+1E-3f}, Roi.POLYGON,nLinks,nLinksNorm);
                        cell.setPosition(f);
                        cells.add(cell);

                    }
                }

                HashMap<Integer,ArrayList<CellForFix>> tracks = trackCells(cells);
                ArrayList<Result> result = compileResult(templateResult,tracks);
                combinedResults.add(result);

            }

            Relate_Cells.exportResultsXML(combinedResults,params);

            System.out.println("Complete!");

        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException
                | UnsupportedLookAndFeelException | ParserConfigurationException | SAXException | IOException
                | TransformerException e) {
            e.printStackTrace();
        }
    }

    protected static HashMap<Integer,ArrayList<CellForFix>> trackCells(ArrayList<CellForFix> cells) {
        double maxDist = 50;

        HashMap<Integer,ArrayList<CellForFix>> tracks = new HashMap<>();

        // Getting the maximum frame number
        int maxFr = 0;
        for (CellForFix cell:cells) {
            if (cell.getPosition() > maxFr) {
                maxFr = cell.getPosition();
            }
        }

        int trackID = 0;
        Random rand = new Random(System.currentTimeMillis());
        // Assigning new trackIDs to all cells in the first frame
        for (CellForFix cell:cells) {
            if (cell.getPosition() == 1) {
                // Assigning the next available track number to this track
                cell.setTrackID(trackID++);

                // Creating a new track ArrayList and adding it to the tracks ArrayList
                ArrayList<CellForFix> track = new ArrayList<>();
                track.add(cell);
                tracks.put(cell.getTrackID(),track);

                // Assigning a random colour to this new track
                cell.setColour(Color.getHSBColor(rand.nextFloat(),1,1));
            }
        }

        // Going through each frame, getting the current cells and the ones from the previous frame
        for (int fr = 2; fr <= maxFr; fr++) {
            ArrayList<CellForFix> prevCells = new ArrayList<>();
            ArrayList<CellForFix> currCells = new ArrayList<>();

            for (CellForFix cell:cells) {
                if (cell.getPosition() + 1 == fr) {
                    prevCells.add(cell);
                } else if (cell.getPosition() == fr) {
                    currCells.add(cell);
                }
            }

            if (currCells.size() > 0) {
                // Creating a 2D cost matrix for the overlap.  A maximum linking distance is specified, above which costs are Inf
                float[][] cost = new float[currCells.size()][prevCells.size()];
                for (int curr = 0; curr < cost.length; curr++) {
                    for (int prev = 0; prev < cost[0].length; prev++) {
                        double[] currCent = currCells.get(curr).getContourCentroid();
                        double[] prevCent = prevCells.get(prev).getContourCentroid();

                        double dist = Math.sqrt((prevCent[0] - currCent[0]) * (prevCent[0] - currCent[0]) + (prevCent[1] - currCent[1]) * (prevCent[1] - currCent[1]));

                        if (dist < maxDist) {
                            cost[curr][prev] = (float) dist;
                        } else {
                            cost[curr][prev] = Float.MAX_VALUE;
                        }
                    }
                }

                // Running the Munkres algorithm to assign matches.
                int[] assignment = new MunkresAssignment(cost).solve();

                // Applying the calculated track IDs to the cells
                for (int curr = 0; curr < assignment.length; curr++) {
                    if (assignment[curr] == -1) {
                        CellForFix currCell = currCells.get(curr);
                        // Assigning the next available track number to this track
                        currCell.setTrackID(trackID++);

                        // Creating a new track ArrayList and adding it to the tracks ArrayList
                        ArrayList<CellForFix> track = new ArrayList<>();
                        track.add(currCell);
                        tracks.put(currCell.getTrackID(), track);

                        // Assigning a random colour to this new track
                        currCell.setColour(Color.getHSBColor(rand.nextFloat(), 1, 1));

                    } else {
                        // Applying TrackID and colour from the previous cell to the newly linked cell
                        CellForFix prevCell = prevCells.get(assignment[curr]);
                        CellForFix currCell = currCells.get(curr);

                        // Checking object separation
                        double[] currCent = currCell.getContourCentroid();
                        double[] prevCent = prevCell.getContourCentroid();

                        double dist = Math.sqrt((prevCent[0] - currCent[0]) * (prevCent[0] - currCent[0]) + (prevCent[1] - currCent[1]) * (prevCent[1] - currCent[1]));

                        if (dist < maxDist) {
                            currCell.setTrackID(prevCell.getTrackID());
                            currCell.setColour(prevCell.getColour());

                            // Adding the new object to that track
                            tracks.get(prevCell.getTrackID()).add(currCell);

                        } else {
                            // If the distance is larger than the linking distance, creating a new track
                            // Assigning the next available track number to this track
                            currCell.setTrackID(trackID++);

                            // Creating a new track ArrayList and adding it to the tracks ArrayList
                            ArrayList<CellForFix> track = new ArrayList<>();
                            track.add(currCell);
                            tracks.put(currCell.getTrackID(), track);

                            // Assigning a random colour to this new track
                            currCell.setColour(Color.getHSBColor(rand.nextFloat(), 1, 1));

                        }
                    }
                }
            }
        }

        return tracks;

    }

    private static ArrayList<Result> compileResult(Result templateResult, HashMap<Integer,ArrayList<CellForFix>> tracks) {
        ArrayList<Result> results = new ArrayList<>();

        // Runs through each track, creating a new Result object and adding it to the ArrayList of results.  Each track
        // is comprised of an ArrayList of Cells
        for (int key:tracks.keySet()) {
            ArrayList<CellForFix> cells = tracks.get(key);

            // Copying the fundamental parameters from the template
            Result result = new Result();
            result.setFile(templateResult.getFile());
            result.setWell(templateResult.getWell());
            result.setField(templateResult.getField());
            result.setComment(templateResult.getComment());

            // Setting the trackID number (one value per track)
            result.setTrackID(key);

            // Initialising the result arrays
            double[] x = new double[cells.size()];
            double[] y = new double[cells.size()];
            double[] frame = new double[cells.size()];
            double[] nLinks = new double[cells.size()];
            double[] nLinksNorm = new double[cells.size()];

            // Going through each cell for the current track and adding its information to the result arrays
            for (int idx = 0; idx < cells.size(); idx++) {
                CellForFix cell = cells.get(idx);

                double[] cent = cell.getContourCentroid();
                x[idx] = cent[0];
                y[idx] = cent[1];

                frame[idx] = cell.getPosition();
                nLinks[idx] = cell.getnLinks();
                nLinksNorm[idx] = cell.getnLinksNorm();
            }

            // Adding the result arrays to the result structure
            result.setX(x);
            result.setY(y);
            result.setFrame(frame);
            result.setnLinks(nLinks);
            result.setnLinksNorm(nLinksNorm);

            // Adding the current result to the ArrayList of results
            results.add(result);

        }

        return results;

    }
}

class CellForFix extends Cell {
    private int nLinks;
    private float nLinksNorm;

    CellForFix(float[] xPoints, float[] yPoints, int type, int nLinks, float nLinksNorm) {
        super(xPoints, yPoints, type);

        this.nLinks = nLinks;
        this.nLinksNorm = nLinksNorm;

    }

    public int getnLinks() {
        return nLinks;
    }

    public float getnLinksNorm() {
        return nLinksNorm;
    }
}
